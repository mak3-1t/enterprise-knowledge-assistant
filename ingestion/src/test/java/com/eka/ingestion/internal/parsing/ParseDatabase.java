package com.eka.ingestion.internal.parsing;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.scan.ScanJobRepository;
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

final class ParseDatabase implements AutoCloseable {
    final JdbcClient jdbc;
    final UploadRepository uploads;
    final ScanJobRepository scans;
    final ParseJobRepository jobs;
    private final AnnotationConfigApplicationContext context;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {}

    ParseDatabase(PostgreSQLContainer postgres) {
        var source =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).cleanDisabled(true).load().migrate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(JdbcClient.class, () -> JdbcClient.create(source));
        context.registerBean(
                PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.register(
                Transactions.class,
                UploadRepository.class,
                ScanJobRepository.class,
                ParseJobRepository.class);
        context.refresh();
        jdbc = context.getBean(JdbcClient.class);
        uploads = context.getBean(UploadRepository.class);
        scans = context.getBean(ScanJobRepository.class);
        jobs = context.getBean(ParseJobRepository.class);
    }

    ParseEvent verified(UUID tenant, UUID user) {
        var row =
                uploads.initiate(
                        tenant,
                        user,
                        "request-" + UUID.randomUUID(),
                        "file.txt",
                        DocumentFormat.TXT,
                        5,
                        null);
        uploads.complete(
                tenant,
                user,
                row.id(),
                5,
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        scans.dispatch();
        scans.clean(scans.claim().orElseThrow());
        return event(tenant, row.id());
    }

    ParseEvent event(UUID tenant, UUID document) {
        return jdbc.sql(
                        "SELECT * FROM ingestion.document_outbox WHERE tenant_id = ? AND document_id = ? AND event_type = 'CONTENT_VERIFIED' ORDER BY document_version DESC LIMIT 1")
                .param(tenant)
                .param(document)
                .query(
                        (rs, ignored) ->
                                new ParseEvent(
                                        rs.getObject("id", UUID.class),
                                        tenant,
                                        document,
                                        rs.getLong("document_version"),
                                        "CONTENT_VERIFIED",
                                        rs.getTimestamp("created_at").toInstant()))
                .single();
    }

    void accept(ParseEvent event) {
        jobs.accept(
                "test-events",
                0,
                1,
                event.tenant() + "/" + event.document(),
                ParseIntakeTest.payload(
                        event.id(),
                        event.tenant(),
                        event.document(),
                        event.version(),
                        event.occurredAt()));
    }

    void reset() {
        for (String table :
                java.util.List.of(
                        "document_source_sections",
                        "document_parse_jobs",
                        "document_intake_rejections",
                        "document_scan_jobs",
                        "document_outbox",
                        "documents",
                        "upload_quotas")) {
            jdbc.sql("DELETE FROM ingestion." + table).update();
        }
    }

    @Override
    public void close() {
        context.close();
    }
}
