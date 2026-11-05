package com.eka.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class RefreshTokensTest {
    @Test
    void generatesIndependent256BitSecretsAndDeterministicDigests() {
        var values = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String raw = RefreshTokens.create();
            assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
            assertThat(values.add(raw)).isTrue();
            String digest = RefreshTokens.hash(raw).orElseThrow();
            assertThat(digest).matches("[0-9a-f]{64}").isNotEqualTo(raw);
            assertThat(RefreshTokens.hash(raw)).contains(digest);
        }
    }

    @Test
    void rejectsMalformedAndNoncanonicalEncodings() {
        assertThat(RefreshTokens.hash(null)).isEmpty();
        for (String value :
                java.util.List.of(
                        "",
                        "short",
                        "A".repeat(44),
                        "A".repeat(42) + "=",
                        "A".repeat(42) + "B",
                        "é".repeat(43))) {
            assertThat(RefreshTokens.hash(value)).isEmpty();
        }
    }
}
