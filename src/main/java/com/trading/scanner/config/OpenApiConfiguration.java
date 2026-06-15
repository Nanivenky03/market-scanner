package com.trading.scanner.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI marketScannerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Market Scanner API")
                        .version("1.8.0")
                        .description("Development and simulation APIs for Market Scanner"));
    }
}