package com.eka.auth.internal.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev & !prod & !production")
@ConditionalOnProperty(name = "eka.auth.bootstrap.enabled", havingValue = "true")
public class DevAccountProvisioner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DevAccountProvisioner.class);
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;

    public DevAccountProvisioner(
            JdbcClient jdbc, PasswordEncoder passwords, Environment environment) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String slug = required("tenant-slug");
        String email = required("email").strip().toLowerCase(Locale.ROOT);
        String password = required("password");
        String role = required("role");
        if (!slug.matches("[a-z0-9]+(-[a-z0-9]+)*")
                || slug.length() > 63
                || !email.matches("[^\\s@]+@[^\\s@]+")
                || email.length() > 254
                || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !(role.equals("ADMIN") || role.equals("MEMBER"))) {
            throw new IllegalStateException("Invalid dev bootstrap configuration");
        }
        jdbc.sql("INSERT INTO auth.tenants (id, slug) VALUES (?, ?) ON CONFLICT (slug) DO NOTHING")
                .param(UUID.randomUUID())
                .param(slug)
                .update();
        UUID tenant =
                jdbc.sql("SELECT id FROM auth.tenants WHERE slug = ? AND active")
                        .param(slug)
                        .query(UUID.class)
                        .optional()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Dev bootstrap tenant is inactive"));
        int created =
                jdbc.sql(
                                """
                INSERT INTO auth.accounts (id, tenant_id, email, password_hash, role)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (tenant_id, email) DO NOTHING
                """)
                        .param(UUID.randomUUID())
                        .param(tenant)
                        .param(email)
                        .param(passwords.encode(password))
                        .param(role)
                        .update();
        LOG.info("auth_outcome=dev_bootstrap account_created={}", created == 1);
    }

    private String required(String suffix) {
        String value = environment.getProperty("eka.auth.bootstrap." + suffix);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing dev bootstrap configuration: " + suffix);
        }
        return value;
    }
}
