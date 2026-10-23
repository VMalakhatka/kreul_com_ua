package org.example.proect.lavka.controller;

import org.example.proect.lavka.dao.wp.FullSyncJobStore;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.example.proect.lavka.service.sync.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
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
        mvc=MockMvcBuilders.standaloneSetup(new FullSyncJobController(service,store,properties)).build();
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
