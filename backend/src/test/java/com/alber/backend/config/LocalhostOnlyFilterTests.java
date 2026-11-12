package com.alber.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The API only answers requests addressed to localhost. Listening on 127.0.0.1 keeps other machines out; checking the
 * Host header also stops a web page in the developer's browser that points its own domain at 127.0.0.1 (DNS
 * rebinding).
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocalhostOnlyFilterTests {

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest
    @ValueSource(
            strings = {"attacker.example", "attacker.example:8080", "192.168.1.20:8080", "localhost.attacker.example"})
    void requestForAnotherHostIsRejected(String host) {
        assertThat(blankQuestion(host)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "localhost:8080", "127.0.0.1:8080", "[::1]:8080", "LOCALHOST:8080"})
    void requestForLocalhostReachesTheApi(String host) {
        // A blank question is rejected by validation, so 400 proves the request got past the filter.
        assertThat(blankQuestion(host)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult blankQuestion(String host) {
        return mvc.post()
                .uri("/chat")
                .header("Host", host)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"  \"}")
                .exchange();
    }
}
