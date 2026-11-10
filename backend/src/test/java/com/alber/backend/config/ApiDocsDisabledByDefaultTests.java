package com.alber.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Without the dev profile (e.g. a packaged jar), the API docs and Swagger UI aren't published. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsDisabledByDefaultTests {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void apiDocsAreNotServedWithoutTheDevProfile() {
        assertThat(mvc.get().uri("/v3/api-docs")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void swaggerUiIsNotServedWithoutTheDevProfile() {
        assertThat(mvc.get().uri("/swagger-ui.html")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
