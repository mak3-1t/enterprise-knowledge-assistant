package com.eka.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.eka.auth.internal.persistence.SessionRecord;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class SessionTokenIssuerTest {
    @Test
    void issuesSessionBoundJwtAndCapsAccessAtAbsoluteSessionExpiry() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var key =
                new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                        .privateKey((RSAPrivateKey) pair.getPrivate())
                        .keyID("eka-local")
                        .build();
        var settings =
                new AuthSettings(
                        new ByteArrayResource(new byte[0]),
                        new ByteArrayResource(new byte[0]),
                        "https://issuer.test",
                        "eka-test");
        var config = new AuthConfiguration();
        var issuer = new SessionTokenIssuer(config.jwtEncoder(key), settings);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var decoder = config.jwtDecoder(key, settings, Clock.fixed(now, ZoneOffset.UTC));
        for (long remaining : new long[] {604800, 30}) {
            var session =
                    new SessionRecord(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            now.plusSeconds(remaining));
            String refresh = RefreshTokens.create();
            var response = issuer.issue(session, refresh, now);
            var jwt = decoder.decode(response.accessToken());
            assertThat(jwt.getClaimAsString("sid")).isEqualTo(session.id().toString());
            assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo(session.tenantId().toString());
            assertThat(jwt.getSubject()).isEqualTo(session.userId().toString());
            assertThat(response.expiresIn()).isEqualTo(Math.min(900, remaining));
            assertThat(response.refreshExpiresIn()).isEqualTo(remaining);
            assertThat(response.refreshToken()).isEqualTo(refresh);
            assertThat(response.toString()).doesNotContain(refresh, response.accessToken());
            assertThat(jwt.getClaims()).doesNotContainKeys("role", "password", "email");
        }
    }
}
