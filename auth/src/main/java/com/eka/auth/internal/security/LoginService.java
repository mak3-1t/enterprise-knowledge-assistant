package com.eka.auth.internal.security;

import com.eka.auth.internal.persistence.AccountRepository;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class LoginService {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final SessionService sessions;
    private final String dummyHash;
    private final AuthEvents events;

    public LoginService(
            AccountRepository accounts,
            PasswordEncoder passwords,
            SessionService sessions,
            AuthEvents events) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.sessions = sessions;
        this.events = events;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public TokenResponse login(String tenantSlug, String email, String password) {
        var found = accounts.findCredentials(tenantSlug, email);
        boolean matches =
                passwords.matches(
                        password, found.map(record -> record.passwordHash()).orElse(dummyHash));
        if (found.isEmpty()
                || !matches
                || !found.get().account().active()
                || !found.get().tenantActive()) {
            events.record(AuthEvents.Outcome.LOGIN_DENIED);
            throw new BadCredentialsException("Invalid credentials");
        }
        var account = found.get().account();
        var result = sessions.open(account.tenantId(), account.id());
        events.record(AuthEvents.Outcome.LOGIN_SUCCEEDED);
        return result;
    }

    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn) {
        @Override
        public String toString() {
            return "TokenResponse[REDACTED]";
        }
    }
}
