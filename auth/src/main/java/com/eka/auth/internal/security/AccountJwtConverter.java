package com.eka.auth.internal.security;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.auth.internal.persistence.AccountRepository;
import com.eka.auth.internal.persistence.SessionRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class AccountJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final Clock clock;

    public AccountJwtConverter(
            AccountRepository accounts, SessionRepository sessions, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID tenant = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        UUID user = UUID.fromString(jwt.getSubject());
        var account =
                accounts.findActiveIdentity(tenant, user)
                        .orElseThrow(() -> new OAuth2AuthenticationException("invalid_token"));
        UUID session = UUID.fromString(jwt.getClaimAsString("sid"));
        if (!sessions.isActive(session, tenant, user, clock.instant())) {
            throw new OAuth2AuthenticationException("invalid_token");
        }
        var principal = new AuthenticatedAccount(user, tenant, account.role().name());
        var authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + account.role().name())));
        authentication.setDetails(session);
        return authentication;
    }
}
