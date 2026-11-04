package com.eka.auth.api;

import java.util.UUID;

/** Verified per-request identity for module APIs. No persistence or JWT types leak out. */
public record AuthenticatedAccount(UUID userId, UUID tenantId, String role) {}
