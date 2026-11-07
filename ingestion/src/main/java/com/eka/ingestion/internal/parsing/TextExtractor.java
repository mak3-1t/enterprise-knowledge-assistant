package com.eka.ingestion.internal.parsing;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import java.nio.file.Path;

public interface TextExtractor {
    ParsedDocument extract(Path file, DocumentFormat format);
}
