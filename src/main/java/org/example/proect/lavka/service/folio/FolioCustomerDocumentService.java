package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioCustomerDocumentDao;
import org.example.proect.lavka.dao.folio.FolioCustomerDocumentDao.DocumentCursor;
import org.example.proect.lavka.dao.folio.FolioCustomerDocumentDao.PartnerRow;
import org.example.proect.lavka.dto.folio.FolioCustomerDocumentDetailResponse;
import org.example.proect.lavka.dto.folio.FolioCustomerDocumentType;
import org.example.proect.lavka.dto.folio.FolioCustomerDocumentsResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FolioCustomerDocumentService {

    private static final int MAX_PARTNER_ID_LENGTH = 8;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final long MAX_PERIOD_DAYS_INCLUSIVE = 366;
    private static final DateTimeFormatter CURSOR_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final List<FolioCustomerDocumentType> ALL_TYPES = List.of(
            FolioCustomerDocumentType.ACCOUNT,
            FolioCustomerDocumentType.EXPENSE,
            FolioCustomerDocumentType.PAYMENT
    );

    private final FolioCustomerDocumentDao dao;
    private final Clock clock;

    public FolioCustomerDocumentService(
            FolioCustomerDocumentDao dao,
            @Qualifier("folioBalanceClock") Clock clock) {
        this.dao = dao;
        this.clock = clock;
    }

    @Transactional(transactionManager = "mssqlTransactionManager", readOnly = true)
    public FolioCustomerDocumentsResponse list(
            String partnerShortName,
            LocalDate dateFrom,
            LocalDate dateTo,
            String types,
            Integer limit,
            String cursor) {
        String partnerId = normalizePartnerShortName(partnerShortName);
        PartnerRow partner = findPartner(partnerId);
        LocalDate today = LocalDate.now(clock);
        LocalDate normalizedTo = dateTo == null ? today : dateTo;
        LocalDate normalizedFrom = dateFrom == null
                ? normalizedTo.minusYears(1).plusDays(1)
                : dateFrom;
        validatePeriod(normalizedFrom, normalizedTo, today);
        List<FolioCustomerDocumentType> normalizedTypes = normalizeTypes(types);
        int normalizedLimit = normalizeLimit(limit);
        DocumentCursor decodedCursor = decodeCursor(cursor);
        validateCursor(decodedCursor, normalizedFrom, normalizedTo, normalizedTypes);

        List<FolioCustomerDocumentsResponse.DocumentSummary> fetched = dao.findDocuments(
                partnerId,
                normalizedFrom,
                normalizedTo,
                normalizedTypes,
                normalizedLimit + 1,
                decodedCursor
        );
        boolean hasMore = fetched.size() > normalizedLimit;
        List<FolioCustomerDocumentsResponse.DocumentSummary> page = hasMore
                ? List.copyOf(fetched.subList(0, normalizedLimit))
                : List.copyOf(fetched);
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1))
                : null;

        return new FolioCustomerDocumentsResponse(
                true,
                new FolioCustomerDocumentsResponse.Partner(partner.shortName(), partner.name()),
                new FolioCustomerDocumentsResponse.Filters(
                        normalizedFrom, normalizedTo, normalizedTypes, normalizedLimit
                ),
                page,
                nextCursor,
                hasMore,
                listWarnings()
        );
    }

    @Transactional(transactionManager = "mssqlTransactionManager", readOnly = true)
    public FolioCustomerDocumentDetailResponse get(
            String partnerShortName,
            String documentType,
            long documentId) {
        String partnerId = normalizePartnerShortName(partnerShortName);
        if (documentId <= 0) {
            throw validation("invalid_document_id", "documentId must be positive");
        }
        FolioCustomerDocumentType type = parseType(documentType);
        PartnerRow partner = findPartner(partnerId);

        FolioCustomerDocumentDetailResponse.Document document = type == FolioCustomerDocumentType.PAYMENT
                ? dao.findPayment(partnerId, documentId).orElseThrow(() -> notFound(type, documentId))
                : dao.findStockDocument(partnerId, type, documentId)
                        .orElseThrow(() -> notFound(type, documentId));

        return new FolioCustomerDocumentDetailResponse(
                true,
                new FolioCustomerDocumentDetailResponse.Partner(partner.shortName(), partner.name()),
                document,
                detailWarnings(document)
        );
    }

    private PartnerRow findPartner(String partnerId) {
        return dao.findPartner(partnerId)
                .orElseThrow(() -> new FolioPartnerNotFoundException(partnerId));
    }

    private static String normalizePartnerShortName(String partnerShortName) {
        String value = partnerShortName == null ? "" : partnerShortName.trim();
        if (value.isEmpty()) {
            throw validation("missing_partner_short_name", "partnerShortName is required");
        }
        if (value.length() > MAX_PARTNER_ID_LENGTH) {
            throw validation(
                    "partner_short_name_too_long",
                    "partnerShortName must fit _PARTNER.N_USER varchar(8)"
            );
        }
        return value;
    }

    private static void validatePeriod(LocalDate dateFrom, LocalDate dateTo, LocalDate today) {
        if (dateFrom.isAfter(dateTo)) {
            throw validation("invalid_date_range", "dateFrom must be before or equal to dateTo");
        }
        if (dateTo.isAfter(today)) {
            throw validation("future_date_to", "dateTo must not be after the current business date");
        }
        long inclusiveDays = ChronoUnit.DAYS.between(dateFrom, dateTo) + 1;
        if (inclusiveDays > MAX_PERIOD_DAYS_INCLUSIVE) {
            throw validation("date_range_too_long", "The document period must not exceed 366 days");
        }
    }

    private static List<FolioCustomerDocumentType> normalizeTypes(String types) {
        if (types == null || types.isBlank() || "all".equalsIgnoreCase(types.trim())) {
            return ALL_TYPES;
        }
        LinkedHashSet<FolioCustomerDocumentType> result = new LinkedHashSet<>();
        for (String raw : types.split(",", -1)) {
            if (raw.isBlank()) {
                throw validation("invalid_document_type", "types contains an empty value");
            }
            result.add(parseType(raw));
        }
        if (result.isEmpty()) {
            throw validation("invalid_document_type", "At least one document type is required");
        }
        return List.copyOf(result);
    }

    private static FolioCustomerDocumentType parseType(String value) {
        try {
            return FolioCustomerDocumentType.parse(value);
        } catch (IllegalArgumentException e) {
            throw validation(
                    "invalid_document_type",
                    "documentType must be ACCOUNT, EXPENSE or PAYMENT"
            );
        }
    }

    private static int normalizeLimit(Integer limit) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > MAX_LIMIT) {
            throw validation("invalid_limit", "limit must be between 1 and 100");
        }
        return value;
    }

    private static DocumentCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor.trim()),
                    StandardCharsets.UTF_8
            );
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Wrong cursor part count");
            }
            return new DocumentCursor(
                    LocalDateTime.parse(parts[0], CURSOR_DATE),
                    Integer.parseInt(parts[1]),
                    Long.parseLong(parts[2])
            );
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw validation("invalid_cursor", "cursor is invalid or corrupted");
        }
    }

    private static void validateCursor(
            DocumentCursor cursor,
            LocalDate dateFrom,
            LocalDate dateTo,
            List<FolioCustomerDocumentType> types) {
        if (cursor == null) {
            return;
        }
        LocalDate cursorDate = cursor.documentDate().toLocalDate();
        if (cursor.documentId() <= 0
                || cursorDate.isBefore(dateFrom)
                || cursorDate.isAfter(dateTo)
                || types.stream().noneMatch(type -> type.sortRank() == cursor.typeRank())) {
            throw validation("invalid_cursor", "cursor does not match the requested filters");
        }
    }

    private static String encodeCursor(FolioCustomerDocumentsResponse.DocumentSummary document) {
        String raw = CURSOR_DATE.format(document.documentDate())
                + "|" + document.documentType().sortRank()
                + "|" + document.documentId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static List<FolioCustomerDocumentsResponse.Issue> listWarnings() {
        return List.of(
                new FolioCustomerDocumentsResponse.Issue(
                        "ACTIVE_LEDGER_ONLY",
                        "Archived Folio documents are not included",
                        Map.of()
                ),
                new FolioCustomerDocumentsResponse.Issue(
                        "FOLIO_NOLOCK_READ",
                        "Concurrent Folio edits can affect one read response",
                        Map.of()
                )
        );
    }

    private static List<FolioCustomerDocumentDetailResponse.Issue> detailWarnings(
            FolioCustomerDocumentDetailResponse.Document document) {
        List<FolioCustomerDocumentDetailResponse.Issue> warnings = new ArrayList<>();
        warnings.add(new FolioCustomerDocumentDetailResponse.Issue(
                "ACTIVE_LEDGER_ONLY",
                "Archived Folio documents are not included",
                Map.of()
        ));
        warnings.add(new FolioCustomerDocumentDetailResponse.Issue(
                "CURRENT_PRODUCT_NAME",
                "Item name is resolved from the current warehouse product card and may differ from the historical name",
                Map.of()
        ));
        if (document.repeatOrder().allowed()) {
            warnings.add(new FolioCustomerDocumentDetailResponse.Issue(
                    "HISTORICAL_PRICE_ONLY",
                    "Historical prices are informational; Woo must use its current product price",
                    Map.of()
            ));
        }
        return List.copyOf(warnings);
    }

    private static FolioCustomerDocumentNotFoundException notFound(
            FolioCustomerDocumentType type,
            long documentId) {
        return new FolioCustomerDocumentNotFoundException(
                "Folio " + type.name().toLowerCase(Locale.ROOT)
                        + " document not found for this customer: " + documentId
        );
    }

    private static FolioAccountValidationException validation(String code, String message) {
        return new FolioAccountValidationException(code, message);
    }
}
