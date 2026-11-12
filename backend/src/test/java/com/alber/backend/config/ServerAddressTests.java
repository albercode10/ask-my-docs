package com.alber.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * There's no authentication yet, so the API listens on the loopback interface only: nobody else on the network can
 * use the local model or pull the book through it.
 */
@SpringBootTest
class ServerAddressTests {

    @Autowired
    private Environment environment;

    @Test
    void serverListensOnLocalhostOnly() {
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
    }
}
