package com.eka.ingestion.internal.persistence;

public enum DocumentFormat {
    PDF("application/pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    TXT("text/plain"),
    MD("text/markdown");

    private final String mediaType;

    DocumentFormat(String mediaType) {
        this.mediaType = mediaType;
    }

    public String mediaType() {
        return mediaType;
    }
}
