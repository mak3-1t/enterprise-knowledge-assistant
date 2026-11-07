package com.eka.ingestion.internal.chunking;

import com.eka.ingestion.internal.parsing.ParseJobRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Private ingestion entry point for a specific tenant and verified source generation. */
@Service
public class DocumentChunkService {
    private final ParseJobRepository sources;
    private final DocumentChunker chunker;

    public DocumentChunkService(ParseJobRepository sources, ChunkSettings settings) {
        this.sources = sources;
        this.chunker = new DocumentChunker(settings);
    }

    public List<SourceChunk> load(UUID tenant, UUID document, long sourceGeneration) {
        return chunker.split(
                tenant,
                document,
                sourceGeneration,
                sources.loadCurrentGeneration(tenant, document, sourceGeneration));
    }
}
