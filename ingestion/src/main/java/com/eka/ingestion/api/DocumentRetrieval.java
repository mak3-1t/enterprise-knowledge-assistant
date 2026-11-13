package com.eka.ingestion.api;

import java.util.List;
import java.util.UUID;

/** Retrieves citation-ready evidence for the authenticated account. */
public interface DocumentRetrieval {
    List<Match> search(float[] query, String model, String modelVersion, int limit);

    record Match(
            UUID documentId,
            String filename,
            long sourceGeneration,
            int chunkOrdinal,
            int sectionOrdinal,
            Integer page,
            int startOffset,
            int endOffset,
            String text,
            double distance,
            boolean lexicalMatch) {
        public Match(
                UUID documentId,
                String filename,
                long sourceGeneration,
                int chunkOrdinal,
                int sectionOrdinal,
                Integer page,
                int startOffset,
                int endOffset,
                String text,
                double distance) {
            this(
                    documentId,
                    filename,
                    sourceGeneration,
                    chunkOrdinal,
                    sectionOrdinal,
                    page,
                    startOffset,
                    endOffset,
                    text,
                    distance,
                    false);
        }

        @Override
        public String toString() {
            return "Match[documentId=" + documentId + ", chunkOrdinal=" + chunkOrdinal + "]";
        }
    }
}
