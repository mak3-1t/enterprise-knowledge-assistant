package com.eka.agent.api;

/** Executes a bounded, authenticated, read-only assistant turn. */
public interface AgentService {
    Answer answer(String question);

    record Answer(String status, String answer, java.util.List<Citation> citations) {}

    record Citation(
            String id,
            java.util.UUID documentId,
            String filename,
            long generation,
            int chunkOrdinal,
            int sectionOrdinal,
            Integer page) {}
}
