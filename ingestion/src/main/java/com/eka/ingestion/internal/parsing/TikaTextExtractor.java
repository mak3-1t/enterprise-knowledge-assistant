package com.eka.ingestion.internal.parsing;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import com.eka.ingestion.internal.scan.FileInspectionMain;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.txt.TXTParser;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Invoke inside the bounded child process, never on an HTTP request thread. */
public final class TikaTextExtractor {
    public static final int MAX_CHARACTERS = 2000000;

    private TikaTextExtractor() {}

    public static ParsedDocument extract(Path file, DocumentFormat format) {
        // Reapply structural gates; this is not a replacement for a successful ClamAV scan.
        FileInspectionMain.inspect(file, format);
        var context = new ParseContext();
        context.set(
                EmbeddedDocumentExtractor.class,
                new EmbeddedDocumentExtractor() {
                    @Override
                    public boolean shouldParseEmbedded(Metadata metadata) {
                        return false;
                    }

                    @Override
                    public void parseEmbedded(
                            InputStream stream,
                            ContentHandler handler,
                            Metadata metadata,
                            boolean outputHtml) {
                        // Only the approved primary document is a source; no recursive attachment
                        // parsing.
                    }
                });
        Parser parser;
        if (format == DocumentFormat.PDF) {
            var pdf = new PDFParserConfig();
            pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
            pdf.setExtractInlineImages(false);
            pdf.setExtractAcroFormContent(false);
            pdf.setExtractAnnotationText(false);
            context.set(PDFParserConfig.class, pdf);
            parser = new PDFParser();
        } else if (format == DocumentFormat.DOCX) {
            parser = new OOXMLParser();
        } else {
            parser = new TXTParser();
        }
        var handler = new SourceHandler(format == DocumentFormat.PDF);
        var metadata = new Metadata();
        metadata.set("Content-Type", format.mediaType());
        metadata.set("Content-Encoding", "UTF-8");
        try (var input = Files.newInputStream(file)) {
            parser.parse(input, handler, metadata, context);
            var sections = handler.finish();
            if (sections.isEmpty()) {
                throw new TextExtractionFailure("EXTRACT_EMPTY");
            }
            return new ParsedDocument("apache-tika:3.3.3", sections);
        } catch (SAXException failure) {
            throw new TextExtractionFailure(
                    handler.limitReached ? "EXTRACT_LIMIT" : "CORRUPTED_FILE");
        } catch (IOException | TikaException failure) {
            throw new TextExtractionFailure("CORRUPTED_FILE");
        }
    }

    private static final class SourceHandler extends DefaultHandler {
        private final boolean pdf;
        private final List<ParsedDocument.Section> sections = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private int total;
        private int depth;
        private int pageDepth;
        private int page;
        private boolean body;
        private boolean limitReached;

        SourceHandler(boolean pdf) {
            this.pdf = pdf;
        }

        @Override
        public void startElement(String uri, String local, String qualified, Attributes attributes)
                throws SAXException {
            depth++;
            String name = local.isEmpty() ? qualified : local;
            if (name.equals("body")) {
                body = true;
            }
            if (pdf && name.equals("div") && "page".equals(attributes.getValue("class"))) {
                flush();
                if (++page > 2000) {
                    limitReached = true;
                    throw new SAXException("Extraction limit");
                }
                pageDepth = depth;
            }
        }

        @Override
        public void characters(char[] chars, int start, int length) throws SAXException {
            if (body && (!pdf || pageDepth > 0)) {
                total += length;
                if (total > MAX_CHARACTERS) {
                    limitReached = true;
                    throw new SAXException("Extraction limit");
                }
                text.append(chars, start, length);
            }
        }

        @Override
        public void endElement(String uri, String local, String qualified) throws SAXException {
            String name = local.isEmpty() ? qualified : local;
            if (body
                    && (!pdf || pageDepth > 0)
                    && java.util.Set.of("p", "h1", "h2", "h3", "h4", "li", "tr", "br")
                            .contains(name)) {
                if (++total > MAX_CHARACTERS) {
                    limitReached = true;
                    throw new SAXException("Extraction limit");
                }
                text.append('\n');
            }
            if (pdf && pageDepth == depth) {
                flush();
                pageDepth = 0;
            }
            if (name.equals("body")) {
                body = false;
            }
            depth--;
        }

        private void flush() {
            String normalized =
                    Normalizer.normalize(
                                    text.toString().replace("\r\n", "\n").replace('\r', '\n'),
                                    Normalizer.Form.NFC)
                            .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "")
                            .strip();
            if (!normalized.isBlank()) {
                sections.add(new ParsedDocument.Section(pdf ? page : null, normalized));
            }
            text.setLength(0);
        }

        List<ParsedDocument.Section> finish() {
            flush();
            return List.copyOf(sections);
        }
    }
}
