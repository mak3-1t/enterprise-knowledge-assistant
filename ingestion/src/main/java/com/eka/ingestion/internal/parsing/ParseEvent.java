package com.eka.ingestion.internal.parsing;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

record ParseEvent(
        UUID id, UUID tenant, UUID document, long version, String type, Instant occurredAt) {
    static ParseEvent decode(String key, String payload) {
        try {
            if (payload == null || payload.length() > 8192) {
                return null;
            }
            var json =
                    JsonMapper.builder()
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .build()
                            .readTree(payload);
            var fields =
                    Set.of(
                            "schemaVersion",
                            "eventId",
                            "tenantId",
                            "documentId",
                            "documentVersion",
                            "eventType",
                            "occurredAt");
            if (!json.isObject() || json.size() != fields.size()) {
                return null;
            }
            for (String field : fields) {
                if (!json.hasNonNull(field)) {
                    return null;
                }
            }
            if (!json.get("schemaVersion").isIntegralNumber()
                    || !json.get("schemaVersion").canConvertToInt()
                    || json.get("schemaVersion").asInt() != 1
                    || !json.get("documentVersion").isIntegralNumber()
                    || !json.get("documentVersion").canConvertToLong()
                    || json.get("documentVersion").asLong() < 0) {
                return null;
            }
            UUID id = canonical(json.get("eventId").asText());
            UUID tenant = canonical(json.get("tenantId").asText());
            UUID document = canonical(json.get("documentId").asText());
            if (!(tenant + "/" + document).equals(key)) {
                return null;
            }
            return new ParseEvent(
                    id,
                    tenant,
                    document,
                    json.get("documentVersion").asLong(),
                    json.get("eventType").asText(),
                    Instant.parse(json.get("occurredAt").asText()));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static UUID canonical(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) {
            throw new IllegalArgumentException("Invalid identity");
        }
        return id;
    }
}
