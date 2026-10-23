package org.example.proect.lavka.service.sync;

import java.util.*;

public class FullSyncJob {
    public record Request(String jobId, String lockToken, int limit, int pageSizeWoo,
                          String cursorAfter, boolean dryRun, boolean forceRefresh) {
        public void validate() {
            if (jobId == null || lockToken == null || !UUID.fromString(jobId).toString().equals(jobId)
                    || !UUID.fromString(lockToken).toString().equals(lockToken) || limit < 1 || limit > 1_000_000
                    || pageSizeWoo < 1 || pageSizeWoo > 100 || (cursorAfter != null &&
                    (cursorAfter.length() > 191 || cursorAfter.chars().anyMatch(Character::isISOControl))))
                throw new IllegalArgumentException("INVALID_JOB_REQUEST");
        }
    }
    public Request request;
    public String status = "QUEUED";
    public String cursor;
    public String instance;
    public String error;
    public int chunks, processed, created, updated, drafted, errorCount;
    public boolean inFlight, safeToRelease, exhausted;
    public long startedAt = System.currentTimeMillis(), updatedAt = startedAt;
    public List<String> errors = new ArrayList<>();

    public FullSyncJob() {}
    public FullSyncJob(Request request) { this.request = request; this.cursor = request.cursorAfter(); }

    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocol", 1); out.put("jobId", request.jobId()); out.put("status", status);
        out.put("cursorAfter", request.cursorAfter()); out.put("nextAfter", cursor);
        out.put("limit", request.limit()); out.put("pageSizeWoo", request.pageSizeWoo());
        out.put("dryRun", request.dryRun()); out.put("forceRefresh", request.forceRefresh());
        out.put("processed", processed); out.put("created", created); out.put("updated", updated);
        out.put("drafted", drafted); out.put("chunks", chunks); out.put("errorCount", errorCount);
        out.put("errors", errors); out.put("error", error); out.put("exhausted", exhausted);
        out.put("safeToRelease", safeToRelease); out.put("startedAt", startedAt); out.put("updatedAt", updatedAt);
        return out;
    }
}
