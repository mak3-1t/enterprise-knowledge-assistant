package com.eka.auth.internal.web;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.auth.internal.security.LoginService;
import com.eka.auth.internal.security.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class SessionController {
    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginService.TokenResponse> refresh(
            @Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .body(sessions.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedAccount identity)
                || !(authentication.getDetails() instanceof UUID session)) {
            throw new BadCredentialsException("Authentication required");
        }
        sessions.logout(session, identity.tenantId(), identity.userId());
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ErrorResponse> denied() {
        return error(401, "unauthorized");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid() {
        return error(400, "invalid_request");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable() {
        return invalid();
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> unavailable() {
        return error(503, "authentication_unavailable");
    }

    @ExceptionHandler(JwtException.class)
    ResponseEntity<ErrorResponse> signingUnavailable() {
        return unavailable();
    }

    private ResponseEntity<ErrorResponse> error(int status, String code) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(new ErrorResponse(code));
    }

    public record RefreshRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[REDACTED]";
        }
    }

    public record ErrorResponse(String error) {}
}
