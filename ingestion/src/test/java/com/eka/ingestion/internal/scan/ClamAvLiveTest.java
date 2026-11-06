package com.eka.ingestion.internal.scan;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in external-engine smoke: no test signature is written to the host filesystem. */
@EnabledIfEnvironmentVariable(named = "EKA_CLAMAV_TESTS", matches = "true")
class ClamAvLiveTest {
    @Test
    void realEngineAcceptsCleanBytesAndDetectsStandardInMemoryTestSignature() {
        String host = System.getenv().getOrDefault("EKA_CLAMAV_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("EKA_CLAMAV_PORT", "3310"));
        var scanner = new ClamAvScanner(host, port, 30000);
        assertThatCode(
                        () ->
                                scanner.scan(
                                        new ByteArrayInputStream(
                                                "clean scanner smoke"
                                                        .getBytes(StandardCharsets.US_ASCII))))
                .doesNotThrowAnyException();
        String testSignature =
                String.join(
                        "",
                        "X5O!P%@AP[4",
                        "\\PZX54(P^)7CC)7}",
                        "$EICAR-STANDARD-",
                        "ANTIVIRUS-TEST-FILE!",
                        "$H+H*");
        assertThatThrownBy(
                        () ->
                                scanner.scan(
                                        new ByteArrayInputStream(
                                                testSignature.getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(InspectionRejected.class)
                .hasMessage("MALWARE_DETECTED");
    }
}
