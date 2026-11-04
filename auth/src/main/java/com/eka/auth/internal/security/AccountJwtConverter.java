package com.eka.auth.internal.security;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.auth.internal.persistence.AccountRepository;
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

    public AccountJwtConverter(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID tenant = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        UUID user = UUID.fromString(jwt.getSubject());
        var account =
                accounts.findActiveIdentity(tenant, user)
                        .orElseThrow(() -> new OAuth2AuthenticationException("invalid_token"));
        var principal = new AuthenticatedAccount(user, tenant, account.role().name());
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + account.role().name())));
    }
}
