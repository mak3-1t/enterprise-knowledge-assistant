package com.eka.ingestion.internal.parsing;

import java.util.List;

/**
 * Internal extracted source data. Never log/serialize through public document metadata endpoints.
 */
public record ParsedDocument(String parserVersion, List<Section> sections) {
    public ParsedDocument {
        sections = List.copyOf(sections);
    }

    public record Section(Integer page, String text) {
        @Override
        public String toString() {
            return "Section[page=" + page + ", characters=" + text.length() + "]";
        }
    }

    @Override
    public String toString() {
        return "ParsedDocument[sections=" + sections.size() + "]";
    }
}
