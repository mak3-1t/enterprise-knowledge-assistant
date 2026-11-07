package com.eka.ingestion.internal.parsing;

enum ParseIntakeOutcome {
    ACCEPTED,
    INVALID_EVENT,
    UNVERIFIED_EVENT,
    STALE_EVENT,
    INTAKE_UNAVAILABLE
}
