package com.alber.backend.book;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The default book path goes up from {@code backend/} with {@code ..}. It must bind as a plain file path: Spring's
 * resource-based conversion to {@link Path} rejects {@code ..} and the app wouldn't start.
 */
@SpringBootTest(properties = "app.book.path=../data/books/no-book-in-tests.epub")
class BookPathBindingTests {

    @Autowired
    private BookProperties properties;

    @Test
    void relativePathWithParentDirectoryBindsAndTheAppStarts() {
        assertThat(properties.file()).isEqualTo(Path.of("../data/books/no-book-in-tests.epub"));
    }
}
