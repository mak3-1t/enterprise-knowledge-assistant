package com.eka.auth.internal.security;

import com.eka.auth.internal.persistence.AccountRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class LoginService {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final AuthSettings settings;
    private final Clock clock;
    private final String dummyHash;
    private final AuthEvents events;

    public LoginService(
            AccountRepository accounts,
            PasswordEncoder passwords,
            JwtEncoder encoder,
            AuthSettings settings,
            Clock clock,
            AuthEvents events) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.encoder = encoder;
        this.settings = settings;
        this.clock = clock;
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
        var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var claims =
                JwtClaimsSet.builder()
                        .issuer(settings.issuer())
                        .audience(List.of(settings.audience()))
                        .subject(account.id().toString())
                        .claim("tenant_id", account.tenantId().toString())
                        .issuedAt(issued)
                        .notBefore(issued)
                        .expiresAt(issued.plusSeconds(900))
                        .build();
        var token =
                encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).keyId("eka-local").build(),
                                claims));
        events.record(AuthEvents.Outcome.LOGIN_SUCCEEDED);
        return new TokenResponse(token.getTokenValue(), "Bearer", 900);
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
        @Override
        public String toString() {
            return "TokenResponse[REDACTED]";
        }
    }
}
