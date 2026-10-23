package org.example.proect.lavka.controller;

import org.example.proect.lavka.dao.wp.FullSyncJobStore;
import org.example.proect.lavka.exception.GlobalExceptionHandler;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.example.proect.lavka.service.sync.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.io.InputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FullSyncJobControllerTest {
    FullSyncJobService service=mock(FullSyncJobService.class);
    FullSyncJobStore store=mock(FullSyncJobStore.class);
    LavkaApiProperties properties=new LavkaApiProperties();
    MockMvc mvc;
    @BeforeEach void setup() {
        properties.setToken("synthetic-test-only");
        mvc=MockMvcBuilders.standaloneSetup(new FullSyncJobController(service,store,properties))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void requiresConfiguredTokenForReadAndStart() throws Exception {
        mvc.perform(get("/admin/sync/jobs/capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/sync/jobs/capabilities").header("X-Auth-Token","wrong")).andExpect(status().isUnauthorized());
        properties.setToken("");
        mvc.perform(get("/admin/sync/jobs/capabilities").header("X-Auth-Token","synthetic-test-only"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(service,store);
    }
    @Test void capabilitiesAndMissingIdHaveVersionedNoncachedResponses() throws Exception {
        mvc.perform(get("/admin/sync/jobs/capabilities").header("X-Auth-Token","synthetic-test-only"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.protocol").value(1)).andExpect(jsonPath("$.persistent").value(true));
        mvc.perform(get("/admin/sync/jobs/missing").header("X-Auth-Token","synthetic-test-only"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("JOB_NOT_FOUND"));
    }
    @Test void dedicatedTokenDoesNotReplaceSharedCredentials() throws Exception {
        properties.setFullSyncToken("synthetic-full-sync-only");
        mvc.perform(get("/admin/sync/jobs/capabilities").header("X-Auth-Token", "synthetic-full-sync-only"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.protocol").value(1));
        mvc.perform(get("/admin/sync/jobs/capabilities").header("X-Auth-Token", "synthetic-test-only"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        assertEquals("synthetic-test-only", properties.getToken());
        assertFalse(properties.toString().contains("synthetic-full-sync-only"));
    }
    @Test void dedicatedTokenWorksWithoutSharedToken() throws Exception {
        properties.setToken(null);
        properties.setFullSyncToken("synthetic-full-sync-only");
        mvc.perform(get("/admin/sync/jobs/capabilities").header("X-Auth-Token", "synthetic-full-sync-only"))
                .andExpect(status().isOk());
    }
    @Test void blankDedicatedTokenKeepsExistingSharedTokenCompatibility() throws Exception {
        properties.setFullSyncToken("  ");
        mvc.perform(get("/admin/sync/jobs/capabilities").header("X-Auth-Token", "synthetic-test-only"))
                .andExpect(status().isOk());
    }
    @Test void everyRouteRejectsWrongTokenBeforeCallingServiceEvenWithGlobalAdvice() throws Exception {
        properties.setFullSyncToken("synthetic-full-sync-only");
        for (var request : List.of(get("/admin/sync/jobs/capabilities"), get("/admin/sync/jobs/test"),
                get("/admin/sync/jobs/test/chunks"), post("/admin/sync/jobs").contentType(MediaType.APPLICATION_JSON).content("{}"))) {
            mvc.perform(request.header("X-Auth-Token", "synthetic-test-only"))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        }
        verifyNoInteractions(service, store);
    }
    @Test void missingConfigurationIs503OnEveryRouteEvenWithGlobalAdvice() throws Exception {
        properties.setToken(null);
        for (var request : List.of(get("/admin/sync/jobs/capabilities"), get("/admin/sync/jobs/test"),
                get("/admin/sync/jobs/test/chunks"), post("/admin/sync/jobs").contentType(MediaType.APPLICATION_JSON).content("{}"))) {
            mvc.perform(request).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.error").value("JOB_API_NOT_CONFIGURED"));
        }
        verifyNoInteractions(service, store);
    }
    @Test void dedicatedEnvironmentBindingHasNoEmbeddedSecret() throws Exception {
        Properties config = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) { config.load(input); }
        assertEquals("${LAVKA_FULL_SYNC_TOKEN:}", config.getProperty("lavka.full-sync-token"));
    }
    @Test void statusNeverDisclosesPrivateLeaseOrInstance() throws Exception {
        String id=UUID.randomUUID().toString();
        var job=new FullSyncJob(new FullSyncJob.Request(id,UUID.randomUUID().toString(),900000,50,"!",false,false));
        when(service.status(id)).thenReturn(job);
        mvc.perform(get("/admin/sync/jobs/"+id).header("X-Auth-Token","synthetic-test-only"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value(id))
                .andExpect(jsonPath("$.lockToken").doesNotExist()).andExpect(jsonPath("$.request").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }
}
