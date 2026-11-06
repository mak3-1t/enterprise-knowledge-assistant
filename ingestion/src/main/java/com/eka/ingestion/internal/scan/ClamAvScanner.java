package com.eka.ingestion.internal.scan;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eka.upload.scan.enabled", havingValue = "true")
public class ClamAvScanner implements MalwareScanner {
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        Thread thread = new Thread(task, "clamav-deadline");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final String host;
    private final int port;
    private final int timeoutMillis;

    public ClamAvScanner(
            @Value("${eka.upload.scan.clamav-host:localhost}") String host,
            @Value("${eka.upload.scan.clamav-port:3310}") int port,
            @Value("${eka.upload.scan.clamav-timeout-ms:30000}") int timeoutMillis) {
        if (port < 1 || port > 65535 || timeoutMillis < 100 || timeoutMillis > 60000) {
            throw new IllegalArgumentException("Invalid scanner configuration");
        }
        this.host = host;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void scan(Path file) {
        try (var input = Files.newInputStream(file)) {
            scan(input);
        } catch (IOException unavailable) {
            throw new ScanUnavailable("SCANNER_UNAVAILABLE");
        }
    }

    void scan(InputStream input) {
        try (var socket = new Socket()) {
            var deadline =
                    DEADLINES.schedule(() -> close(socket), timeoutMillis, TimeUnit.MILLISECONDS);
            try {
                socket.connect(new InetSocketAddress(host, port), Math.min(5000, timeoutMillis));
                socket.setSoTimeout(timeoutMillis);
                var output = new DataOutputStream(socket.getOutputStream());
                output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                long sent = 0;
                {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        sent += read;
                        if (sent > 20971520) {
                            throw new InspectionRejected("FILE_TOO_LARGE");
                        }
                        output.writeInt(read);
                        output.write(buffer, 0, read);
                    }
                }
                output.writeInt(0);
                output.flush();
                var response = new ByteArrayOutputStream();
                int next;
                boolean terminated = false;
                while ((next = socket.getInputStream().read()) != -1) {
                    if (next == 0) {
                        terminated = true;
                        break;
                    }
                    if (response.size() >= 512) {
                        throw new ScanUnavailable("SCANNER_UNAVAILABLE");
                    }
                    response.write(next);
                }
                String reply = response.toString(StandardCharsets.US_ASCII);
                if (!terminated) {
                    throw new ScanUnavailable("SCANNER_UNAVAILABLE");
                }
                if (reply.equals("stream: OK")) {
                    return;
                }
                if (reply.startsWith("stream: ") && reply.endsWith(" FOUND")) {
                    if (reply.startsWith("stream: Heuristics.Encrypted.")) {
                        throw new InspectionRejected("PASSWORD_PROTECTED");
                    }
                    if (reply.startsWith("stream: Heuristics.Limits.Exceeded")) {
                        throw new InspectionRejected("SCAN_LIMIT_EXCEEDED");
                    }
                    if (reply.startsWith("stream: Heuristics.OLE2.ContainsMacros")) {
                        throw new InspectionRejected("MACRO_DETECTED");
                    }
                    throw new InspectionRejected("MALWARE_DETECTED");
                }
                throw new ScanUnavailable("SCANNER_UNAVAILABLE");
            } finally {
                deadline.cancel(false);
            }
        } catch (IOException failure) {
            throw new ScanUnavailable("SCANNER_UNAVAILABLE");
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing the deadline socket is best-effort; no document data is logged.
        }
    }
}
