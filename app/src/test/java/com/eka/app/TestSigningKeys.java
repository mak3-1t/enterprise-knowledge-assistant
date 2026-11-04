package com.eka.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Fresh ephemeral test keys, never checked into Git or used for development. */
final class TestSigningKeys {
    static final KeyPair PAIR = generate();
    static final String PRIVATE = pem("PRIVATE KEY", PAIR.getPrivate().getEncoded());
    static final String PUBLIC = pem("PUBLIC KEY", PAIR.getPublic().getEncoded());
    static final String ISSUER = "https://issuer.example.test";
    static final String AUDIENCE = "eka-test";

    private TestSigningKeys() {}

    static void configure(DynamicPropertyRegistry registry) {
        registry.add("eka.auth.private-key", () -> PRIVATE);
        registry.add("eka.auth.public-key", () -> PUBLIC);
        registry.add("eka.auth.issuer", () -> ISSUER);
        registry.add("eka.auth.audience", () -> AUDIENCE);
    }

    static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot generate test keys", ex);
        }
    }

    private static String pem(String kind, byte[] bytes) {
        try {
            Path directory = Files.createTempDirectory("eka-test-keys-");
            directory.toFile().deleteOnExit();
            Path file = directory.resolve("key.pem");
            String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
            Files.writeString(
                    file,
                    "-----BEGIN " + kind + "-----\n" + encoded + "\n-----END " + kind + "-----\n");
            file.toFile().deleteOnExit();
            return file.toUri().toString();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot write ephemeral test keys", ex);
        }
    }
}
