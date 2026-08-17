package org.example.folioruslab.web;

import org.example.folioruslab.config.LabProperties;
import org.example.folioruslab.security.BearerTokenAuthenticationFilter;
import org.example.folioruslab.security.SecurityConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FolioRusLabWebSecurityTest {

    private static final String LAB_AUTHENTICATOR = "test-authenticator-" + "x".repeat(32);

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private RecordingLabService recordingLabService;

    @BeforeAll
    void setUpWebContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SecurityTestConfiguration.class);
        context.refresh();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        recordingLabService = context.getBean(RecordingLabService.class);
    }

    @AfterAll
    void closeWebContext() {
        context.close();
    }

    @BeforeEach
    void resetService() {
        recordingLabService.reset();
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"));

        assertEquals(0, recordingLabService.calls());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/swagger-ui.html", "/v3/api-docs"})
    void swaggerEndpointsArePublicButDoNotReachLabService(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk());

        assertEquals(0, recordingLabService.calls());
    }

    @Test
    void apiWithoutTokenIsRejectedBeforeController() throws Exception {
        mockMvc.perform(get("/api/v1/preflight"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertEquals(0, recordingLabService.calls());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Basic not-a-bearer-token",
            "bearer wrong-case-token",
            "Bearer wrong-token",
            "Bearer"
    })
    void malformedOrWrongAuthorizationIsRejected(String authorization) throws Exception {
        mockMvc.perform(get("/api/v1/preflight")
                        .header("Authorization", authorization))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertEquals(0, recordingLabService.calls());
    }

    @Test
    void validTokenReachesPreflightService() throws Exception {
        mockMvc.perform(get("/api/v1/preflight")
                        .header("Authorization", "Bearer " + LAB_AUTHENTICATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("Paint_Rus"))
                .andExpect(jsonPath("$.compatibilityLevel").value(80))
                .andExpect(jsonPath("$.codePage").value(1251))
                .andExpect(jsonPath("$.safeToRun").value(true));

        assertEquals(1, recordingLabService.calls());
    }

    @Test
    void validTokenAllowsPostAndReachesExecutionService() throws Exception {
        mockMvc.perform(post("/api/v1/sql/execute")
                        .header("Authorization", "Bearer " + LAB_AUTHENTICATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.database").value("Paint_Rus"));

        assertEquals(1, recordingLabService.calls());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class SecurityTestConfiguration {

        @Bean
        LabProperties labProperties() {
            LabProperties properties = new LabProperties();
            properties.setToken(LAB_AUTHENTICATOR);
            return properties;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter(
                LabProperties properties,
                ObjectMapper objectMapper
        ) {
            return new BearerTokenAuthenticationFilter(properties, objectMapper);
        }

        @Bean
        RecordingLabService recordingLabService() {
            return new RecordingLabService();
        }

        @Bean
        TestApiController testApiController(RecordingLabService service) {
            return new TestApiController(service);
        }

        @Bean
        TestHealthController testHealthController() {
            return new TestHealthController();
        }

        @Bean
        TestDocsController testDocsController() {
            return new TestDocsController();
        }
    }

    @RestController
    static class TestApiController {

        private final RecordingLabService service;

        TestApiController(RecordingLabService service) {
            this.service = service;
        }

        @GetMapping("/api/v1/preflight")
        Map<String, Object> preflight() {
            service.recordCall();
            return Map.of(
                    "database", "Paint_Rus",
                    "compatibilityLevel", 80,
                    "codePage", 1251,
                    "safeToRun", true
            );
        }

        @PostMapping("/api/v1/sql/execute")
        Map<String, Object> execute() {
            service.recordCall();
            return Map.of("state", "ROLLED_BACK", "database", "Paint_Rus");
        }
    }

    @RestController
    static class TestHealthController {

        @GetMapping("/actuator/health")
        Map<String, String> health() {
            return Map.of("status", "UP");
        }
    }

    @RestController
    static class TestDocsController {

        @GetMapping({"/swagger-ui.html", "/v3/api-docs"})
        Map<String, String> docs() {
            return Map.of("status", "available");
        }
    }

    static final class RecordingLabService {

        private int calls;

        void recordCall() {
            calls++;
        }

        int calls() {
            return calls;
        }

        void reset() {
            calls = 0;
        }
    }
}
