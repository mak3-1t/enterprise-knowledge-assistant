package com.eka.ingestion.internal.messaging;

public interface OutboxPublisher {
    OutboxDelivery.Receipt publish(OutboxDelivery delivery);
}
