package com.eka.ingestion.internal.parsing;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import com.eka.ingestion.internal.scan.InspectionRejected;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** Credential-free child entry point. Outputs source text only to the parent's private file. */
public final class TextExtractionMain {
    private TextExtractionMain() {}

    public static void main(String[] args) {
        int result = 70;
        try {
            if (args.length == 3) {
                var document =
                        TikaTextExtractor.extract(
                                Path.of(args[1]), DocumentFormat.valueOf(args[0]));
                byte[] json = JsonMapper.builder().build().writeValueAsBytes(document);
                if (json.length > 8388608) {
                    throw new TextExtractionFailure("EXTRACT_LIMIT");
                }
                Files.write(Path.of(args[2]), json);
                result = 0;
            }
        } catch (TextExtractionFailure failure) {
            result =
                    java.util.Map.of("EXTRACT_EMPTY", 20, "EXTRACT_LIMIT", 21, "CORRUPTED_FILE", 22)
                            .getOrDefault(failure.getMessage(), 70);
        } catch (InspectionRejected failure) {
            result =
                    java.util.Map.of(
                                    "TYPE_MISMATCH",
                                    10,
                                    "CORRUPTED_FILE",
                                    22,
                                    "PASSWORD_PROTECTED",
                                    12,
                                    "MACRO_DETECTED",
                                    13,
                                    "ACTIVE_CONTENT_UNSUPPORTED",
                                    14,
                                    "FILE_TOO_LARGE",
                                    15)
                            .getOrDefault(failure.getMessage(), 70);
        } catch (Throwable failure) {
            result = 70;
        }
        System.exit(result);
    }
}
