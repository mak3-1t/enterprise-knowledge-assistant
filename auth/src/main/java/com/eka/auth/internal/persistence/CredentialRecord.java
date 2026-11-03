package com.eka.auth.internal.persistence;

/** Only for the future auth service; never serialize or log password hashes. */
public record CredentialRecord(AccountSummary account, String passwordHash, boolean tenantActive) {
    @Override
    public String toString() {
        return "CredentialRecord[REDACTED]";
    }
}
