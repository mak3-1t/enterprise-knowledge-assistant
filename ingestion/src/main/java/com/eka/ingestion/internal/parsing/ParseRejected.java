package com.eka.ingestion.internal.parsing;

final class ParseRejected extends RuntimeException {
    private final ParseIntakeOutcome reason;

    ParseRejected(ParseIntakeOutcome reason) {
        super(reason.name());
        this.reason = reason;
    }

    ParseIntakeOutcome reason() {
        return reason;
    }
}
