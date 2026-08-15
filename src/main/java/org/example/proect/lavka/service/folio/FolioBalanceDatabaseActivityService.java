package org.example.proect.lavka.service.folio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioBalanceDatabaseActivityDao;
import org.example.proect.lavka.dto.folio.FolioBalanceDatabaseActivityResponse;
import org.example.proect.lavka.dto.folio.FolioBalanceDatabaseActivityResponse.Issue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FolioBalanceDatabaseActivityService {

    private static final String PROCEDURE = "I_DOLG_DOC";

    private final FolioBalanceDatabaseActivityDao dao;
    @Qualifier("folioBalanceClock")
    private final Clock clock;

    public FolioBalanceDatabaseActivityResponse inspect() {
        LocalDateTime checkedAt = LocalDateTime.now(clock);
        try {
            var inspection = dao.inspect();
            List<Issue> warnings = new ArrayList<>();
            if (inspection.inspectionFailures() > 0) {
                warnings.add(new Issue(
                        "PARTIAL_SESSION_INSPECTION",
                        "Не все подходящие сессии SQL Server удалось проверить",
                        Map.of("failedSessions", inspection.inspectionFailures())
                ));
            }
            String state = state(inspection);
            if ("NOT_DETECTED".equals(state)) {
                warnings.add(new Issue(
                        "PROCEDURE_NOT_DETECTED",
                        "В момент проверки выполнение I_DOLG_DOC не обнаружено",
                        Map.of("pointInTimeCheck", true)
                ));
            } else if ("IDLE_SESSION".equals(state)) {
                warnings.add(new Issue(
                        "PROCEDURE_SESSION_IDLE",
                        "Найдена сессия, последний запрос которой — I_DOLG_DOC, но сейчас она sleeping",
                        Map.of("pointInTimeCheck", true)
                ));
            }
            return response(!"UNAVAILABLE".equals(state), checkedAt, state, inspection, List.copyOf(warnings));
        } catch (Exception e) {
            log.warn("[folio.balance.database-activity] diagnostic failed type={}",
                    e.getClass().getSimpleName());
            return new FolioBalanceDatabaseActivityResponse(
                    false,
                    checkedAt,
                    PROCEDURE,
                    "UNAVAILABLE",
                    0,
                    0,
                    0,
                    0,
                    List.of(new Issue(
                            "DATABASE_ACTIVITY_UNAVAILABLE",
                            "Не удалось проверить активность I_DOLG_DOC",
                            Map.of()
                    ))
            );
        }
    }

    private static String state(FolioBalanceDatabaseActivityDao.Inspection inspection) {
        if (inspection.blockedSessions() > 0) {
            return "BLOCKED";
        }
        if (inspection.activeSessions() > 0) {
            return "RUNNING";
        }
        if (inspection.idleSessions() > 0) {
            return "IDLE_SESSION";
        }
        if (inspection.inspectionFailures() > 0) {
            return "UNAVAILABLE";
        }
        return "NOT_DETECTED";
    }

    private static FolioBalanceDatabaseActivityResponse response(
            boolean ok,
            LocalDateTime checkedAt,
            String state,
            FolioBalanceDatabaseActivityDao.Inspection inspection,
            List<Issue> warnings) {
        return new FolioBalanceDatabaseActivityResponse(
                ok,
                checkedAt,
                PROCEDURE,
                state,
                inspection.detectedSessions(),
                inspection.activeSessions(),
                inspection.blockedSessions(),
                inspection.idleSessions(),
                warnings
        );
    }
}
