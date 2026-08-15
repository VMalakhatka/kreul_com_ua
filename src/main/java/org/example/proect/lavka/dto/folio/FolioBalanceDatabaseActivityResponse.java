package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FolioBalanceDatabaseActivityResponse(
        boolean ok,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        LocalDateTime checkedAt,
        String procedure,
        String state,
        int detectedSessions,
        int activeSessions,
        int blockedSessions,
        int idleSessions,
        List<Issue> warnings
) {
    public record Issue(
            String code,
            String message,
            Map<String, Object> details
    ) {
    }
}
