package com.eka.ingestion.internal.scan;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/** Child-process entry point: no Spring startup, credentials, text output or input execution. */
public final class FileInspectionMain {
    private FileInspectionMain() {}

    public static void main(String[] args) {
        int result = 70;
        try {
            if (args.length == 2) {
                inspect(Path.of(args[1]), DocumentFormat.valueOf(args[0]));
                result = 0;
            }
        } catch (InspectionRejected rejected) {
            result = exitCode(rejected.getMessage());
        } catch (Throwable failure) {
            // Child failure is retryable, never a clean verdict; no parser diagnostics leak.
            result = 70;
        }
        System.exit(result);
    }

    public static void inspect(Path file, DocumentFormat format) {
        try {
            long size = Files.size(file);
            if (size < 1 || size > 20971520) {
                throw new InspectionRejected("FILE_TOO_LARGE");
            }
            byte[] head;
            try (var input = Files.newInputStream(file)) {
                head = input.readNBytes(8);
            }
            if (format == DocumentFormat.PDF) {
                if (!starts(head, "%PDF-")) {
                    throw new InspectionRejected("TYPE_MISMATCH");
                }
                pdf(file);
            } else if (format == DocumentFormat.DOCX) {
                if (head.length < 4
                        || head[0] != 'P'
                        || head[1] != 'K'
                        || head[2] != 3
                        || head[3] != 4) {
                    throw new InspectionRejected("TYPE_MISMATCH");
                }
                docx(file);
            } else {
                if (starts(head, "MZ")
                        || starts(head, "PK")
                        || starts(head, "%PDF-")
                        || (head.length >= 4
                                && head[0] == 127
                                && starts(
                                        java.util.Arrays.copyOfRange(head, 1, head.length),
                                        "ELF"))) {
                    throw new InspectionRejected("TYPE_MISMATCH");
                }
                text(file);
            }
        } catch (InspectionRejected rejected) {
            throw rejected;
        } catch (IOException | XMLStreamException corrupt) {
            throw new InspectionRejected("CORRUPTED_FILE");
        }
    }

