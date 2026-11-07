package com.eka.ingestion.internal.chunking;

import com.eka.ingestion.internal.parsing.ParsedDocument.Section;
import java.text.BreakIterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ChunkComparison {
    public static void main(String[] args) {
        var documents =
                List.of(
                        ("# Đăng ký tài khoản\n\nQuản trị viên xác nhận yêu cầu. Nhân viên nhận email rồi thiết lập mật khẩu.\n\n")
                                .repeat(80),
                        ("Operations handbook. Restart the service only after the health check fails.\n\nUse the incident log to record the reason and the outcome.\n")
                                .repeat(70),
                        ("## API\n\n```json\n{\"status\":\"ready\",\"page\":42}\n```\n日本語の検索結果を確認する。 🚀\n\n")
                                .repeat(85));
        UUID tenant = new UUID(0, 1);
        int original = documents.stream().mapToInt(s -> s.codePointCount(0, s.length())).sum();
        for (var strategy : ChunkSettings.Strategy.values()) {
            for (int size : List.of(256, 512, 1024)) {
                var splitter = new DocumentChunker(new ChunkSettings(strategy, size, size / 8));
                int count = 0;
                int characters = 0;
                int words = 0;
                for (int i = 0; i < documents.size(); i++) {
                    var chunks =
                            splitter.split(
                                    tenant,
                                    new UUID(0, i + 2),
                                    1,
                                    List.of(new Section(null, documents.get(i))));
                    count += chunks.size();
                    for (var chunk : chunks) {
                        characters += chunk.text().codePointCount(0, chunk.text().length());
                        var iterator = BreakIterator.getWordInstance(Locale.ROOT);
                        iterator.setText(chunk.text());
                        for (int start = iterator.first(), end = iterator.next();
                                end != BreakIterator.DONE;
                                start = end, end = iterator.next()) {
                            if (Character.isLetterOrDigit(chunk.text().codePointAt(start))) {
                                words++;
                            }
                        }
                    }
                }
                System.out.printf(
                        Locale.ROOT,
                        "%s %d %d %.2f %.2f%n",
                        strategy,
                        size,
                        count,
                        100.0 * (characters - original) / original,
                        (double) words / count);
            }
        }
        System.out.println("Corpus code points: " + original);
    }
}
