package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioDocumentAuditDao;
import org.example.proect.lavka.dao.folio.FolioProfitReadBudget;
import org.example.proect.lavka.dto.folio.FolioDocumentAuditResponse;
import org.example.proect.lavka.dto.folio.FolioDocumentAuditResponse.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class FolioDocumentAuditService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FolioDocumentAuditService.class);
    private final FolioDocumentAuditDao dao;
    private final FolioDocumentAuditRules rules;
    private final boolean enabled;
    public FolioDocumentAuditService(FolioDocumentAuditDao dao, FolioDocumentAuditRules rules,
            @Value("${lavka.folio.document-audit.enabled:false}") boolean enabled) {
        this.dao=dao; this.rules=rules; this.enabled=enabled;
    }

    @Transactional(transactionManager="mssqlTransactionManager", propagation=Propagation.NOT_SUPPORTED)
    public FolioDocumentAuditResponse audit(LocalDate from, LocalDate to, int pageSize, long afterId, Long upperId,
            String expectedRulesVersion) {
        if (!enabled) throw invalid("DOCUMENT_AUDIT_DISABLED", "Аудит документов отключён");
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from,to) >= 366
                || from.isBefore(LocalDate.of(1753,1,1)) || to.isAfter(LocalDate.of(9999,12,30)))
            throw invalid("DOCUMENT_AUDIT_PERIOD_INVALID", "Нужен диапазон dateFrom/dateTo до 366 дней включительно");
        if (pageSize < 1 || pageSize > 500 || afterId < 0 || (upperId != null && (upperId < 0 || upperId < afterId))
                || (afterId > 0 && upperId == null))
            throw invalid("DOCUMENT_AUDIT_PAGE_INVALID", "Некорректные границы страницы; продолжение требует upperPaymentId");
        if (expectedRulesVersion != null && !FolioDocumentAuditRules.VERSION.equals(expectedRulesVersion))
            throw invalid("DOCUMENT_AUDIT_RULES_CHANGED", "Версия правил изменилась; начните новый аудит");
        String operationId = java.util.UUID.randomUUID().toString();
        long started = System.nanoTime();
        LOG.info("Folio document audit started: operationId={} from={} to={} afterPaymentId={} pageSize={}",operationId,from,to,afterId,pageSize);
        try (var budget = new FolioProfitReadBudget(90,30)) {
            LocalDate until = to.plusDays(1);
            long upper = upperId == null ? dao.upperId(from,until) : upperId;
            long total = upper == 0 ? 0 : dao.count(from,until,upper);
            var source = upper == 0 ? List.<FolioDocumentAuditDao.Row>of() : dao.page(from,until,afterId,upper,pageSize+1);
            List<Item> items = source.stream().limit(pageSize).map(rules::evaluate).toList();
            boolean more = source.size() > pageSize;
            Long next = more ? items.get(items.size()-1).document().paymentId() : null;
            Map<String,Long> statuses = new LinkedHashMap<>();
            statuses.put("VALID",0L); statuses.put("ERROR",0L); statuses.put("RULE_REVIEW",0L);
            Map<String,Long> categories = new LinkedHashMap<>();
            items.forEach(item -> {
                statuses.merge(item.status(),1L,Long::sum);
                categories.merge(item.category().code(),1L,Long::sum);
            });
            LOG.info("Folio document audit completed: operationId={} examined={} hasMore={} durationMs={}",
                    operationId,items.size(),more,(System.nanoTime()-started)/1_000_000);
            return new FolioDocumentAuditResponse(true,"PAGE_READY",FolioDocumentAuditRules.VERSION,OffsetDateTime.now(ZoneOffset.UTC),
                    from,to,coverage(true),new Pagination(pageSize,afterId,upper,next,more,total),
                    new Summary("PAGE",items.size(),Map.copyOf(statuses),Map.copyOf(categories)),rules.catalog(),items,null,null);
        } catch (DataAccessException failure) {
            LOG.warn("Folio document audit failed: operationId={} exceptionType={} durationMs={}",operationId,
                    failure.getClass().getSimpleName(),(System.nanoTime()-started)/1_000_000);
            return new FolioDocumentAuditResponse(false,"SOURCE_UNAVAILABLE",FolioDocumentAuditRules.VERSION,OffsetDateTime.now(ZoneOffset.UTC),
                    from,to,coverage(false),null,null,rules.catalog(),List.of(),"DOCUMENT_AUDIT_SOURCE_UNAVAILABLE",operationId);
        }
    }

    private static Coverage coverage(boolean pageComplete) {
        return new Coverage("SCL_PLAT", "DOCUMENT_DATE_INCLUSIVE", "LIVE_NOT_SNAPSHOT", true,
                List.of("INCOMING","OUTGOING","UNKNOWN"),List.of("CASH","BANK","UNKNOWN"),pageComplete,false,
                List.of("SCL_NAKL/SCL_MOVE товарные документы", "Сопоставление пар переводов/выписок",
                        "Автор/дата создания и коррекции, приложение и основание документа",
                        "Историческая привязка касса/карта/ФОП к складу", "Полнота всех бизнес-правил", "Подтверждение валюты SUM_POR"),
                List.of("VALID означает только прохождение реализованных проверок", "Изменения/удаления между страницами возможны",
                        "Документы вне диапазона дат не включаются по периоду примечания", "Примечание содержит закрытые финансовые данные; не публиковать и не логировать",
                        "Правила листа3 с01.09.2026 не применены ретроспективно; старый лист3 не используется"));
    }
    private static FolioAccountValidationException invalid(String code,String message) { return new FolioAccountValidationException(code,message); }
}
