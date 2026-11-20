package com.alber.backend.book;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.JSoupParser;
import org.apache.tika.sax.BodyContentHandler;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Reads the book's EPUB into chapters of plain text, in spine (reading) order, each titled from the EPUB 2 table of
 * contents ({@code toc.ncx}). A missing or unreadable file means no book, not a crash.
 *
 * <p>Each chapter file is read with Tika's lenient JSoup HTML parser: the book's chapters are plain HTML (unclosed
 * {@code <link>} tags), which strict XML parsers, and so Spring AI's {@code TikaDocumentReader}, reject. The package
 * files (container, OPF, NCX) are XML, read with the JDK parser with DTDs and external entities off, since the EPUB is
 * input from outside the app.
 */
@Slf4j
public final class BookLoader {

    private BookLoader() {}

    /** The book's chapters in reading order, or empty when the file is missing, unreadable or has no text. */
    public static Optional<List<BookChapter>> loadChapters(Path path) {
        Path file = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            log.warn(
                    "No book at {}. POST /chat answers 503 until the EPUB is there (see README → The book, or set"
                            + " app.book.path).",
                    file);
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            List<BookChapter> chapters = readChapters(zip);
            if (chapters.isEmpty()) {
                log.warn("The book at {} has no text. POST /chat answers 503.", file);
                return Optional.empty();
            }
            log.info(
                    "Loaded book {}: {} chapters, {} characters",
                    file.getFileName(),
                    chapters.size(),
                    chapters.stream().mapToInt(c -> c.text().length()).sum());
            return Optional.of(List.copyOf(chapters));
        } catch (IOException | SAXException | TikaException | ParserConfigurationException | RuntimeException e) {
            log.warn("Could not read the book at {}: {}. POST /chat answers 503.", file, e.toString());
            return Optional.empty();
        }
    }

    private static List<BookChapter> readChapters(ZipFile zip)
            throws IOException, SAXException, TikaException, ParserConfigurationException {
        Document container = xml(zip, "META-INF/container.xml");
        String opfPath = firstElement(container, "rootfile").getAttribute("full-path");
        Document opf = xml(zip, opfPath);

        Map<String, Element> manifest = new HashMap<>();
        for (Element item : elements(opf, "item")) {
            manifest.put(item.getAttribute("id"), item);
        }
        Element spine = firstElement(opf, "spine");
        Map<String, String> titles = readToc(zip, opfPath, manifest, spine);

        List<BookChapter> chapters = new ArrayList<>();
        String title = null;
        for (Element itemref : elements(opf, "itemref")) {
            Element item = manifest.get(itemref.getAttribute("idref"));
            if (item == null) {
                continue;
            }
            String path = resolve(opfPath, item.getAttribute("href"));
            // A file the table of contents doesn't list continues the chapter before it.
            title = titles.getOrDefault(path, title);
            String text = clean(htmlText(zip, path));
            if (!text.isEmpty()) {
                chapters.add(new BookChapter(title, text));
            }
        }
        return chapters;
    }

    /** Chapter titles by file path, from the NCX; empty when the EPUB has none. The first entry per file wins. */
    private static Map<String, String> readToc(
            ZipFile zip, String opfPath, Map<String, Element> manifest, Element spine)
            throws IOException, SAXException, ParserConfigurationException {
        Element ncxItem = manifest.get(spine.getAttribute("toc"));
        if (ncxItem == null) {
            ncxItem = manifest.values().stream()
                    .filter(item -> "application/x-dtbncx+xml".equals(item.getAttribute("media-type")))
                    .findFirst()
                    .orElse(null);
        }
        if (ncxItem == null) {
            return Map.of();
        }
        String ncxPath = resolve(opfPath, ncxItem.getAttribute("href"));
        if (zip.getEntry(ncxPath) == null) {
            return Map.of();
        }
        Map<String, String> titles = new HashMap<>();
        for (Element navPoint : elements(xml(zip, ncxPath), "navPoint")) {
            Element label = firstChild(navPoint, "navLabel");
            Element content = firstChild(navPoint, "content");
            if (label == null || content == null) {
                continue;
            }
            String title = label.getTextContent().strip().replaceAll("\\s+", " ");
            if (!title.isEmpty()) {
                titles.putIfAbsent(resolve(ncxPath, content.getAttribute("src")), title);
            }
        }
        return titles;
    }

    private static String htmlText(ZipFile zip, String path) throws IOException, SAXException, TikaException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            log.debug("The book lists {} but doesn't contain it.", path);
            return "";
        }
        BodyContentHandler text = new BodyContentHandler(-1);
        try (InputStream in = TikaInputStream.get(zip.getInputStream(entry))) {
            new JSoupParser().parse(in, text, new Metadata(), new ParseContext());
        }
        return text.toString();
    }

    /**
     * The zip path of {@code href} (relative, maybe percent-encoded, maybe with a {@code #fragment}) as seen from the
     * file at {@code from}.
     */
    static String resolve(String from, String href) {
        String withoutFragment = href.split("#", 2)[0];
        String decoded = URLDecoder.decode(withoutFragment.replace("+", "%2B"), StandardCharsets.UTF_8);
        int slash = from.lastIndexOf('/');
        String dir = slash < 0 ? "" : from.substring(0, slash + 1);
        try {
            String path =
                    new URI(null, null, "/" + dir + decoded, null).normalize().getPath();
            return path.substring(1);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Bad path in the book: " + href, e);
        }
    }

    private static Document xml(ZipFile zip, String path)
            throws IOException, SAXException, ParserConfigurationException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            throw new IOException("The book has no " + path);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return safeXmlParser().parse(in);
        }
    }

    /** Namespace-aware, with no DTD loading, no external entities and no XInclude (no XXE, no network access). */
    private static DocumentBuilder safeXmlParser() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder;
    }

    private static List<Element> elements(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        List<Element> elements = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            elements.add((Element) nodes.item(i));
        }
        return elements;
    }

    private static Element firstElement(Document document, String localName) throws IOException {
        List<Element> found = elements(document, localName);
        if (found.isEmpty()) {
            throw new IOException("The book's package files have no <" + localName + ">");
        }
        return found.getFirst();
    }

    private static @Nullable Element firstChild(Element parent, String localName) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    /**
     * Drops indentation and trailing spaces, lines that are only an image file name (Tika lists the illustrations),
     * and runs of blank lines, so the prompt carries no wasted tokens.
     */
    static String clean(String text) {
        return text.replace("\r\n", "\n")
                .replace(' ', ' ')
                .replaceAll("(?m)^[ \\t]+|[ \\t]+$", "")
                .replaceAll("(?mi)^[\\w./-]+\\.(?:jpe?g|png|gif|svg)$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