    private static void text(Path file) throws IOException {
        try {
            var decoded =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(Files.readAllBytes(file)));
            while (decoded.hasRemaining()) {
                char value = decoded.get();
                if (Character.isISOControl(value)
                        && value != '\r'
                        && value != '\n'
                        && value != '\t'
                        && value != '\f') {
                    throw new InspectionRejected("TYPE_MISMATCH");
                }
            }
        } catch (CharacterCodingException invalid) {
            throw new InspectionRejected("TYPE_MISMATCH");
        }
    }

    private static void pdf(Path file) throws IOException {
        try (var document = Loader.loadPDF(file.toFile())) {
            if (document.isEncrypted()) {
                throw new InspectionRejected("PASSWORD_PROTECTED");
            }
            if (document.getNumberOfPages() < 1) {
                throw new InspectionRejected("CORRUPTED_FILE");
            }
            var pending = new ArrayDeque<COSBase>();
            var visited = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
            pending.add(document.getDocumentCatalog().getCOSObject());
            while (!pending.isEmpty()) {
                COSBase next = pending.removeFirst();
                if (!visited.add(next)) {
                    continue;
                }
                if (visited.size() > 100000) {
                    throw new InspectionRejected("CORRUPTED_FILE");
                }
                if (next instanceof COSObject reference) {
                    if (reference.getObject() != null) {
                        pending.add(reference.getObject());
                    }
                } else if (next instanceof COSDictionary dictionary) {
                    for (var entry : dictionary.entrySet()) {
                        if (Set.of(
                                        "JS",
                                        "JavaScript",
                                        "AA",
                                        "OpenAction",
                                        "EmbeddedFiles",
                                        "RichMedia")
                                .contains(entry.getKey().getName())) {
                            throw new InspectionRejected("ACTIVE_CONTENT_UNSUPPORTED");
                        }
                        if (entry.getKey().equals(COSName.S)
                                && entry.getValue() instanceof COSName action
                                && Set.of(
                                                "JavaScript",
                                                "Launch",
                                                "SubmitForm",
                                                "ImportData",
                                                "GoToR",
                                                "Rendition")
                                        .contains(action.getName())) {
                            throw new InspectionRejected("ACTIVE_CONTENT_UNSUPPORTED");
                        }
                        if (entry.getValue() != null) {
                            pending.add(entry.getValue());
                        }
                    }
                } else if (next instanceof COSArray array) {
                    for (COSBase value : array) {
                        if (value != null) {
                            pending.add(value);
                        }
                    }
                }
            }
        } catch (InvalidPasswordException encrypted) {
            throw new InspectionRejected("PASSWORD_PROTECTED");
        }
    }

    private static void docx(Path file) throws IOException, XMLStreamException {
        try (var archive = new ZipFile(file.toFile())) {
            Set<String> names = new HashSet<>();
            boolean contentTypes = false;
            boolean rootRelationship = false;
            boolean document = false;
            long total = 0;
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = Normalizer.normalize(entry.getName(), Normalizer.Form.NFKC);
                if (names.size() >= 2048
                        || !names.add(name)
                        || name.startsWith("/")
                        || name.contains("\\")
                        || name.contains(":")
                        || java.util.Arrays.asList(name.split("/")).contains("..")) {
                    throw new InspectionRejected("CORRUPTED_FILE");
                }
                if (name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) {
                    throw new InspectionRejected("MACRO_DETECTED");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                long limit = Math.min(16777216L, 67108864L - total);
                byte[] bytes;
                try (var input = archive.getInputStream(entry)) {
                    bytes = input.readNBytes((int) limit + 1);
                }
                total += bytes.length;
                var crc = new java.util.zip.CRC32();
                crc.update(bytes);
                if (bytes.length > limit
                        || bytes.length != entry.getSize()
                        || crc.getValue() != entry.getCrc()
                        || (entry.getCompressedSize() >= 0
                                && bytes.length > entry.getCompressedSize() * 100L + 1048576L)) {
                    throw new InspectionRejected("CORRUPTED_FILE");
                }
                if (name.endsWith(".xml") || name.endsWith(".rels")) {
                    String root = xml(bytes, name);
                    if (name.equals("[Content_Types].xml")) {
                        contentTypes = root.equals("Types");
                    } else if (name.equals("_rels/.rels")) {
                        rootRelationship = root.equals("Relationships");
                    } else if (name.equals("word/document.xml")) {
                        document = root.equals("document");
                    }
                }
            }
            if (!contentTypes || !rootRelationship || !document) {
                throw new InspectionRejected("CORRUPTED_FILE");
            }
        }
    }

    private static String xml(byte[] bytes, String part) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver(
                (publicId, systemId, base, namespace) -> {
                    throw new XMLStreamException("External references disabled");
                });
        var reader = factory.createXMLStreamReader(new java.io.ByteArrayInputStream(bytes));
        String root = "";
        boolean requiredType = false;
        boolean requiredRelationship = false;
        int depth = 0;
        int events = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (++events > 1000000
                        || event == XMLStreamConstants.DTD
                        || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw new InspectionRejected("CORRUPTED_FILE");
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 128) {
                        throw new InspectionRejected("CORRUPTED_FILE");
                    }
                    if (root.isEmpty()) {
                        root = reader.getLocalName();
                        String namespace = reader.getNamespaceURI();
                        if ((part.equals("[Content_Types].xml")
                                        && !"http://schemas.openxmlformats.org/package/2006/content-types"
                                                .equals(namespace))
                                || (part.equals("_rels/.rels")
                                        && !"http://schemas.openxmlformats.org/package/2006/relationships"
                                                .equals(namespace))) {
                            throw new InspectionRejected("CORRUPTED_FILE");
                        }
                        if (part.equals("word/document.xml")
                                && !Set.of(
                                                "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                                                "http://purl.oclc.org/ooxml/wordprocessingml/main")
                                        .contains(namespace == null ? "" : namespace)) {
                            throw new InspectionRejected("CORRUPTED_FILE");
                        }
                    }
                    String type = reader.getAttributeValue(null, "ContentType");
                    if (type != null && type.toLowerCase(Locale.ROOT).contains("macroenabled")) {
                        throw new InspectionRejected("MACRO_DETECTED");
                    }
                    if ("External".equalsIgnoreCase(reader.getAttributeValue(null, "TargetMode"))) {
                        throw new InspectionRejected("ACTIVE_CONTENT_UNSUPPORTED");
                    }
                    if ("/word/document.xml".equals(reader.getAttributeValue(null, "PartName"))
                            && "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                                    .equals(type)) {
                        requiredType = true;
                    }
                    String relationship = reader.getAttributeValue(null, "Type");
                    String target = reader.getAttributeValue(null, "Target");
                    if (relationship != null
                            && relationship.endsWith("/officeDocument")
                            && ("word/document.xml".equals(target)
                                    || "/word/document.xml".equals(target))) {
                        requiredRelationship = true;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                }
            }
            if ((part.equals("[Content_Types].xml") && !requiredType)
                    || (part.equals("_rels/.rels") && !requiredRelationship)) {
                throw new InspectionRejected("CORRUPTED_FILE");
            }
            return root;
        } finally {
            reader.close();
        }
    }

    private static boolean starts(byte[] bytes, String prefix) {
        byte[] expected = prefix.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (bytes[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    static int exitCode(String code) {
        return switch (code) {
            case "TYPE_MISMATCH" -> 10;
            case "CORRUPTED_FILE" -> 11;
            case "PASSWORD_PROTECTED" -> 12;
            case "MACRO_DETECTED" -> 13;
            case "ACTIVE_CONTENT_UNSUPPORTED" -> 14;
            case "FILE_TOO_LARGE" -> 15;
            default -> 70;
        };
    }
}
