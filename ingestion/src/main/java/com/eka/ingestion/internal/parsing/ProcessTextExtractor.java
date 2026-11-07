package com.eka.ingestion.internal.parsing;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import tools.jackson.databind.json.JsonMapper;

/** Heap/deadline isolation only; this is not an OS/network sandbox. */
public final class ProcessTextExtractor implements TextExtractor {
    public ParsedDocument extract(Path input, DocumentFormat format) {
        Path output = null;
        Process child = null;
        try {
            output = Files.createTempFile("eka-extracted-", ".json");
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Xmx256m");
            String classpath =
                    System.getProperty(
                            "surefire.test.class.path", System.getProperty("java.class.path"));
            if (bootJar(classpath)) {
                command.add("-Dloader.main=" + TextExtractionMain.class.getName());
                command.addAll(
                        List.of(
                                "-cp",
                                classpath,
                                "org.springframework.boot.loader.launch.PropertiesLauncher"));
            } else {
                command.addAll(List.of("-cp", classpath, TextExtractionMain.class.getName()));
            }
            command.addAll(
                    List.of(
                            format.name(),
                            input.toAbsolutePath().toString(),
                            output.toAbsolutePath().toString()));
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
            if (!child.waitFor(60, TimeUnit.SECONDS)) {
                throw new TextExtractionFailure("PROCESSING_TIMEOUT");
            }
            if (child.exitValue() != 0) {
                throw new TextExtractionFailure(
                        Map.of(
                                        10,
                                        "TYPE_MISMATCH",
                                        12,
                                        "PASSWORD_PROTECTED",
                                        13,
                                        "MACRO_DETECTED",
                                        14,
                                        "ACTIVE_CONTENT_UNSUPPORTED",
                                        15,
                                        "FILE_TOO_LARGE",
                                        20,
                                        "EXTRACT_EMPTY",
                                        21,
                                        "EXTRACT_LIMIT",
                                        22,
                                        "CORRUPTED_FILE")
                                .getOrDefault(child.exitValue(), "PARSE_UNAVAILABLE"));
            }
            long bytes = Files.size(output);
            if (bytes < 1 || bytes > 8388608) {
                throw new TextExtractionFailure("EXTRACT_LIMIT");
            }
            var parsed =
                    JsonMapper.builder()
                            .build()
                            .readValue(Files.readAllBytes(output), ParsedDocument.class);
            validate(parsed, format);
            return parsed;
        } catch (TextExtractionFailure failure) {
            throw failure;
        } catch (RuntimeException | IOException failure) {
            throw new TextExtractionFailure("PARSE_UNAVAILABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TextExtractionFailure("PARSE_UNAVAILABLE");
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                try {
                    child.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException failure) {
                    System.getLogger(ProcessTextExtractor.class.getName())
                            .log(
                                    System.Logger.Level.WARNING,
                                    "Extracted temporary output cleanup deferred");
                }
            }
        }
    }

    private static void validate(ParsedDocument parsed, DocumentFormat format) {
        if (!"apache-tika:3.3.3".equals(parsed.parserVersion())
                || parsed.sections().isEmpty()
                || parsed.sections().size() > 2000) {
            throw new TextExtractionFailure("PARSE_UNAVAILABLE");
        }
        long size = 0;
        int page = 0;
        for (var section : parsed.sections()) {
            if (section.text() == null || section.text().isBlank()) {
                throw new TextExtractionFailure("PARSE_UNAVAILABLE");
            }
            size += section.text().length();
            if (format == DocumentFormat.PDF) {
                if (section.page() == null || section.page() <= page || section.page() > 2000) {
                    throw new TextExtractionFailure("PARSE_UNAVAILABLE");
                }
                page = section.page();
            } else if (section.page() != null) {
                throw new TextExtractionFailure("PARSE_UNAVAILABLE");
            }
        }
        if (size > TikaTextExtractor.MAX_CHARACTERS) {
            throw new TextExtractionFailure("EXTRACT_LIMIT");
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
