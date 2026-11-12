package com.alber.backend.book;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the book lives. The EPUB is copyrighted and never committed: it goes in the git-ignored {@code data/books/}
 * folder at the repo root (see README → The book).
 *
 * <p>A {@code String}, not a {@code Path}: Spring converts strings to {@code Path} through a resource lookup, which
 * rejects the {@code ..} in the default {@code ../data/books/…}.
 *
 * @param path the EPUB file; a relative path resolves against the working directory ({@code backend/} for
 *     {@code ./mvnw spring-boot:run})
 */
@ConfigurationProperties("app.book")
public record BookProperties(String path) {

    /** The EPUB file as a path. */
    public Path file() {
        return Path.of(path);
    }
}
