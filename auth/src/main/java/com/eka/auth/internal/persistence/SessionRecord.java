package com.eka.auth.internal.persistence;

import java.time.Instant;
import java.util.UUID;

public record SessionRecord(UUID id, UUID tenantId, UUID userId, Instant expiresAt) {}
