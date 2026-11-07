package com.eka.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TikaTextExtractorTest {
    @TempDir Path directory;

    @Test
    void extractsStrictUtf8AndNormalizesUnicodeWithoutFabricatedPageNumbers() throws Exception {
        Path file =
                Files.writeString(
                        directory.resolve("text.txt"),
                        "Tiếng Việt\r\nThông tin công ty",
                        StandardCharsets.UTF_8);
        var result = TikaTextExtractor.extract(file, DocumentFormat.TXT);
        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().getFirst().page()).isNull();
        assertThat(result.sections().getFirst().text())
                .contains("Tiếng Việt", "Thông tin công ty")
                .doesNotContain("\r");
        assertThat(result.toString()).doesNotContain("Tiếng Việt");
        assertThat(result.sections().getFirst().toString()).doesNotContain("Tiếng Việt");
    }

    @Test
    void preservesMarkdownAsUnrenderedSourceIncludingHeadingsAndCode() throws Exception {
        Path file =
                Files.writeString(
                        directory.resolve("source.md"),
                        "# Hướng dẫn\n<script>example()</script>\n```java\nint value = 1;\n```",
                        StandardCharsets.UTF_8);
        var text = TikaTextExtractor.extract(file, DocumentFormat.MD).sections().getFirst().text();
        assertThat(text).contains("# Hướng dẫn", "<script>example()</script>", "int value = 1;");
    }

    @Test
    void mapsPdfTextToRealPagesAndDoesNotUseOcrForBlankPages() throws Exception {
        Path file = directory.resolve("pages.pdf");
        try (var document = new PDDocument()) {
            for (String text : new String[] {"First page", "Second page"}) {
                var page = new PDPage();
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(30, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(file.toFile());
        }
        var result = TikaTextExtractor.extract(file, DocumentFormat.PDF);
        assertThat(result.sections())
                .extracting(ParsedDocument.Section::page)
                .containsExactly(1, 2);
        assertThat(result.sections().get(0).text())
                .contains("First page")
                .doesNotContain("Second page");
        assertThat(result.sections().get(1).text()).contains("Second page");
    }

    @Test
    void extractsDocxParagraphsWithoutInventingLayoutPageNumbers() throws Exception {
        Path file = directory.resolve("source.docx");
        try (var document = new XWPFDocument();
                var output = Files.newOutputStream(file)) {
            document.createParagraph().createRun().setText("Quy định công ty");
            document.createParagraph().createRun().setText("Nội dung thứ hai");
            document.write(output);
        }
        var result = TikaTextExtractor.extract(file, DocumentFormat.DOCX);
        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().getFirst().page()).isNull();
        assertThat(result.sections().getFirst().text())
                .contains("Quy định công ty", "Nội dung thứ hai");
    }

    @Test
    void blankPdfAndWhitespaceTextHaveExplicitEmptyExtractionOutcome() throws Exception {
        Path pdf = directory.resolve("scan-like.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        assertThatThrownBy(() -> TikaTextExtractor.extract(pdf, DocumentFormat.PDF))
                .hasMessage("EXTRACT_EMPTY");
        Path text = Files.writeString(directory.resolve("empty.txt"), " \n\t ");
        assertThatThrownBy(() -> TikaTextExtractor.extract(text, DocumentFormat.TXT))
                .hasMessage("EXTRACT_EMPTY");
    }

    @Test
    void textLimitRejectsRatherThanSilentlyPublishingTruncatedOutput() throws Exception {
        Path text = Files.writeString(directory.resolve("large.txt"), "abcd ".repeat(400001));
        assertThatThrownBy(() -> TikaTextExtractor.extract(text, DocumentFormat.TXT))
                .hasMessage("EXTRACT_LIMIT");
    }

    @Test
    void boundedChildExtractsTextAndReturnsSafeEmptyOutcome() throws Exception {
        var extractor = new ProcessTextExtractor();
        Path file =
                Files.writeString(
                        directory.resolve("child.txt"),
                        "Dữ liệu từ tiến trình con",
                        StandardCharsets.UTF_8);
        assertThat(extractor.extract(file, DocumentFormat.TXT).sections().getFirst().text())
                .contains("Dữ liệu từ tiến trình con");
        Path blank = Files.writeString(directory.resolve("child-empty.txt"), "  \n ");
        assertThatThrownBy(() -> extractor.extract(blank, DocumentFormat.TXT))
                .hasMessage("EXTRACT_EMPTY")
                .hasNoCause();
    }

    @Test
    void corruptOrDisguisedInputIsRejectedBeforeTextExtraction() throws Exception {
        Path file = Files.writeString(directory.resolve("fake.pdf"), "MZ disguised input");
        assertThatThrownBy(() -> TikaTextExtractor.extract(file, DocumentFormat.PDF))
                .hasMessage("TYPE_MISMATCH");
    }
}
