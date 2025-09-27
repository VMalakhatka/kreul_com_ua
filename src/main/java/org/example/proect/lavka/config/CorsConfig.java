package org.example.proect.lavka.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // допустимые origin'ы
                .allowedOrigins(
                        "https://api.kreul.com.ua", // твой продакшен Swagger UI
                        "http://127.0.0.1:8080",    // локальный Swagger UI
                        "http://localhost:8080"     // на всякий случай
                )
                .allowedMethods("GET","POST","PUT","DELETE","PATCH","OPTIONS")
                .allowCredentials(true);
    }
}