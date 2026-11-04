package com.eka.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eka.auth.tools.GenerateAuthKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

class GenerateAuthKeysTest {
    @TempDir Path directory;

    @Test
    void generatedFilesLoadAndExistingKeysCannotBeOverwritten() throws Exception {
        GenerateAuthKeys.main(new String[] {directory.toString()});
        var settings =
                new AuthSettings(
                        new FileSystemResource(directory.resolve("private.pem")),
                        new FileSystemResource(directory.resolve("public.pem")),
                        "https://issuer.test",
                        "eka");
        assertThat(new AuthConfiguration().signingKey(settings).size()).isEqualTo(3072);
        byte[] original = Files.readAllBytes(directory.resolve("private.pem"));
        assertThatThrownBy(() -> GenerateAuthKeys.main(new String[] {directory.toString()}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(directory.resolve("private.pem"))).isEqualTo(original);
    }
}
