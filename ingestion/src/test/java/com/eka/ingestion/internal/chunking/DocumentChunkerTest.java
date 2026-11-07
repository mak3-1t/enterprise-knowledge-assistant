package com.eka.ingestion.internal.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eka.ingestion.internal.parsing.ParsedDocument.Section;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentChunkerTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();

    @Test
    void fixedWindowsCoverUnicodeWithoutSplittingSurrogates() {
        String text = "Tiếng Việt 日本語 🚀\n".repeat(500);
        var chunks =
                new DocumentChunker(new ChunkSettings(ChunkSettings.Strategy.FIXED, 64, 16))
                        .split(tenant, document, 7, List.of(new Section(null, text)));
        assertThat(chunks.size()).isGreaterThan(10);
        int previousEnd = 0;
        StringBuilder reconstructed = new StringBuilder();
        for (var chunk : chunks) {
            assertThat(chunk.tenantId()).isEqualTo(tenant);
            assertThat(chunk.documentId()).isEqualTo(document);
            assertThat(chunk.sourceGeneration()).isEqualTo(7);
            assertThat(chunk.page()).isNull();
            assertThat(chunk.text())
                    .isEqualTo(text.substring(chunk.startOffset(), chunk.endOffset()));
            assertThat(chunk.text().codePointCount(0, chunk.text().length()))
                    .isLessThanOrEqualTo(64);
            assertThat(Character.isLowSurrogate(chunk.text().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)))
                    .isFalse();
            assertThat(chunk.startOffset()).isLessThanOrEqualTo(previousEnd);
            assertThat(chunk.endOffset()).isGreaterThan(previousEnd);
            if (previousEnd > 0) {
                assertThat(text.codePointCount(chunk.startOffset(), previousEnd)).isEqualTo(16);
            }
            reconstructed.append(text, previousEnd, chunk.endOffset());
            previousEnd = chunk.endOffset();
        }
        assertThat(reconstructed.toString()).isEqualTo(text);
    }

    @Test
    void recursivePrefersParagraphsThenFallsBackAndRetainsSectionAndPage() {
        var chunker =
                new DocumentChunker(new ChunkSettings(ChunkSettings.Strategy.RECURSIVE, 64, 16));
        String first = "a".repeat(48) + "\n\n" + "b".repeat(90);
        var sections = List.of(new Section(1, first), new Section(2, ""), new Section(3, "tail"));
        var chunks = chunker.split(tenant, document, 3, sections);
        assertThat(chunks).isEqualTo(chunker.split(tenant, document, 3, sections));
        assertThat(chunks).extracting(SourceChunk::ordinal).containsExactly(0, 1, 2, 3);
        assertThat(chunks).extracting(SourceChunk::sectionOrdinal).containsExactly(0, 0, 0, 2);
        assertThat(chunks).extracting(SourceChunk::page).containsExactly(1, 1, 1, 3);
        assertThat(chunks.get(0).endOffset()).isEqualTo(50);
        assertThat(chunks.get(1).startOffset()).isEqualTo(34);
        assertThat(chunks.get(1).endOffset()).isEqualTo(98);
        assertThat(chunks.get(2).endOffset()).isEqualTo(first.length());
        assertThat(chunker.split(tenant, document, 3, List.of())).isEmpty();
    }

    @Test
    void rejectsSettingsThatCouldProduceInvalidWindows() {
        assertThatThrownBy(() -> new ChunkSettings(null, 512, 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkSettings(ChunkSettings.Strategy.FIXED, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkSettings(ChunkSettings.Strategy.FIXED, 4097, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkSettings(ChunkSettings.Strategy.RECURSIVE, 64, 33))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
