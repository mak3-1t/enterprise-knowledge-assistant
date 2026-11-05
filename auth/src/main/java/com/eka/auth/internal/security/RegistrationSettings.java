package com.eka.auth.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("eka.auth.registration")
public record RegistrationSettings(boolean enabled) {}
