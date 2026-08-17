package org.example.folioruslab.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "folioRusLabToken";

    @Bean
    OpenAPI folioRusLabOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ФОЛИО Paint_Rus — локальная лаборатория")
                        .version("1.0")
                        .description("Локальный API для контролируемых запросов к копии Paint_Rus. "
                                + "По умолчанию SQL выполняется в режиме ROLLBACK.")
                        .license(new License().name("Internal use only")))
                .components(new Components().addSecuritySchemes(
                        BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("local API token")
                                .description("Значение FOLIO_RUS_API_TOKEN без слова Bearer")
                ));
    }
}
