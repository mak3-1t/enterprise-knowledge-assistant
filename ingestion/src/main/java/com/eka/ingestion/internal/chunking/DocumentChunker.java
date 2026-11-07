package com.eka.ingestion.internal.chunking;

import com.eka.ingestion.internal.parsing.ParsedDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class DocumentChunker {
    private final ChunkSettings settings;
    private final ChunkBoundary boundary;

    DocumentChunker(ChunkSettings settings) {
        this.settings = settings;
        this.boundary =
                settings.strategy() == ChunkSettings.Strategy.FIXED
                        ? (text, minimum, maximum) -> maximum
                        : ChunkBoundary::recursive;
    }

    List<SourceChunk> split(
            UUID tenant, UUID document, long generation, List<ParsedDocument.Section> sections) {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(document);
        if (generation < 0) {
            throw new IllegalArgumentException("Invalid source generation");
        }
        List<SourceChunk> result = new ArrayList<>();
        for (int sectionOrdinal = 0; sectionOrdinal < sections.size(); sectionOrdinal++) {
            var section = sections.get(sectionOrdinal);
            String text = section.text();
            int start = 0;
            int remaining = text.codePointCount(0, text.length());
            while (remaining > 0) {
                int end = text.length();
                if (remaining > settings.size()) {
                    int minimumSize = (settings.size() + settings.overlap() + 1) / 2;
                    int minimum = text.offsetByCodePoints(start, minimumSize);
                    int maximum = text.offsetByCodePoints(start, settings.size());
                    end = boundary.end(text, minimum, maximum);
                }
                result.add(
                        new SourceChunk(
                                tenant,
                                document,
                                generation,
                                result.size(),
                                sectionOrdinal,
                                section.page(),
                                start,
                                end,
                                text.substring(start, end)));
                if (end == text.length()) {
                    break;
                }
                int next = text.offsetByCodePoints(end, -settings.overlap());
                remaining -= text.codePointCount(start, next);
                start = next;
            }
        }
        return List.copyOf(result);
    }
}
