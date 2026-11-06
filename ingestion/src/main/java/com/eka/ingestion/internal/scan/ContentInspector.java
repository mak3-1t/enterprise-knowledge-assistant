package com.eka.ingestion.internal.scan;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import java.nio.file.Path;

public interface ContentInspector {
    void inspect(Path file, DocumentFormat format);
}
