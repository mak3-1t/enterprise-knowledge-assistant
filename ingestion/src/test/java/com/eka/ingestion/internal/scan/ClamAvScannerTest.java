package com.eka.ingestion.internal.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
class ClamAvScannerTest {
    @TempDir Path directory;

    @Test
    void streamsLengthPrefixedBytesAndAcceptsOnlyCompleteCleanReply() throws Exception {
        check(
                "stream: OK\0",
                2000,
                scanner -> assertThatCode(() -> scanner.scan(file())).doesNotThrowAnyException());
    }

    @Test
    void infectedReplyIsStableRejectionWithoutExposingSignature() throws Exception {
        check(
                "stream: test-only-signature FOUND\0",
                2000,
                scanner ->
                        assertThatThrownBy(() -> scanner.scan(file()))
                                .hasMessage("MALWARE_DETECTED")
                                .hasNoCause());
    }

    @Test
    void encryptedMacroAndScanLimitHeuristicsAreRejectedWithSpecificSafeCodes() throws Exception {
        check(
                "stream: Heuristics.Encrypted.PDF FOUND\0",
                2000,
                scanner ->
                        assertThatThrownBy(() -> scanner.scan(file()))
                                .hasMessage("PASSWORD_PROTECTED"));
        check(
                "stream: Heuristics.OLE2.ContainsMacros FOUND\0",
                2000,
                scanner ->
                        assertThatThrownBy(() -> scanner.scan(file()))
                                .hasMessage("MACRO_DETECTED"));
        check(
                "stream: Heuristics.Limits.Exceeded.MaxFileSize FOUND\0",
                2000,
                scanner ->
                        assertThatThrownBy(() -> scanner.scan(file()))
                                .hasMessage("SCAN_LIMIT_EXCEEDED"));
    }

    @Test
    void unknownErrorAndTruncatedCleanReplyAreNeverAccepted() throws Exception {
        for (String reply :
                new String[] {"stream: size limit exceeded ERROR\0", "stream: OK", "other: OK\0"}) {
            check(
                    reply,
                    2000,
                    scanner ->
                            assertThatThrownBy(() -> scanner.scan(file()))
                                    .hasMessage("SCANNER_UNAVAILABLE"));
        }
    }

    @Test
    void stalledScannerIsClosedByDeadline() throws Exception {
        check(
                null,
                150,
                scanner ->
                        assertThatThrownBy(() -> scanner.scan(file()))
                                .hasMessage("SCANNER_UNAVAILABLE"));
    }

    private Path file() throws Exception {
        return Files.writeString(
                directory.resolve("sample.txt"), "scanner test", StandardCharsets.UTF_8);
    }

    private void check(String reply, int timeout, Check check) throws Exception {
        try (var server = new ServerSocket(0);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var peer =
                    executor.submit(
                            () -> {
                                try (var socket = server.accept()) {
                                    var input = new DataInputStream(socket.getInputStream());
                                    assertThat(
                                                    new String(
                                                            input.readNBytes(10),
                                                            StandardCharsets.US_ASCII))
                                            .isEqualTo("zINSTREAM\0");
                                    var contents = new ByteArrayOutputStream();
                                    int size;
                                    while ((size = input.readInt()) != 0) {
                                        assertThat(size).isBetween(1, 16384);
                                        contents.write(input.readNBytes(size));
                                    }
                                    assertThat(contents.toString(StandardCharsets.UTF_8))
                                            .isEqualTo("scanner test");
                                    if (reply == null) {
                                        Thread.sleep(500);
                                    } else {
                                        socket.getOutputStream()
                                                .write(reply.getBytes(StandardCharsets.US_ASCII));
                                        socket.getOutputStream().flush();
                                    }
                                }
                                return null;
                            });
            check.run(new ClamAvScanner("localhost", server.getLocalPort(), timeout));
            peer.get();
        }
    }

    private interface Check {
        void run(ClamAvScanner scanner) throws Exception;
    }
}
