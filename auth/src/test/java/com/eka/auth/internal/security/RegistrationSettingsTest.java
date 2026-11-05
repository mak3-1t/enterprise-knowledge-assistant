package com.eka.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RegistrationSettingsTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SettingsConfig.class);

    @Test
    void registrationDefaultsToDisabledAndRequiresExplicitTrue() {
        runner.run(
                context ->
                        assertThat(context.getBean(RegistrationSettings.class).enabled())
                                .isFalse());
        runner.withPropertyValues("eka.auth.registration.enabled=false")
                .run(
                        context ->
                                assertThat(context.getBean(RegistrationSettings.class).enabled())
                                        .isFalse());
        runner.withPropertyValues("eka.auth.registration.enabled=true")
                .run(
                        context ->
                                assertThat(context.getBean(RegistrationSettings.class).enabled())
                                        .isTrue());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RegistrationSettings.class)
    static class SettingsConfig {}
}
