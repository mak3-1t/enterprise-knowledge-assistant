package com.eka.auth.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.auth.api.CurrentAccount;
import com.eka.auth.internal.persistence.AccountRepository;
import com.eka.auth.internal.persistence.AccountRole;
import com.eka.auth.internal.persistence.AccountSummary;
import com.eka.auth.internal.security.AuthEvents;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

class IdentityServiceTest {
    private final CurrentAccount current = mock(CurrentAccount.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AuthEvents events = mock(AuthEvents.class);
    private final IdentityService service = new IdentityService(current, accounts, events);
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void memberRoleCannotReadMembersEvenWhenCalledWithoutHttpFilter() {
        identity("MEMBER");
        assertThatThrownBy(() -> service.members(0, 20)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.member(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(accounts);
    }

    @Test
    void invalidAndOverflowingPaginationNeverQueriesDatabase() {
        identity("ADMIN");
        for (int[] params :
                List.of(
                        new int[] {-1, 20},
                        new int[] {0, 0},
                        new int[] {0, 101},
                        new int[] {Integer.MAX_VALUE, 100})) {
            assertThatThrownBy(() -> service.members(params[0], params[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(accounts);
    }

    @Test
    void listingPassesVerifiedTenantAndDatabaseOffset() {
        identity("ADMIN");
        when(accounts.findMembers(tenant, 5, 10)).thenReturn(List.of());
        assertThat(service.members(2, 5))
                .isEqualTo(new IdentityService.MemberPage(List.of(), 2, 5));
        verify(accounts).findMembers(tenant, 5, 10);
    }

    @Test
    void unavailableIdentityIsRejectedByMe() {
        identity("MEMBER");
        when(accounts.findActiveIdentity(tenant, user)).thenReturn(Optional.empty());
        assertThatThrownBy(service::me).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void meUsesCurrentAccountRecordAndOnlySafeFields() {
        identity("MEMBER");
        when(accounts.findActiveIdentity(tenant, user)).thenReturn(Optional.of(summary()));
        assertThat(service.me())
                .isEqualTo(
                        new IdentityService.MeView(user, tenant, "member@example.test", "MEMBER"));
    }

    @Test
    void missingOrForeignMemberHasSameNotFoundOutcome() {
        identity("ADMIN");
        UUID target = UUID.randomUUID();
        when(accounts.findById(tenant, target)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.member(target))
                .isInstanceOf(IdentityService.MemberNotFound.class);
        verify(accounts).findById(tenant, target);
        verify(events).record(AuthEvents.Outcome.MEMBER_NOT_FOUND);
    }

    @Test
    void memberDetailsMapSafeSummary() {
        identity("ADMIN");
        when(accounts.findById(tenant, user)).thenReturn(Optional.of(summary()));
        var response = service.member(user);
        assertThat(response.id()).isEqualTo(user);
        assertThat(response.tenantId()).isEqualTo(tenant);
        assertThat(response.email()).isEqualTo("member@example.test");
    }

    private void identity(String role) {
        when(current.require()).thenReturn(new AuthenticatedAccount(user, tenant, role));
    }

    private AccountSummary summary() {
        return new AccountSummary(
                user,
                tenant,
                "member@example.test",
                AccountRole.MEMBER,
                true,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
