package com.alber.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API information shown in the OpenAPI document and Swagger UI. Endpoints document themselves. */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI askMyDocsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ask-my-docs API")
                        // The API's version, not the build's. Required by the OpenAPI spec.
                        .version("v0")
                        .description("Ask questions about your documents and get answers grounded in them,"
                                + " using a local LLM through Spring AI and Ollama."));
    }
}
