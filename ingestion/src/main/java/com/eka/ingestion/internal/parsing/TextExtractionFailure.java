package com.eka.ingestion.internal.parsing;

public class TextExtractionFailure extends RuntimeException {
    public TextExtractionFailure(String code) {
        super(code);
    }
}
