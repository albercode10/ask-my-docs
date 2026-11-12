package com.alber.backend.book;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * Builds a small EPUB 2 at test time, so tests never need the real (copyrighted) book. Every chapter's text is made
 * up, with marker phrases the tests look for. Like the real book, the chapters are plain HTML rather than well-formed
 * XHTML (an unclosed {@code <link>}), and they show an illustration.
 */
public final class TestEpubs {

    /** Marker phrase in chapter one. */
    public static final String CHAPTER_ONE_MARKER = "Waxed moustaches were popular among cyclists in 1890";

    /** Marker phrase in chapter two, with curly braces so prompt templating would break it. */
    public static final String CHAPTER_TWO_MARKER = "Trim the beard {weekly} with sharp scissors";

    private TestEpubs() {}

    /** Writes the fixture EPUB to a new temporary directory and returns its path. */
    public static Path writeToTempDir() {
        try {
            return write(Files.createTempDirectory("fixture-book").resolve("book.epub"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Writes a two-chapter fixture EPUB to {@code file} and returns it. The zip and the manifest list chapter two first,
     * the spine chapter one first, so reading in spine order is what puts chapter one's marker first.
     */
    public static Path write(Path file) throws IOException {
        return write(file, true);
    }

    /** Writes a well-formed EPUB whose chapters have an illustration but no text, and returns it. */
    public static Path writeWithoutText(Path file) throws IOException {
        return write(file, false);
    }

    private static Path write(Path file, boolean withText) throws IOException {
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
            add(zip, "OEBPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Fixture Book</dc:title>
                        <dc:identifier id="id">fixture-book</dc:identifier>
                        <dc:language>en</dc:language>
                      </metadata>
                      <manifest>
                        <item id="style" href="style.css" media-type="text/css"/>
                        <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="pic" href="pic1.jpg" media-type="image/jpeg"/>
                      </manifest>
                      <spine>
                        <itemref idref="ch1"/>
                        <itemref idref="ch2"/>
                      </spine>
                    </package>
                    """);
            zip.putNextEntry(new ZipEntry("OEBPS/pic1.jpg"));
            ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpg", zip);
            zip.closeEntry();
            add(zip, "OEBPS/style.css", "p { margin: 0; color: #333; }\n");
            add(zip, "OEBPS/ch2.xhtml", withText ? chapter("Grooming", CHAPTER_TWO_MARKER + ".") : imageOnly());
            add(zip, "OEBPS/ch1.xhtml", withText ? chapter("A Short History", CHAPTER_ONE_MARKER + ".") : imageOnly());
        }
        return file;
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

    private static String chapter(String title, String text) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>%1$s</title><link rel="stylesheet" type="text/css" href="style.css"></head>
                  <body>
                    <h1>%1$s</h1>
                    <p><img src="pic1.jpg" alt=""></p>


                    <p>%2$s</p>
                    <p>   </p>
                    <p>Another short paragraph.</p>
                  </body>
                </html>
                """.formatted(title, text);
    }

    private static void add(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
