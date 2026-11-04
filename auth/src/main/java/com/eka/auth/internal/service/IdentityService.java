package com.eka.auth.internal.service;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.auth.api.CurrentAccount;
import com.eka.auth.internal.persistence.AccountRepository;
import com.eka.auth.internal.persistence.AccountSummary;
import com.eka.auth.internal.security.AuthEvents;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
    private final CurrentAccount current;
    private final AccountRepository accounts;
    private final AuthEvents events;

    public IdentityService(CurrentAccount current, AccountRepository accounts, AuthEvents events) {
        this.current = current;
        this.accounts = accounts;
        this.events = events;
    }

    public MeView me() {
        var identity = current.require();
        var account =
                accounts.findActiveIdentity(identity.tenantId(), identity.userId())
                        .orElseThrow(() -> new BadCredentialsException("Authentication required"));
        events.record(AuthEvents.Outcome.ME_READ);
        return new MeView(account.id(), account.tenantId(), account.email(), account.role().name());
    }

    public MemberPage members(int page, int size) {
        var identity = requireAdmin();
        long offset = (long) page * size;
        if (page < 0 || size < 1 || size > 100 || offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        var items =
                accounts.findMembers(identity.tenantId(), size, (int) offset).stream()
                        .map(IdentityService::view)
                        .toList();
        events.record(AuthEvents.Outcome.MEMBERS_LISTED);
        return new MemberPage(items, page, size);
    }

    public MemberView member(UUID id) {
        var identity = requireAdmin();
        var found = accounts.findById(identity.tenantId(), id);
        if (found.isEmpty()) {
            events.record(AuthEvents.Outcome.MEMBER_NOT_FOUND);
            throw new MemberNotFound();
        }
        events.record(AuthEvents.Outcome.MEMBER_READ);
        return view(found.get());
    }

    private AuthenticatedAccount requireAdmin() {
        var identity = current.require();
        if (!"ADMIN".equals(identity.role())) {
            throw new AccessDeniedException("Insufficient role");
        }
        return identity;
    }

    private static MemberView view(AccountSummary account) {
        return new MemberView(
                account.id(),
                account.tenantId(),
                account.email(),
                account.role().name(),
                account.active(),
                account.createdAt());
    }

    public record MeView(UUID userId, UUID tenantId, String email, String role) {}

    public record MemberView(
            UUID id, UUID tenantId, String email, String role, boolean active, Instant createdAt) {}

    public record MemberPage(List<MemberView> items, int page, int size) {}

    public static class MemberNotFound extends RuntimeException {}
}
