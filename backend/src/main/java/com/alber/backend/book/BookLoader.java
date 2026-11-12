package com.alber.backend.book;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ParserDecorator;
import org.apache.tika.parser.epub.EpubParser;
import org.apache.tika.parser.html.JSoupParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Reads the book's EPUB to plain text with Apache Tika, chapters in spine order. A missing or unreadable file means no
 * book, not a crash.
 *
 * <p>Not Spring AI's {@code TikaDocumentReader}: its auto-detected EPUB parser reads chapters as strict XML and fails
 * on EPUBs whose chapters are plain HTML (unclosed {@code <link>} tags, as in the book this app is built for). Here the
 * EPUB parser reads chapters with Tika's lenient JSoup HTML parser instead.
 */
@Slf4j
public final class BookLoader {

    private BookLoader() {}

    /** The book's cleaned text, or empty when the file is missing, unreadable or has no text. */
    public static Optional<String> load(Path path) {
        Path file = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            log.warn(
                    "No book at {}. POST /chat answers 503 until the EPUB is there (see README → The book, or set"
                            + " app.book.path).",
                    file);
            return Optional.empty();
        }
        try {
            String cleaned = clean(extractText(file));
            if (cleaned.isEmpty()) {
                log.warn("The book at {} has no text. POST /chat answers 503.", file);
                return Optional.empty();
            }
            log.info("Loaded book {}: {} characters", file.getFileName(), cleaned.length());
            return Optional.of(cleaned);
        } catch (IOException | SAXException | TikaException | RuntimeException e) {
            log.warn("Could not read the book at {}: {}. POST /chat answers 503.", file, e.toString());
            return Optional.empty();
        }
    }

    private static String extractText(Path file) throws IOException, SAXException, TikaException {
        EpubParser epub = new EpubParser();
        epub.setContentParser(new LenientHtmlParser());
        BodyContentHandler text = new BodyContentHandler(-1);
        try (InputStream in = TikaInputStream.get(file)) {
            epub.parse(in, text, new Metadata(), new ParseContext());
        }
        return text.toString();
    }

    /**
     * Drops indentation and trailing spaces, lines that are only an image file name (Tika lists the illustrations),
     * and runs of blank lines, so the prompt carries no wasted tokens.
     */
    static String clean(String text) {
        return text.replace("\r\n", "\n")
                .replace(' ', ' ')
                .replaceAll("(?m)^[ \\t]+|[ \\t]+$", "")
                .replaceAll("(?mi)^[\\w./-]+\\.(?:jpe?g|png|gif|svg)$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /** JSoup needs a stream that supports mark/reset; the EPUB parser hands over a plain zip entry stream. */
    private static final class LenientHtmlParser extends ParserDecorator {

        LenientHtmlParser() {
            super(new JSoupParser());
        }

        @Override
        public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
                throws IOException, SAXException, TikaException {
            super.parse(TikaInputStream.get(stream), handler, metadata, context);
        }
    }
}
