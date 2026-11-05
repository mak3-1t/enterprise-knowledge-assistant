package com.eka.auth.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.eka.auth.internal.persistence.RegistrationRepository;
import com.eka.auth.internal.security.RegistrationSettings;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

class RegistrationServiceTest {
    private static ValidatorFactory validation;
    private final RegistrationRepository repository = mock(RegistrationRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private static final String PASSWORD = "Local-test-password";

    @BeforeAll
    static void validation() {
        validation = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidation() {
        validation.close();
    }

    private RegistrationService service(boolean enabled) {
        return new RegistrationService(
                new RegistrationSettings(enabled),
                repository,
                passwords,
                validation.getValidator());
    }

    @Test
    void disabledRegistrationNeverHashesOrWritesEvenWhenCalledWithoutHttp() {
        assertThatThrownBy(
                        () ->
                                service(false)
                                        .register(
                                                new RegistrationService.Request(
                                                        "team", "a@example.test", PASSWORD)))
                .isInstanceOf(RegistrationService.RegistrationDisabled.class);
        verifyNoInteractions(repository, passwords);
    }

    @Test
    void normalizesIdentifiersPreservesPasswordAndAssignsAdmin() {
        String password = " " + PASSWORD + " ";
        when(passwords.encode(password)).thenReturn("test-only-encoded");
        var result =
                service(true)
                        .register(
                                new RegistrationService.Request(
                                        " Team-A ", " A@Example.Test ", password));
        assertThat(result.tenantSlug()).isEqualTo("team-a");
        assertThat(result.email()).isEqualTo("a@example.test");
        assertThat(result.role()).isEqualTo("ADMIN");
        assertThat(result.tenantId()).isNotNull();
        assertThat(result.userId()).isNotEqualTo(result.tenantId());
        verify(repository)
                .create(
                        result.tenantId(),
                        "team-a",
                        result.userId(),
                        "a@example.test",
                        "test-only-encoded");
        verify(passwords).encode(password);
    }

    @Test
    void invalidInputNeverHashesOrWrites() {
        for (var request :
                List.of(
                        new RegistrationService.Request(null, "a@example.test", PASSWORD),
                        new RegistrationService.Request("bad_slug", "a@example.test", PASSWORD),
                        new RegistrationService.Request("a".repeat(64), "a@example.test", PASSWORD),
                        new RegistrationService.Request("team", "invalid", PASSWORD),
                        new RegistrationService.Request("team", "a@example.test", null),
                        new RegistrationService.Request("team", "a@example.test", "short"),
                        new RegistrationService.Request("team", "a@example.test", " ".repeat(12)),
                        new RegistrationService.Request(
                                "team", "a@example.test", "a".repeat(73)))) {
            assertThatThrownBy(() -> service(true).register(request))
                    .isInstanceOf(RegistrationService.InvalidRegistration.class);
        }
        assertThatThrownBy(() -> service(true).register(null))
                .isInstanceOf(RegistrationService.InvalidRegistration.class);
        verifyNoInteractions(repository, passwords);
    }

    @Test
    void enforcesBcryptUtf8ByteLimitBeforeHashing() {
        assertThatThrownBy(
                        () ->
                                service(true)
                                        .register(
                                                new RegistrationService.Request(
                                                        "team", "a@example.test", "é".repeat(37))))
                .isInstanceOf(RegistrationService.InvalidRegistration.class);
        verifyNoInteractions(repository, passwords);
    }

    @Test
    void duplicateFailureDoesNotExposeDatabaseError() {
        when(passwords.encode(PASSWORD)).thenReturn("hash");
        doThrow(new DuplicateKeyException("private-db-detail"))
                .when(repository)
                .create(any(), any(), any(), any(), any());
        assertThatThrownBy(
                        () ->
                                service(true)
                                        .register(
                                                new RegistrationService.Request(
                                                        "team", "a@example.test", PASSWORD)))
                .isInstanceOf(RegistrationService.RegistrationConflict.class)
                .hasMessage(null)
                .hasNoCause();
    }

    @Test
    void requestAndResponseToStringRedactSensitiveFields() {
        var request = new RegistrationService.Request("team", "a@example.test", PASSWORD);
        assertThat(request.toString()).doesNotContain(PASSWORD, "a@example.test");
        when(passwords.encode(PASSWORD)).thenReturn("hash");
        assertThat(service(true).register(request).toString())
                .doesNotContain(PASSWORD, "a@example.test", "hash");
    }
}
