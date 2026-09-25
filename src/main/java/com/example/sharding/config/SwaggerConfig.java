package com.example.sharding.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger UI configuration.
 *
 * <p>Swagger UI is available at: <a href="http://localhost:8080/swagger-ui.html">
 * http://localhost:8080/swagger-ui.html</a></p>
 * <p>OpenAPI JSON spec at: <a href="http://localhost:8080/v3/api-docs">
 * http://localhost:8080/v3/api-docs</a></p>
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sharding & Replication API")
                        .description("""
                                REST API demonstrating **PostgreSQL sharding with streaming replication**
                                using Hibernate and Spring Boot.

                                ### Routing Strategy
                                - **Writes** (`POST`, `PATCH`) → routed to the **Primary** of the resolved shard
                                - **Reads** (`GET`) → routed to the **Replica** of the resolved shard
                                - **Shard key**: `userId` — hash-based: `shard = userId % 3`

                                ### Topology
                                | Shard | Primary Port | Replica Port |
                                |-------|-------------|--------------|
                                | 0     | 5432        | 5435         |
                                | 1     | 5433        | 5437         |
                                | 2     | 5434        | 5438         |
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Jatin Ghataliya")
                                .email("prajapati.jatin94@gmail.com")
                                .url("https://github.com/Jatinghataliya/sharding-replication"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local development server")));
    }
}
