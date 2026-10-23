package org.example.proect.lavka.controller;

import org.example.proect.lavka.dao.wp.FullSyncJobStore;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.example.proect.lavka.service.sync.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/admin/sync/jobs")
public class FullSyncJobController {
    private final FullSyncJobService service;
    private final FullSyncJobStore store;
    private final LavkaApiProperties properties;
    public FullSyncJobController(FullSyncJobService service, FullSyncJobStore store, LavkaApiProperties properties) {
        this.service = service; this.store = store; this.properties = properties;
    }
    private ResponseEntity<?> authorize(String token) {
        String expected = properties.getFullSyncToken();
        if (expected == null || expected.isBlank()) expected = properties.getToken();
        if (expected == null || expected.isBlank())
            return response(503, Map.of("protocol", 1, "error", "JOB_API_NOT_CONFIGURED"));
        if (token == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
            return response(401, Map.of("protocol", 1, "error", "UNAUTHORIZED"));
        return null;
    }
    private ResponseEntity<?> response(int code, Object body) {
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(body);
    }
    @GetMapping("/capabilities")
    public ResponseEntity<?> capabilities(@RequestHeader(value="X-Auth-Token", required=false) String token) {
        ResponseEntity<?> denied = authorize(token);
        if (denied != null) return denied;
        FullSyncJob active = store.active();
        return response(200, Map.of("protocol", 1, "persistent", true, "maxChunkSize", 100,
                "activeJobId", active == null ? "" : active.request.jobId()));
    }
    @PostMapping
    public ResponseEntity<?> start(@RequestBody FullSyncJob.Request request,
            @RequestHeader(value="X-Auth-Token", required=false) String token) {
        ResponseEntity<?> denied = authorize(token);
        if (denied != null) return denied;
        try { return response(202, service.start(request).view()); }
        catch (IllegalArgumentException e) { return response(400, Map.of("error", "INVALID_OR_CONFLICTING_JOB_REQUEST")); }
        catch (IllegalStateException e) { return response(409, Map.of("error", "FULL_SYNC_BUSY_OR_LEASE_MISSING")); }
    }
    @GetMapping("/{id}")
    public ResponseEntity<?> status(@PathVariable String id, @RequestHeader(value="X-Auth-Token", required=false) String token) {
        ResponseEntity<?> denied = authorize(token);
        if (denied != null) return denied;
        FullSyncJob job = service.status(id);
        return job == null ? response(404, Map.of("error", "JOB_NOT_FOUND", "protocol", 1)) : response(200, job.view());
    }
    @GetMapping("/{id}/chunks")
    public ResponseEntity<?> chunks(@PathVariable String id, @RequestParam(defaultValue="0") int after,
            @RequestHeader(value="X-Auth-Token", required=false) String token) {
        ResponseEntity<?> denied = authorize(token);
        if (denied != null) return denied;
        return response(200, store.chunks(id, Math.max(0, after)));
    }
}
