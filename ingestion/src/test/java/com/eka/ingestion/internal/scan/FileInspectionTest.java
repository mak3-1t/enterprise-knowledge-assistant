package com.eka.ingestion.internal.scan;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileInspectionTest {
    @TempDir Path directory;

    @Test
    void rejectsExecutableRenamedPdfAndCorruptedPdfButAcceptsRealPdf() throws Exception {
        Path fake = bytes("fake.pdf", "MZ-not-a-PDF".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> inspect(fake, DocumentFormat.PDF)).hasMessage("TYPE_MISMATCH");
        Path corrupt = bytes("corrupt.pdf", "%PDF-1.7\nbroken".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> inspect(corrupt, DocumentFormat.PDF)).hasMessage("CORRUPTED_FILE");
        Path valid = directory.resolve("real.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(valid.toFile());
        }
        assertThatCode(() -> inspect(valid, DocumentFormat.PDF)).doesNotThrowAnyException();
    }

    @Test
    void rejectsPasswordProtectedAndActiveContentPdf() throws Exception {
        Path protectedFile = directory.resolve("protected.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.protect(
                    new StandardProtectionPolicy(
                            "owner-password", "user-password", new AccessPermission()));
            pdf.save(protectedFile.toFile());
        }
        assertThatThrownBy(() -> inspect(protectedFile, DocumentFormat.PDF))
                .hasMessage("PASSWORD_PROTECTED");
        Path active = directory.resolve("active.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            var action = new COSDictionary();
            action.setItem(COSName.S, COSName.getPDFName("JavaScript"));
            action.setString(COSName.getPDFName("JS"), "app.alert('test')");
            pdf.getDocumentCatalog()
                    .getCOSObject()
                    .setItem(COSName.getPDFName("OpenAction"), action);
            pdf.save(active.toFile());
        }
        assertThatThrownBy(() -> inspect(active, DocumentFormat.PDF))
                .hasMessage("ACTIVE_CONTENT_UNSUPPORTED");
    }

    @Test
    void acceptsWellFormedDocxAndRejectsMissingOrInvalidPackageParts() throws Exception {
        assertThatCode(() -> inspect(archive(parts()), DocumentFormat.DOCX))
                .doesNotThrowAnyException();
        var missing = parts();
        missing.remove("word/document.xml");
        assertThatThrownBy(() -> inspect(archive(missing), DocumentFormat.DOCX))
                .hasMessage("CORRUPTED_FILE");
        var invalid = parts();
        invalid.put("word/document.xml", "<not-closed>");
        assertThatThrownBy(() -> inspect(archive(invalid), DocumentFormat.DOCX))
                .hasMessage("CORRUPTED_FILE");
    }

    @Test
    void rejectsMacroPartAndMacroEnabledContentType() throws Exception {
        var macro = parts();
        macro.put("word/vbaProject.bin", "test-only-placeholder");
        assertThatThrownBy(() -> inspect(archive(macro), DocumentFormat.DOCX))
                .hasMessage("MACRO_DETECTED");
        var renamed = parts();
        renamed.put(
                "[Content_Types].xml",
                renamed.get("[Content_Types].xml")
                        .replace(
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                                "application/vnd.ms-word.document.macroEnabled.main+xml"));
        assertThatThrownBy(() -> inspect(archive(renamed), DocumentFormat.DOCX))
                .hasMessage("MACRO_DETECTED");
    }

    @Test
    void rejectsZipTraversalBombAndExcessiveEntries() throws Exception {
        var traversal = parts();
        traversal.put("../escape.xml", "test");
        assertThatThrownBy(() -> inspect(archive(traversal), DocumentFormat.DOCX))
                .hasMessage("CORRUPTED_FILE");
        var compressed = parts();
        compressed.put("word/oversized-data.bin", "0".repeat(3000000));
        assertThatThrownBy(() -> inspect(archive(compressed), DocumentFormat.DOCX))
                .hasMessage("CORRUPTED_FILE");
        var entries = parts();
        for (int i = 0; i < 2050; i++) {
            entries.put("word/test-" + i + ".bin", "test");
        }
        assertThatThrownBy(() -> inspect(archive(entries), DocumentFormat.DOCX))
                .hasMessage("CORRUPTED_FILE");
    }

    @Test
    void rejectsDoctypeEntitiesAndExternalRelationshipsWithoutFetchingThem() throws Exception {
        var doctype = parts();
        doctype.put(
                "word/document.xml",
                "<!DOCTYPE document [<!ENTITY x SYSTEM 'file:///nonexistent-test-file'>]>"
                        + "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>&x;</w:document>");
        assertThatThrownBy(() -> inspect(archive(doctype), DocumentFormat.DOCX))
                .hasMessage("CORRUPTED_FILE");
        var external = parts();
        external.put(
                "word/_rels/document.xml.rels",
                "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                        + "<Relationship Id='external' Type='test' Target='https://example.invalid/test' TargetMode='External'/></Relationships>");
        assertThatThrownBy(() -> inspect(archive(external), DocumentFormat.DOCX))
                .hasMessage("ACTIVE_CONTENT_UNSUPPORTED");
    }

    @Test
    void textRequiresStrictUtf8AndRejectsBinaryControlsWhileMarkdownRemainsUnrenderedText()
            throws Exception {
        assertThatThrownBy(
                        () ->
                                inspect(
                                        bytes("invalid.txt", new byte[] {(byte) 0xc3, 0x28}),
                                        DocumentFormat.TXT))
                .hasMessage("TYPE_MISMATCH");
        assertThatThrownBy(
                        () ->
                                inspect(
                                        bytes("binary.txt", new byte[] {65, 0, 66}),
                                        DocumentFormat.TXT))
                .hasMessage("TYPE_MISMATCH");
        Path markdown =
                bytes(
                        "safe.md",
                        "# Tiếng Việt\n<script>alert('test')</script>"
                                .getBytes(StandardCharsets.UTF_8));
        assertThatCode(() -> inspect(markdown, DocumentFormat.MD)).doesNotThrowAnyException();
        // Validation never renders HTML or modifies the original. Preview sanitization is separate.
    }

    @Test
    void childProcessLoadsDependenciesAndReturnsSafeVerdicts() throws Exception {
        var inspector = new ProcessFileInspector(30);
        assertThatCode(
                        () ->
                                inspector.inspect(
                                        bytes(
                                                "real.txt",
                                                "xin chào".getBytes(StandardCharsets.UTF_8)),
                                        DocumentFormat.TXT))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                inspector.inspect(
                                        bytes("fake-child.pdf", new byte[] {77, 90}),
                                        DocumentFormat.PDF))
                .hasMessage("TYPE_MISMATCH");
        Path pdf = directory.resolve("child.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        assertThatCode(() -> inspector.inspect(pdf, DocumentFormat.PDF)).doesNotThrowAnyException();
    }

    private void inspect(Path file, DocumentFormat format) {
        FileInspectionMain.inspect(file, format);
    }

    private Path bytes(String name, byte[] bytes) throws IOException {
        return Files.write(directory.resolve(name), bytes);
    }

    private Path archive(Map<String, String> entries) throws IOException {
        Path file = Files.createTempFile(directory, "docx-", ".docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private static Map<String, String> parts() {
        var result = new LinkedHashMap<String, String>();
        result.put(
                "[Content_Types].xml",
                "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'>"
                        + "<Override PartName='/word/document.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'/></Types>");
        result.put(
                "_rels/.rels",
                "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                        + "<Relationship Id='main' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' Target='word/document.xml'/></Relationships>");
        result.put(
                "word/document.xml",
                "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>Test</w:t></w:r></w:p></w:body></w:document>");
        return result;
    }
}
