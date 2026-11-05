package com.eka.auth.internal.service;

import com.eka.auth.internal.persistence.RegistrationRepository;
import com.eka.auth.internal.security.RegistrationSettings;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class RegistrationService {
    private final RegistrationSettings settings;
    private final RegistrationRepository registrations;
    private final PasswordEncoder passwords;
    private final Validator validator;

    public RegistrationService(
            RegistrationSettings settings,
            RegistrationRepository registrations,
            PasswordEncoder passwords,
            Validator validator) {
        this.settings = settings;
        this.registrations = registrations;
        this.passwords = passwords;
        this.validator = validator;
    }

    public RegisteredAccount register(Request request) {
        if (!settings.enabled()) {
            throw new RegistrationDisabled();
        }
        if (request == null
                || !validator.validate(request).isEmpty()
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidRegistration();
        }
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        // Hash before opening the short DB transaction, which commits both rows or neither.
        String hash = passwords.encode(request.password());
        try {
            registrations.create(tenant, request.tenantSlug(), user, request.email(), hash);
        } catch (DuplicateKeyException exception) {
            throw new RegistrationConflict();
        }
        return new RegisteredAccount(tenant, user, request.tenantSlug(), request.email(), "ADMIN");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(
            @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String tenantSlug,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {
        public Request {
            tenantSlug = normalize(tenantSlug);
            email = normalize(email);
        }

        private static String normalize(String value) {
            return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return "RegistrationRequest[REDACTED]";
        }
    }

    public record RegisteredAccount(
            UUID tenantId, UUID userId, String tenantSlug, String email, String role) {
        @Override
        public String toString() {
            return "RegisteredAccount[REDACTED]";
        }
    }

    public static class RegistrationDisabled extends RuntimeException {}

    public static class RegistrationConflict extends RuntimeException {}

    public static class InvalidRegistration extends RuntimeException {}
}
