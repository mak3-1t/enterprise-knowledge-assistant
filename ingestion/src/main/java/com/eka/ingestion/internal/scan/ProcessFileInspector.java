package com.eka.ingestion.internal.scan;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Heap/deadline isolation; OS/container CPU, filesystem and network isolation remain deployment
 * work.
 */
@Component
@ConditionalOnProperty(name = "eka.upload.scan.enabled", havingValue = "true")
public class ProcessFileInspector implements ContentInspector {
    private final int timeoutSeconds;

    public ProcessFileInspector(
            @Value("${eka.upload.scan.inspection-timeout-seconds:60}") int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("Invalid inspection timeout");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void inspect(Path file, DocumentFormat format) {
        Process child = null;
        try {
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Xmx256m");
            String classpath =
                    System.getProperty(
                            "surefire.test.class.path", System.getProperty("java.class.path"));
            if (bootJar(classpath)) {
                command.add("-Dloader.main=" + FileInspectionMain.class.getName());
                command.addAll(
                        List.of(
                                "-cp",
                                classpath,
                                "org.springframework.boot.loader.launch.PropertiesLauncher"));
            } else {
                command.addAll(List.of("-cp", classpath, FileInspectionMain.class.getName()));
            }
            command.add(format.name());
            command.add(file.toAbsolutePath().toString());
            var builder =
                    new ProcessBuilder(command)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            for (String name : List.of("SystemRoot", "WINDIR", "TEMP", "TMP", "TMPDIR")) {
                String value = System.getenv(name);
                if (value != null) {
                    builder.environment().put(name, value);
                }
            }
            child = builder.start();
            if (!child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new ScanUnavailable("PROCESSING_TIMEOUT");
            }
            int result = child.exitValue();
            if (result == 0) {
                return;
            }
            String code =
                    java.util.Map.of(
                                    10,
                                    "TYPE_MISMATCH",
                                    11,
                                    "CORRUPTED_FILE",
                                    12,
                                    "PASSWORD_PROTECTED",
                                    13,
                                    "MACRO_DETECTED",
                                    14,
                                    "ACTIVE_CONTENT_UNSUPPORTED",
                                    15,
                                    "FILE_TOO_LARGE")
                            .getOrDefault(result, "INSPECTION_UNAVAILABLE");
            if (result >= 10 && result <= 15) {
                throw new InspectionRejected(code);
            }
            throw new ScanUnavailable(code);
        } catch (IOException unavailable) {
            throw new ScanUnavailable("INSPECTION_UNAVAILABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ScanUnavailable("INSPECTION_UNAVAILABLE");
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                try {
                    child.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static boolean bootJar(String classpath) throws IOException {
        if (classpath.contains(File.pathSeparator) || !classpath.endsWith(".jar")) {
            return false;
        }
        try (var jar = new JarFile(classpath)) {
            var manifest = jar.getManifest();
            String main =
                    manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
            return main != null && main.startsWith("org.springframework.boot.loader.");
        }
    }
}
