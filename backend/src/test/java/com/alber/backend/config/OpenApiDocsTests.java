package com.alber.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The API docs are served in the dev profile, which {@code ./mvnw spring-boot:run} activates. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OpenApiDocsTests {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void apiDocsAreServedWithTheApiTitleAndDescription() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.info.title").isEqualTo("ask-my-docs API");
                    // Required by the OpenAPI spec; a custom Info drops springdoc's default.
                    json.assertThat().extractingPath("$.info.version").isEqualTo("v0");
                    json.assertThat()
                            .extractingPath("$.info.description")
                            .asString()
                            .isNotBlank();
                });
    }

    @Test
    void chatEndpointIsDocumented() {
        assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat()
                    .extractingPath("$.paths['/chat'].post.summary")
                    .asString()
                    .isNotBlank();
            json.assertThat()
                    .extractingPath("$.paths['/chat'].post.responses")
                    .asMap()
                    .containsKeys("200", "400", "503");
        });
    }

    @Test
    void swaggerUiHtmlRedirectsToTheSwaggerUi() {
        assertThat(mvc.get().uri("/swagger-ui.html"))
                .hasStatus(HttpStatus.FOUND)
                .redirectedUrl()
                .endsWith("/swagger-ui/index.html");
    }

    @Test
    void swaggerUiIndexIsServed() {
        assertThat(mvc.get().uri("/swagger-ui/index.html"))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.TEXT_HTML)
                .bodyText()
                .contains("swagger-ui");
    }
}
