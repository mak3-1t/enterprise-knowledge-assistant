package com.eka.auth.internal.security;

import com.eka.auth.internal.persistence.SessionRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public class SessionTokenIssuer {
    private final JwtEncoder encoder;
    private final AuthSettings settings;

    public SessionTokenIssuer(JwtEncoder encoder, AuthSettings settings) {
        this.encoder = encoder;
        this.settings = settings;
    }

    public LoginService.TokenResponse issue(SessionRecord session, String refresh, Instant now) {
        Instant expiry =
                now.plusSeconds(900).isBefore(session.expiresAt())
                        ? now.plusSeconds(900)
                        : session.expiresAt();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(settings.issuer())
                        .audience(List.of(settings.audience()))
                        .subject(session.userId().toString())
                        .claim("tenant_id", session.tenantId().toString())
                        .claim("sid", session.id().toString())
                        .issuedAt(now)
                        .notBefore(now)
                        .expiresAt(expiry)
                        .build();
        String access =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(SignatureAlgorithm.RS256)
                                                .keyId("eka-local")
                                                .build(),
                                        claims))
                        .getTokenValue();
        return new LoginService.TokenResponse(
                access,
                "Bearer",
                Duration.between(now, expiry).toSeconds(),
                refresh,
                Duration.between(now, session.expiresAt()).toSeconds());
    }
}
