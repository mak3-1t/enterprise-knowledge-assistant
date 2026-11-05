package com.eka.auth.internal.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/** 256-bit random bearer secrets, stored only as SHA-256 digests. */
public final class RefreshTokens {
    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokens() {}

    public static String create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static Optional<String> hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        byte[] bytes = Base64.getUrlDecoder().decode(token);
        if (bytes.length != 32
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(token.getBytes(StandardCharsets.US_ASCII))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
