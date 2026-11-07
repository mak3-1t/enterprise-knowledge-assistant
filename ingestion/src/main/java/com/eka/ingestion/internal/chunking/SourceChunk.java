package com.eka.ingestion.internal.chunking;

import java.util.UUID;

/** Internal source slice. Offsets are UTF-16 indices within one parsed section. */
public record SourceChunk(
        UUID tenantId,
        UUID documentId,
        long sourceGeneration,
        int ordinal,
        int sectionOrdinal,
        Integer page,
        int startOffset,
        int endOffset,
        String text) {
    @Override
    public String toString() {
        return "SourceChunk[ordinal="
                + ordinal
                + ", sectionOrdinal="
                + sectionOrdinal
                + ", startOffset="
                + startOffset
                + ", endOffset="
                + endOffset
                + "]";
    }
}
