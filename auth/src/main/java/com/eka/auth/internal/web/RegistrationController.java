package com.eka.auth.internal.web;

import com.eka.auth.internal.security.AuthEvents;
import com.eka.auth.internal.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class RegistrationController {
    private final RegistrationService registration;
    private final AuthEvents events;

    public RegistrationController(RegistrationService registration, AuthEvents events) {
        this.registration = registration;
        this.events = events;
    }

    @PostMapping("/register")
    public ResponseEntity<RegistrationService.RegisteredAccount> register(
            @Valid @RequestBody RegistrationService.Request request) {
        var account = registration.register(request);
        // Repository transaction has committed before a success outcome is recorded.
        events.record(AuthEvents.Outcome.REGISTRATION_SUCCEEDED);
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(account);
    }

    @ExceptionHandler(RegistrationService.RegistrationDisabled.class)
    ResponseEntity<ErrorResponse> disabled() {
        events.record(AuthEvents.Outcome.REGISTRATION_DISABLED);
        return error(404, "not_found");
    }

    @ExceptionHandler(RegistrationService.RegistrationConflict.class)
    ResponseEntity<ErrorResponse> conflict() {
        events.record(AuthEvents.Outcome.REGISTRATION_CONFLICT);
        return error(409, "registration_conflict");
    }

    @ExceptionHandler(RegistrationService.InvalidRegistration.class)
    ResponseEntity<ErrorResponse> invalid() {
        events.record(AuthEvents.Outcome.REGISTRATION_INVALID);
        return error(400, "invalid_request");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidFields() {
        return invalid();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> invalidJson() {
        return invalid();
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> unavailable() {
        events.record(AuthEvents.Outcome.REGISTRATION_FAILED);
        return error(503, "registration_unavailable");
    }

    private ResponseEntity<ErrorResponse> error(int status, String code) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(new ErrorResponse(code));
    }

    public record ErrorResponse(String error) {}
}
