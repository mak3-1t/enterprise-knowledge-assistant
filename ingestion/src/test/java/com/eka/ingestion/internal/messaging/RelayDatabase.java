package com.eka.ingestion.internal.messaging;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import com.eka.ingestion.internal.persistence.UploadRepository;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;

final class RelayDatabase implements AutoCloseable {
    private final AnnotationConfigApplicationContext context;
    final JdbcClient jdbc;
    final UploadRepository uploads;
    final OutboxRelayRepository relay;
    final PlatformTransactionManager transactions;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {}

    RelayDatabase(PostgreSQLContainer postgres) {
        var source =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).cleanDisabled(true).load().migrate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(JdbcClient.class, () -> JdbcClient.create(source));
        context.registerBean(
                PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.register(Transactions.class, UploadRepository.class, OutboxRelayRepository.class);
        context.refresh();
        jdbc = context.getBean(JdbcClient.class);
        uploads = context.getBean(UploadRepository.class);
        relay = context.getBean(OutboxRelayRepository.class);
        transactions = context.getBean(PlatformTransactionManager.class);
    }

    UUID pending(UUID tenant, UUID user) {
        return uploads.initiate(
                        tenant,
                        user,
                        "relay-" + UUID.randomUUID(),
                        "file.txt",
                        DocumentFormat.TXT,
                        5,
                        null)
                .id();
    }

    UUID complete(UUID tenant, UUID user) {
        UUID id = pending(tenant, user);
        uploads.complete(tenant, user, id, 5, "a".repeat(64));
        return id;
    }

    void reset() {
        jdbc.sql("DELETE FROM ingestion.document_scan_jobs").update();
        jdbc.sql("DELETE FROM ingestion.document_outbox").update();
        jdbc.sql("DELETE FROM ingestion.documents").update();
        jdbc.sql("DELETE FROM ingestion.upload_quotas").update();
    }

    @Override
    public void close() {
        context.close();
    }
}
