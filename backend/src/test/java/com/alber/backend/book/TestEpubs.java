package com.alber.backend.book;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * Builds small EPUB 2 files at test time, so tests never need the real (copyrighted) book. Every chapter's text is
 * made up, with marker phrases the tests look for. Like the real book, the chapters are plain HTML rather than
 * well-formed XHTML (an unclosed {@code <link>}), they show an illustration, and the chapter titles come from a
 * {@code toc.ncx} whose labels differ from the chapters' own headings.
 */
public final class TestEpubs {

    /** Marker phrase in chapter one. */
    public static final String CHAPTER_ONE_MARKER = "Waxed moustaches were popular among cyclists in 1890";

    /** Marker phrase in chapter two, with curly braces so prompt templating would break it. */
    public static final String CHAPTER_TWO_MARKER = "Trim the beard {weekly} with sharp scissors";

    /** Chapter one's title in the table of contents (its heading in the text is "A Short History"). */
    public static final String CHAPTER_ONE_TITLE = "One: A Short History";

    /** Chapter two's title in the table of contents (its heading in the text is "Grooming"). */
    public static final String CHAPTER_TWO_TITLE = "Two: Grooming";

    /** Marker phrase in the front matter that comes before the first table-of-contents entry. */
    public static final String FRONT_MATTER_MARKER = "Printed on paper from sustainable forests";

    /** Marker phrase in the second file of chapter one, which the table of contents doesn't list. */
    public static final String CONTINUATION_MARKER = "Pomade came later, with the barbershop revival";

    private static final String NCX_DOCTYPE =
            "<!DOCTYPE ncx PUBLIC \"-//NISO//DTD ncx 2005-1//EN\" \"http://www.daisy.org/z3986/2005/ncx-2005-1.dtd\">";

    private TestEpubs() {}

