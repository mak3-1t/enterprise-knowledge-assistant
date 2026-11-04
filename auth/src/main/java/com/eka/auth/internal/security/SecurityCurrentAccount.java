package com.eka.auth.internal.security;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.auth.api.CurrentAccount;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityCurrentAccount implements CurrentAccount {
    @Override
    public AuthenticatedAccount require() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.isAuthenticated()
                && auth.getPrincipal() instanceof AuthenticatedAccount account) {
            return account;
        }
        throw new AuthenticationCredentialsNotFoundException("Authentication required");
    }
}
