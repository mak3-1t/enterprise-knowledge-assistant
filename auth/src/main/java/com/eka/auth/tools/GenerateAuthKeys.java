package com.eka.auth.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;

/** Explicit local key-generation utility. Never called during application startup. */
public final class GenerateAuthKeys {
    private GenerateAuthKeys() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "Usage: GenerateAuthKeys <private-output-directory>");
        }
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path privateFile = directory.resolve("private.pem");
        Path publicFile = directory.resolve("public.pem");
        if (Files.exists(privateFile) || Files.exists(publicFile)) {
            throw new IllegalStateException(
                    "Key files already exist; existing keys will not be overwritten");
        }
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        var pair = generator.generateKeyPair();
        // Apply owner-only POSIX permissions at creation where supported; Windows uses directory
        // ACLs.
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(
                    privateFile,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            Set.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE)));
            Files.writeString(privateFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        } else {
            Files.writeString(
                    privateFile,
                    pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                    StandardOpenOption.CREATE_NEW);
        }
        Files.writeString(
                publicFile,
                pem("PUBLIC KEY", pair.getPublic().getEncoded()),
                StandardOpenOption.CREATE_NEW);
        System.out.println("Created private.pem and public.pem in " + directory);
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN "
                + type
                + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
                + "\n-----END "
                + type
                + "-----\n";
    }
}
