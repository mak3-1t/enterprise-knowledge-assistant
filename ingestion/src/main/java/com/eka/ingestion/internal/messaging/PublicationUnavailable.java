package com.eka.ingestion.internal.messaging;

public class PublicationUnavailable extends RuntimeException {
    public PublicationUnavailable() {
        super("BROKER_UNAVAILABLE");
    }
}
