package com.deskbooks.backend.planning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

final class JournalImportParser {
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String DOCX_TEXT_ERROR = "could not read docx document text";
    private static final String PAGE_BREAK_TYPE = "page";

    JournalImportPreviewResponse preview(JournalImportPreviewRequest body) {
        Path path = Path.of(body.path()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file not found");
        }
        List<JournalImportDraftResponse> drafts = drafts(path, documentPages(path));
        if (drafts.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no journal text found");
        }
        return new JournalImportPreviewResponse(path.getFileName().toString(), drafts);
    }

    private List<JournalImportDraftResponse> drafts(Path path, List<String> pages) {
        String baseTitle = stripExtension(path.getFileName().toString());
        List<JournalImportDraftResponse> drafts = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String page = pages.get(i).trim();
            if (!page.isBlank()) {
                drafts.add(new JournalImportDraftResponse(
                        i + 1,
                        baseTitle + " page " + (i + 1),
                        page));
            }
        }
        return drafts;
    }

    private List<String> documentPages(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (isTextImport(filename)) {
                return splitTextPages(Files.readString(path));
            }
            if (filename.endsWith(".docx")) {
                return docxPages(path);
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read journal import text");
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "supported journal imports: .txt, .md, .markdown, .docx");
    }

    private boolean isTextImport(String filename) {
        return filename.endsWith(".txt") || filename.endsWith(".md") || filename.endsWith(".markdown");
    }

    private List<String> splitTextPages(String text) {
        if (text.contains("\f")) {
            return List.of(text.split("\\f"));
        }
        List<String> pages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            String marker = line.trim().toLowerCase(Locale.ROOT);
            if (isPageMarker(marker)) {
                pages.add(String.join("\n", current));
                current = new ArrayList<>();
            } else {
                current.add(line);
            }
        }
        pages.add(String.join("\n", current));
        return nonBlankPages(pages);
    }

    private boolean isPageMarker(String marker) {
        return marker.equals("--- page ---") || marker.equals("=== page ===");
    }

    private List<String> docxPages(Path path) throws IOException {
        try (ZipFile docx = new ZipFile(path.toFile())) {
            var entry = docx.getEntry("word/document.xml");
            if (entry == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, DOCX_TEXT_ERROR);
            }
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder().parse(docx.getInputStream(entry));
            return pagesFromParagraphs(document.getElementsByTagNameNS(WORD_NS, "p"));
        } catch (IOException | ParserConfigurationException | SAXException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, DOCX_TEXT_ERROR);
        }
    }

    private List<String> pagesFromParagraphs(NodeList paragraphs) {
        List<String> pages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            Element paragraph = (Element) paragraphs.item(i);
            addParagraphText(paragraph, current);
            if (hasPageBreak(paragraph) && !current.isEmpty()) {
                pages.add(String.join("\n\n", current));
                current = new ArrayList<>();
            }
        }
        pages.add(String.join("\n\n", current));
        return nonBlankPages(pages);
    }

    private void addParagraphText(Element paragraph, List<String> current) {
        String text = paragraphText(paragraph).trim();
        if (!text.isBlank()) {
            current.add(text);
        }
    }

    private boolean hasPageBreak(Element paragraph) {
        if (paragraph.getElementsByTagNameNS(WORD_NS, "lastRenderedPageBreak").getLength() > 0) {
            return true;
        }
        NodeList breaks = paragraph.getElementsByTagNameNS(WORD_NS, "br");
        for (int j = 0; j < breaks.getLength(); j++) {
            Element br = (Element) breaks.item(j);
            if (PAGE_BREAK_TYPE.equals(br.getAttributeNS(WORD_NS, "type"))) {
                return true;
            }
        }
        return false;
    }

    private String paragraphText(Element paragraph) {
        NodeList textNodes = paragraph.getElementsByTagNameNS(WORD_NS, "t");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < textNodes.getLength(); i++) {
            builder.append(textNodes.item(i).getTextContent());
        }
        return builder.toString();
    }

    private List<String> nonBlankPages(List<String> pages) {
        return pages.stream()
                .map(String::trim)
                .filter(page -> !page.isBlank())
                .toList();
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }
}
