package com.eka.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

class AuthTokenTest {
    private static KeyPair keys;
    private static KeyPair otherKeys;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String USER = UUID.randomUUID().toString();
    private static final String TENANT = UUID.randomUUID().toString();
    private final AuthConfiguration configuration = new AuthConfiguration();

    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        otherKeys = generator.generateKeyPair();
    }

    @Test
    void loadsMatchingExternalKeysAndRejectsMismatchedKeys() throws Exception {
        var loaded = configuration.signingKey(settings(keys, keys));
        assertThat(loaded.toRSAPublicKey()).isEqualTo(keys.getPublic());
        assertThatThrownBy(() -> configuration.signingKey(settings(keys, otherKeys)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingKeyMaterialFailsClearly() {
        var missing =
                new AuthSettings(
                        new ByteArrayResource(new byte[0]),
                        pem("PUBLIC KEY", keys.getPublic().getEncoded()),
                        "https://issuer.test",
                        "eka-test");
        assertThatThrownBy(() -> configuration.signingKey(missing))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void validTokenWorksAndExpirationUsesInjectedClock() throws Exception {
        var key = rsa(keys);
        String token = token(key, claims().build());
        var decoder = configuration.jwtDecoder(key, settings(keys, keys), CLOCK);
        assertThat(decoder.decode(token).getSubject()).isEqualTo(USER);
        var later =
                configuration.jwtDecoder(
                        key,
                        settings(keys, keys),
                        Clock.fixed(NOW.plusSeconds(901), ZoneOffset.UTC));
        assertThatThrownBy(() -> later.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void missingAndInvalidClaimsAreRejected() throws Exception {
        var key = rsa(keys);
        var decoder = configuration.jwtDecoder(key, settings(keys, keys), CLOCK);
        var invalid =
                List.of(
                        claims().issuer("https://wrong.test").build(),
                        claims().audience(List.of("wrong")).build(),
                        claims().subject("invalid").build(),
                        claims().claim("tenant_id", "invalid").build(),
                        claims().expiresAt(NOW.plusSeconds(901)).build(),
                        claims().issuedAt(NOW.plusSeconds(1)).build());
        for (var claims : invalid) {
            String token = token(key, claims);
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        }
        var noExpiry =
                JwtClaimsSet.builder()
                        .issuer("https://issuer.test")
                        .audience(List.of("eka-test"))
                        .subject(USER)
                        .claim("tenant_id", TENANT)
                        .issuedAt(NOW)
                        .build();
        assertThatThrownBy(() -> decoder.decode(token(key, noExpiry)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void wrongSigningKeyIsRejected() throws Exception {
        var decoder = configuration.jwtDecoder(rsa(keys), settings(keys, keys), CLOCK);
        String token = token(rsa(otherKeys), claims().build());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder()
                .issuer("https://issuer.test")
                .audience(List.of("eka-test"))
                .subject(USER)
                .claim("tenant_id", TENANT)
                .issuedAt(NOW)
                .notBefore(NOW)
                .expiresAt(NOW.plusSeconds(900));
    }

    private String token(RSAKey key, JwtClaimsSet claims) {
        return configuration
                .jwtEncoder(key)
                .encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).keyId("eka-local").build(),
                                claims))
                .getTokenValue();
    }

    private static RSAKey rsa(KeyPair pair) {
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID("eka-local")
                .build();
    }

    private static AuthSettings settings(KeyPair privatePair, KeyPair publicPair) {
        return new AuthSettings(
                pem("PRIVATE KEY", privatePair.getPrivate().getEncoded()),
                pem("PUBLIC KEY", publicPair.getPublic().getEncoded()),
                "https://issuer.test",
                "eka-test");
    }

    private static Resource pem(String kind, byte[] bytes) {
        return new ByteArrayResource(
                ("-----BEGIN "
                                + kind
                                + "-----\n"
                                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
                                + "\n-----END "
                                + kind
                                + "-----\n")
                        .getBytes(StandardCharsets.US_ASCII));
    }
}
