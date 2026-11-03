package com.eka.auth.internal.persistence;

import java.time.Instant;
import java.util.UUID;

/** Internal persistence result, not an HTTP DTO or cross-module API. */
public record AccountSummary(
        UUID id,
        UUID tenantId,
        String email,
        AccountRole role,
        boolean active,
        Instant createdAt) {}
