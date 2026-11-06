package com.eka.ingestion.internal.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eka.ingestion.internal.service.UploadEvents;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {
    private final OutboxRelayRepository repository = mock(OutboxRelayRepository.class);
    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private final UploadEvents events = mock(UploadEvents.class);
    private final OutboxRelay relay = new OutboxRelay(repository, publisher, events);
    private final OutboxDelivery delivery = delivery();

    @Test
    void brokerFailureNeverAcknowledgesDatabaseAndLeavesDurableRetry() {
        when(repository.claim()).thenReturn(Optional.of(delivery));
        when(publisher.publish(delivery)).thenThrow(new PublicationUnavailable());
        when(repository.defer(delivery)).thenReturn(true);
        relay.tick();
        verify(repository).defer(delivery);
        verify(repository, never()).acknowledge(any(), any());
        verify(events).record(UploadEvents.Outcome.OUTBOX_DEFERRED, delivery.documentId());
    }

    @Test
    void acknowledgementIsRecordedOnlyAfterBrokerReturnsAReceipt() {
        var receipt = new OutboxDelivery.Receipt(1, 42);
        when(repository.claim()).thenReturn(Optional.of(delivery), Optional.empty());
        when(publisher.publish(delivery)).thenReturn(receipt);
        when(repository.acknowledge(delivery, receipt)).thenReturn(true);
        relay.tick();
        var sequence = org.mockito.Mockito.inOrder(publisher, repository);
        sequence.verify(repository).claim();
        sequence.verify(publisher).publish(delivery);
        sequence.verify(repository).acknowledge(delivery, receipt);
        verify(events).record(UploadEvents.Outcome.OUTBOX_PUBLISHED, delivery.documentId());
    }

    @Test
    void lostDatabaseAcknowledgementKeepsEventRetryableAfterBrokerAppend() {
        var receipt = new OutboxDelivery.Receipt(0, 3);
        when(repository.claim()).thenReturn(Optional.of(delivery));
        when(publisher.publish(delivery)).thenReturn(receipt);
        when(repository.acknowledge(delivery, receipt))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(repository.defer(delivery)).thenReturn(true);
        relay.tick();
        verify(repository).defer(delivery);
        verify(events, never())
                .record(UploadEvents.Outcome.OUTBOX_PUBLISHED, delivery.documentId());
    }

    @Test
    void staleLeaseCannotEmitSuccessfulDeliveryMetric() {
        var receipt = new OutboxDelivery.Receipt(0, 3);
        when(repository.claim()).thenReturn(Optional.of(delivery), Optional.empty());
        when(publisher.publish(delivery)).thenReturn(receipt);
        when(repository.acknowledge(delivery, receipt)).thenReturn(false);
        relay.tick();
        verify(events).record(UploadEvents.Outcome.OUTBOX_STALE, delivery.documentId());
        verify(events, never())
                .record(UploadEvents.Outcome.OUTBOX_PUBLISHED, delivery.documentId());
    }

    static OutboxDelivery delivery() {
        return new OutboxDelivery(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "UPLOAD_COMPLETED",
                Instant.parse("2026-11-06T00:00:00Z"),
                1,
                UUID.randomUUID(),
                1);
    }
}