    /** Writes the two-chapter fixture EPUB to a new temporary directory and returns its path. */
    public static Path writeToTempDir() {
        try {
            return write(Files.createTempDirectory("fixture-book").resolve("book.epub"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Writes a two-chapter fixture EPUB to {@code file} and returns it. The zip and the manifest list chapter two first,
     * the spine chapter one first, so reading in spine order is what puts chapter one's marker first. The table of
     * contents points at chapter two with a {@code #fragment}.
     */
    public static Path write(Path file) throws IOException {
        return write(file, List.of(chapterOne(), chapterTwo()), ncx(List.of(chapterOne(), chapterTwo())));
    }

    /** The two chapters without a table of contents, so no chapter has a title. */
    public static Path writeWithoutToc(Path file) throws IOException {
        return write(file, List.of(chapterOne(), chapterTwo()), null);
    }

    /**
     * Front matter, chapter one in two files, then chapter two. Only chapter one's first file and chapter two are in
     * the table of contents.
     */
    public static Path writeWithFrontMatterAndContinuation(Path file) throws IOException {
        Part front = new Part("front", "front.xhtml", chapter("Imprint", FRONT_MATTER_MARKER + "."), null);
        Part continuation = new Part("ch1b", "ch1b.xhtml", chapter("More history", CONTINUATION_MARKER + "."), null);
        return write(
                file,
                List.of(front, chapterOne(), continuation, chapterTwo()),
                ncx(List.of(chapterOne(), chapterTwo())));
    }

    /**
     * The two chapters, with a table of contents that declares an external entity reading {@code secretFile} and uses
     * it as chapter one's title. A safe reader never reads the file.
     */
    public static Path writeWithExternalEntityInToc(Path file, Path secretFile) throws IOException {
        String ncx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE ncx [<!ENTITY secret SYSTEM "%s">]>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="n1" playOrder="1">
                      <navLabel><text>Leaked: &secret;</text></navLabel>
                      <content src="ch1.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>
                """.formatted(secretFile.toUri());
        return write(file, List.of(chapterOne(), chapterTwo()), ncx);
    }

    /** Writes a well-formed EPUB whose chapters have an illustration but no text, and returns it. */
    public static Path writeWithoutText(Path file) throws IOException {
        Part one = new Part("ch1", "ch1.xhtml", imageOnly(), CHAPTER_ONE_TITLE);
        Part two = new Part("ch2", "ch2.xhtml", imageOnly(), CHAPTER_TWO_TITLE);
        return write(file, List.of(one, two), ncx(List.of(one, two)));
    }

    private static Part chapterOne() {
        return new Part("ch1", "ch1.xhtml", chapter("A Short History", CHAPTER_ONE_MARKER + "."), CHAPTER_ONE_TITLE);
    }

    private static Part chapterTwo() {
        return new Part("ch2", "ch2.xhtml", chapter("Grooming", CHAPTER_TWO_MARKER + "."), CHAPTER_TWO_TITLE);
    }

    /** One spine item: manifest id, file name next to the OPF, its HTML, and its title in the TOC (null if absent). */
    private record Part(
            String id, String file, String html, @Nullable String tocTitle) {}

    private static String ncx(List<Part> parts) {
        StringBuilder points = new StringBuilder();
        int order = 1;
        for (Part part : parts) {
            if (part.tocTitle() == null) {
                continue;
            }
            // Chapter two's entry points at an anchor inside its file, as real tables of contents often do.
            String src = part.id().equals("ch2") ? part.file() + "#start" : part.file();
            points.append("""
                        <navPoint id="nav%1$d" playOrder="%1$d">
                          <navLabel><text>%2$s</text></navLabel>
                          <content src="%3$s"/>
                        </navPoint>
                    """.formatted(order++, part.tocTitle(), src));
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                %s
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <docTitle><text>Fixture Book</text></docTitle>
                  <navMap>
                %s  </navMap>
                </ncx>
                """.formatted(NCX_DOCTYPE, points);
    }

    private static Path write(Path file, List<Part> spine, @Nullable String ncx) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            // The mimetype entry must come first and be stored uncompressed.
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            ZipEntry entry = new ZipEntry("mimetype");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(mimetype.length);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(mimetype);
            zip.closeEntry();

            add(zip, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """);
            add(zip, "OEBPS/content.opf", opf(spine, ncx != null));
            zip.putNextEntry(new ZipEntry("OEBPS/pic1.jpg"));
            ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpg", zip);
            zip.closeEntry();
            add(zip, "OEBPS/style.css", "p { margin: 0; color: #333; }\n");
            if (ncx != null) {
                add(zip, "OEBPS/toc.ncx", ncx);
            }
            // Zip order is the reverse of the spine, so only reading in spine order gives the right order.
            for (Part part : spine.reversed()) {
                add(zip, "OEBPS/" + part.file(), part.html());
            }
        }
        return file;
    }

    private static String opf(List<Part> spine, boolean withToc) {
        List<String> manifest = new ArrayList<>();
        manifest.add("<item id=\"style\" href=\"style.css\" media-type=\"text/css\"/>");
        if (withToc) {
            manifest.add("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>");
        }
        for (Part part : spine.reversed()) {
            manifest.add("<item id=\"%s\" href=\"%s\" media-type=\"application/xhtml+xml\"/>"
                    .formatted(part.id(), part.file()));
        }
        manifest.add("<item id=\"pic\" href=\"pic1.jpg\" media-type=\"image/jpeg\"/>");
        StringBuilder itemrefs = new StringBuilder();
        for (Part part : spine) {
            itemrefs.append("    <itemref idref=\"").append(part.id()).append("\"/>\n");
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Fixture Book</dc:title>
                    <dc:identifier id="id">fixture-book</dc:identifier>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest>
                    %s
                  </manifest>
                  <spine%s>
                %s  </spine>
                </package>
                """.formatted(String.join("\n    ", manifest), withToc ? " toc=\"ncx\"" : "", itemrefs);
    }

    private static String imageOnly() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><link rel="stylesheet" type="text/css" href="style.css"></head>
                  <body>
                    <p><img src="pic1.jpg" alt=""></p>
                    <p>   </p>
                  </body>
                </html>
                """;
    }

    private static String chapter(String heading, String text) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Fixture Book</title><link rel="stylesheet" type="text/css" href="style.css"></head>
                  <body>
                    <h1 id="start">%1$s</h1>
                    <p><img src="pic1.jpg" alt=""></p>


                    <p>%2$s</p>
                    <p>   </p>
                    <p>Another short paragraph.</p>
                  </body>
                </html>
                """.formatted(heading, text);
    }

    private static void add(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
