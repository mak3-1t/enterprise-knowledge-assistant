package com.eka.ingestion.internal.storage;

import com.eka.ingestion.internal.persistence.UploadRecord;
import com.eka.ingestion.internal.persistence.VerifiedUpload;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Map;

public interface UploadStorage {
    UploadForm authorize(UploadRecord row);

    VerifiedUpload seal(UploadRecord row);

    InputStream open(UploadRecord row);

    void remove(UploadRecord row);

    record UploadForm(URI url, Map<String, String> fields, Instant expiresAt) {
        @Override
        public String toString() {
            return "UploadForm[redacted]";
        }
    }
}
