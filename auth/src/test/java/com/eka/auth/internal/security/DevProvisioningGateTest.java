package com.eka.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class DevProvisioningGateTest {
    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner()
                    .withUserConfiguration(DevAccountProvisioner.class)
                    .withBean(
                            JdbcClient.class,
                            () -> JdbcClient.create(new DriverManagerDataSource()))
                    .withBean(PasswordEncoder.class, BCryptPasswordEncoder::new);

    @Test
    void onlyExplicitDevOptInEnablesProvisioning() {
        contexts.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("eka.auth.bootstrap.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DevAccountProvisioner.class));
        contexts.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).doesNotHaveBean(DevAccountProvisioner.class));
    }

    @Test
    void neitherDefaultNorProductionProfilesCanEnableProvisioning() {
        contexts.withPropertyValues("eka.auth.bootstrap.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DevAccountProvisioner.class));
        for (String production : new String[] {"prod", "production"}) {
            contexts.withInitializer(
                            context ->
                                    context.getEnvironment().setActiveProfiles("dev", production))
                    .withPropertyValues("eka.auth.bootstrap.enabled=true")
                    .run(
                            context ->
                                    assertThat(context)
                                            .doesNotHaveBean(DevAccountProvisioner.class));
        }
    }
}
