package com.deskbooks.backend.planning;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

final class JournalDocxPageReader {
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String DOCX_TEXT_ERROR = "could not read docx document text";
    private static final String PAGE_BREAK_TYPE = "page";

    List<String> pages(Path path) throws IOException {
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
        return JournalPages.nonBlank(pages);
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
}
