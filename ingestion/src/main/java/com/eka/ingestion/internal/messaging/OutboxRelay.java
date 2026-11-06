package com.eka.ingestion.internal.messaging;

import com.eka.ingestion.internal.service.UploadEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eka.ingestion.kafka.enabled", havingValue = "true")
public class OutboxRelay {
    private final OutboxRelayRepository deliveries;
    private final OutboxPublisher publisher;
    private final UploadEvents events;

    public OutboxRelay(
            OutboxRelayRepository deliveries, OutboxPublisher publisher, UploadEvents events) {
        this.deliveries = deliveries;
        this.publisher = publisher;
        this.events = events;
    }

    @Scheduled(
            fixedDelayString = "${eka.ingestion.kafka.poll-delay-ms:500}",
            scheduler = "outboxRelayScheduler")
    public void tick() {
        for (int i = 0; i < 10; i++) {
            var candidate = deliveries.claim();
            if (candidate.isEmpty()) {
                return;
            }
            var delivery = candidate.get();
            try {
                var receipt = publisher.publish(delivery);
                boolean recorded = deliveries.acknowledge(delivery, receipt);
                events.record(
                        recorded
                                ? UploadEvents.Outcome.OUTBOX_PUBLISHED
                                : UploadEvents.Outcome.OUTBOX_STALE,
                        delivery.documentId());
            } catch (RuntimeException unavailable) {
                if (deliveries.defer(delivery)) {
                    events.record(UploadEvents.Outcome.OUTBOX_DEFERRED, delivery.documentId());
                }
                return;
            }
        }
    }
}
