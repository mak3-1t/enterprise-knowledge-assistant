package com.eka.auth.internal.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eka.auth")
public record AuthSettings(
        @NotNull Resource privateKey,
        @NotNull Resource publicKey,
        @NotBlank String issuer,
        @NotBlank String audience) {}
