package com.eka.auth.internal.security;

import com.eka.auth.internal.persistence.SessionRecord;
import com.eka.auth.internal.persistence.SessionRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private final SessionRepository sessions;
    private final SessionTokenIssuer issuer;
    private final Clock clock;
    private final AuthEvents events;

    public SessionService(
            SessionRepository sessions, SessionTokenIssuer issuer, Clock clock, AuthEvents events) {
        this.sessions = sessions;
        this.issuer = issuer;
        this.clock = clock;
        this.events = events;
    }

    public LoginService.TokenResponse open(UUID tenant, UUID user) {
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var session = new SessionRecord(UUID.randomUUID(), tenant, user, now.plusSeconds(604800));
        String refresh = RefreshTokens.create();
        return sessions.create(
                        session,
                        RefreshTokens.hash(refresh).orElseThrow(),
                        now,
                        stored -> issuer.issue(stored, refresh, now))
                .orElseThrow(SessionService::denied);
    }

    public LoginService.TokenResponse refresh(String token) {
        var oldHash = RefreshTokens.hash(token);
        if (oldHash.isEmpty()) {
            events.record(AuthEvents.Outcome.REFRESH_DENIED);
            throw denied();
        }
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String next = RefreshTokens.create();
        // Repository must finish its transaction (including replay revocation) before throwing 401.
        var result =
                sessions.rotate(
                        oldHash.get(),
                        RefreshTokens.hash(next).orElseThrow(),
                        now,
                        stored -> issuer.issue(stored, next, now));
        if (result.isEmpty()) {
            events.record(AuthEvents.Outcome.REFRESH_DENIED);
            throw denied();
        }
        events.record(AuthEvents.Outcome.REFRESH_SUCCEEDED);
        return result.get();
    }

    public void logout(UUID session, UUID tenant, UUID user) {
        if (!sessions.revoke(session, tenant, user, clock.instant())) {
            throw denied();
        }
        events.record(AuthEvents.Outcome.LOGOUT_SUCCEEDED);
    }

    private static BadCredentialsException denied() {
        return new BadCredentialsException("Authentication required");
    }
}
