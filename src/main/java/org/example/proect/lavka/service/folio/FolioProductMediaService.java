package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioProductMediaDao;
import org.example.proect.lavka.dao.folio.FolioProductMediaDao.MediaRow;
import org.example.proect.lavka.dao.folio.FolioProductMediaDao.ProductRow;
import org.example.proect.lavka.dao.wp.FolioProductMediaRequestDao;
import org.example.proect.lavka.dao.wp.S3MediaIndexDao;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaChangeRequest;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaChangeRequest.Change;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaChangeResponse;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaChangeResponse.Result;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaChangeResponse.Value;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaCommon.ApiMessage;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaCommon.RecordId;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaCommon.S3Match;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaSearchResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FolioProductMediaService {

    private static final int MAIN_FILENAME_MAX_LENGTH = 50;
    private static final int GALLERY_FILENAME_MAX_LENGTH = 100;

    private final FolioProductMediaDao folio;
    private final S3MediaIndexDao s3Index;
    private final FolioProductMediaRequestDao requests;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate mssqlTransaction;

    public FolioProductMediaService(
            FolioProductMediaDao folio,
            S3MediaIndexDao s3Index,
            FolioProductMediaRequestDao requests,
            ObjectMapper objectMapper,
            @Qualifier("mssqlTransactionManager") PlatformTransactionManager transactionManager
    ) {
        this.folio = folio;
        this.s3Index = s3Index;
        this.requests = requests;
        this.objectMapper = objectMapper;
        this.mssqlTransaction = new TransactionTemplate(transactionManager);
        this.mssqlTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    public FolioProductMediaSearchResponse search(
            String sku,
            String filename,
            String roleValue,
            String matchValue,
            Integer limitValue,
            Integer offsetValue
    ) {
        String normalizedSku = trimToNull(sku);
        String normalizedFilename = trimToNull(filename);
        String role = defaultValue(trimToNull(roleValue), "all").toLowerCase(Locale.ROOT);
        String match = defaultValue(trimToNull(matchValue), "exact").toLowerCase(Locale.ROOT);
        int limit = limitValue == null ? 50 : Math.max(1, Math.min(limitValue, 200));
        int offset = offsetValue == null ? 0 : Math.max(0, offsetValue);

        var query = new FolioProductMediaSearchResponse.Query(
                normalizedSku, normalizedFilename, role, match, limit, offset
        );
        List<ApiMessage> errors = new ArrayList<>();
        if (normalizedSku == null && normalizedFilename == null) {
            errors.add(message("SEARCH_FILTER_REQUIRED",
                    "At least sku or filename is required."));
        }
        if (!Set.of("main", "gallery", "all").contains(role)) {
            errors.add(message("INVALID_ROLE", "role must be main, gallery or all."));
        }
        if (!Set.of("exact", "normalized").contains(match)) {
            errors.add(message("INVALID_MATCH", "match must be exact or normalized."));
        }
        if (!errors.isEmpty()) {
            return new FolioProductMediaSearchResponse(false, query, 0, List.of(), List.of(), errors);
        }

        String exactFilename = "exact".equals(match) ? normalizedFilename : null;
        List<MediaRow> rows = new ArrayList<>();
        if (!"gallery".equals(role)) {
            rows.addAll(folio.searchMain(normalizedSku, exactFilename));
        }
        if (!"main".equals(role)) {
            rows.addAll(folio.searchGallery(normalizedSku, exactFilename));
        }

        if (normalizedFilename != null) {
            rows = rows.stream()
                    .filter(row -> filenameMatches(row.filename(), normalizedFilename, match))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        rows.sort(Comparator.comparing(MediaRow::sku)
                .thenComparing(MediaRow::role)
                .thenComparing(row -> row.sortOrder() == null ? Integer.MIN_VALUE : row.sortOrder())
                .thenComparing(MediaRow::recordKey));

        long total = rows.size();
        int from = Math.min(offset, rows.size());
        int to = Math.min(from + limit, rows.size());
        List<FolioProductMediaSearchResponse.Item> items = rows.subList(from, to).stream()
                .map(row -> toSearchItem(row, normalizedFilename, match))
                .toList();

        return new FolioProductMediaSearchResponse(true, query, total, items, List.of(), List.of());
    }

    public FolioProductMediaChangeResponse change(FolioProductMediaChangeRequest request) {
        if (request == null) {
            return invalidChangeResponse(null, true, 0,
                    message("INVALID_REQUEST", "Request body is required."));
        }
        int requested = request.changes() == null ? 0 : request.changes().size();
        if (request.previewOnly() == null) {
            return invalidChangeResponse(request.externalRequestId(), true, requested,
                    message("PREVIEW_ONLY_REQUIRED", "previewOnly must be explicitly true or false."));
        }
        boolean previewOnly = request.previewOnly();
        if (requested == 0) {
            return invalidChangeResponse(request.externalRequestId(), previewOnly, 0,
                    message("CHANGES_REQUIRED", "changes must contain at least one item."));
        }
        if (!previewOnly && trimToNull(request.externalRequestId()) == null) {
            return invalidChangeResponse(request.externalRequestId(), false, requested,
                    message("EXTERNAL_REQUEST_ID_REQUIRED", "externalRequestId is required for apply."));
        }
        if (!previewOnly && request.externalRequestId().trim().length() > 190) {
            return invalidChangeResponse(request.externalRequestId(), false, requested,
                    message("EXTERNAL_REQUEST_ID_TOO_LONG",
                            "externalRequestId must not exceed 190 characters."));
        }

        String requestHash = null;
        if (!previewOnly) {
            requestHash = requestHash(request);
            FolioProductMediaChangeResponse replay = replayIfPresent(request.externalRequestId().trim(), requestHash, requested);
            if (replay != null) {
                return replay;
            }
        }

        List<IndexedChange> indexed = new ArrayList<>();
        for (int i = 0; i < request.changes().size(); i++) {
            indexed.add(new IndexedChange(i, request.changes().get(i)));
        }

        Map<String, List<IndexedChange>> bySku = indexed.stream().collect(Collectors.groupingBy(
                item -> defaultValue(trimToNull(item.change() == null ? null : item.change().sku()), "#invalid:" + item.index()),
                LinkedHashMap::new,
                Collectors.toList()
        ));

        List<Result> results = new ArrayList<>();
        for (List<IndexedChange> skuChanges : bySku.values()) {
            if (previewOnly) {
                results.addAll(processSku(skuChanges, false));
            } else {
                List<Result> oneSku = mssqlTransaction.execute(status ->
                        processSku(skuChanges, true));
                if (oneSku != null) {
                    results.addAll(oneSku);
                }
            }
        }
        results.sort(Comparator.comparingInt(Result::index));
        results.forEach(result -> logResult(request.externalRequestId(), result));

        FolioProductMediaChangeResponse response = response(
                previewOnly,
                request.externalRequestId(),
                results,
                List.of(),
                List.of()
        );
        if (!previewOnly && (response.ok() || countStatus(response.results(), "applied") > 0)) {
            response = saveAndResolveRace(request.externalRequestId().trim(), requestHash, response, requested);
        }
        return response;
    }

    private List<Result> processSku(List<IndexedChange> changes, boolean apply) {
        String sku = changes.stream()
                .map(IndexedChange::change)
                .filter(Objects::nonNull)
                .map(Change::sku)
                .map(FolioProductMediaService::trimToNull)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        PlanningContext context = new PlanningContext(sku, apply);
        List<Plan> plans = changes.stream()
                .map(item -> plan(item, context))
                .toList();

        if (plans.stream().anyMatch(plan -> "blocked".equals(plan.result().status()))) {
            return plans.stream().map(plan -> {
                if (!"ready".equals(plan.result().status())) {
                    return plan.result();
                }
                List<ApiMessage> errors = new ArrayList<>(plan.result().errors());
                errors.add(message("SKU_CHANGESET_BLOCKED",
                        "Another change for the same SKU is blocked; no changes for this SKU were written."));
                return copyResult(plan.result(), "blocked", plan.result().recordId(), errors);
            }).toList();
        }

        if (!apply) {
            return plans.stream().map(Plan::result).toList();
        }

        List<Result> applied = new ArrayList<>();
        for (Plan plan : plans) {
            Result result = plan.result();
            if ("noop".equals(result.status())) {
                applied.add(result);
                continue;
            }
            RecordId recordId = result.recordId();
            int affected;
            switch (plan.operation()) {
                case SET_MAIN -> affected = folio.updateMain(
                        plan.sku(), plan.beforeFilename(), plan.afterFilename());
                case UPDATE_GALLERY -> affected = folio.updateGallery(
                        plan.galleryId(), plan.plusArtic(),
                        plan.beforeFilename(), plan.beforeSortOrder(),
                        plan.afterFilename(), plan.afterSortOrder());
                case ADD_GALLERY -> {
                    int generatedId = folio.insertGallery(
                            plan.plusArtic(), plan.afterFilename(), plan.afterSortOrder());
                    affected = 1;
                    recordId = new RecordId("img_prod", String.valueOf(generatedId));
                }
                default -> throw new IllegalStateException("Unsupported plan operation: " + plan.operation());
            }
            if (affected != 1) {
                throw new IllegalStateException("Folio media optimistic write affected " + affected
                        + " rows for sku=" + plan.sku() + " operation=" + plan.operation().apiValue);
            }
            Result appliedResult = copyResult(result, "applied", recordId, result.errors());
            applied.add(appliedResult);
        }
        return applied;
    }

    private Plan plan(IndexedChange indexed, PlanningContext context) {
        int index = indexed.index();
        Change change = indexed.change();
        if (change == null) {
            return blockedPlan(index, null, null, null, "all", null,
                    message("INVALID_CHANGE", "Change item must not be null."), List.of(), null, null);
        }

        String sku = trimToNull(change.sku());
        Operation operation = Operation.from(change.operation());
        String role = operation == Operation.SET_MAIN ? "main"
                : operation == null ? "all" : "gallery";
        if (sku == null) {
            return blockedPlan(index, change.operation(), null, operation, role, null,
                    message("SKU_REQUIRED", "sku is required."), List.of(), null, null);
        }
        if (operation == null) {
            return blockedPlan(index, change.operation(), sku, null, role, null,
                    message("INVALID_OPERATION", "operation must be set_main, update_gallery or add_gallery."),
                    List.of(), null, null);
        }

        FilenameValidation filenameValidation = validateFilename(change.filename());
        if (filenameValidation.error() != null) {
            return blockedPlan(index, operation.apiValue, sku, operation, role, null,
                    filenameValidation.error(), List.of(), null,
                    new Value(filenameValidation.filename(), change.sortOrder()));
        }
        String filename = filenameValidation.filename();
        int maxLength = operation == Operation.SET_MAIN
                ? MAIN_FILENAME_MAX_LENGTH : GALLERY_FILENAME_MAX_LENGTH;
        if (filename.length() > maxLength) {
            return blockedPlan(index, operation.apiValue, sku, operation, role, null,
                    message("FILENAME_TOO_LONG", "Filename does not fit the Folio column.", Map.of(
                            "length", filename.length(), "maximum", maxLength)),
                    List.of(), null, new Value(filename, change.sortOrder()));
        }
        if (!Charset.forName("windows-1251").newEncoder().canEncode(filename)) {
            return blockedPlan(index, operation.apiValue, sku, operation, role, null,
                    message("INVALID_FILENAME",
                            "Filename contains characters that cannot be stored in Folio CP1251 varchar."),
                    List.of(), null, new Value(filename, change.sortOrder()));
        }

        ProductRow product = context.product();
        if (product == null) {
            return blockedPlan(index, operation.apiValue, sku, operation, role, null,
                    message("SKU_NOT_FOUND", "SKU does not exist in dbo.ALL_ARTC.", Map.of("sku", sku)),
                    List.of(), null, new Value(filename, change.sortOrder()));
        }
        if (operation != Operation.SET_MAIN
                && (product.plusArtic() <= 0 || folio.countProductsByPlusArtic(product.plusArtic()) != 1)) {
            return blockedPlan(index, operation.apiValue, sku, operation, role, null,
                    message("PLUS_ARTIC_REQUIRED", "SKU does not resolve to one non-empty PLUS_ARTIC."),
                    List.of(), null, new Value(filename, change.sortOrder()));
        }

        S3Check s3 = checkS3(filename, change.s3Proof(), true);
        if (!s3.errors().isEmpty()) {
            return blockedPlan(index, operation.apiValue, sku, operation, role, null,
                    s3.errors().get(0), s3.matches(), null, new Value(filename, change.sortOrder()),
                    s3.warnings(), s3.errors());
        }

        return switch (operation) {
            case SET_MAIN -> planSetMain(index, change, context, product, filename, s3);
            case UPDATE_GALLERY -> planUpdateGallery(index, change, context, product, filename, s3);
            case ADD_GALLERY -> planAddGallery(index, change, context, product, filename, s3);
        };
    }

    private Plan planSetMain(int index,
                             Change change,
                             PlanningContext context,
                             ProductRow product,
                             String filename,
                             S3Check s3) {
        String current = context.mainFilename;
        RecordId recordId = new RecordId("ALL_ARTC", product.sku());
        Value before = new Value(current, null);
        Value after = new Value(filename, null);
        if (Objects.equals(current, filename)) {
            return planResult(index, Operation.SET_MAIN, product.sku(), product.plusArtic(), null,
                    current, null, filename, null,
                    result(index, Operation.SET_MAIN.apiValue, "noop", "main", product.sku(),
                            recordId, before, after, s3));
        }
        if (!Objects.equals(current, change.expectedOldFilename())) {
            return blockedPlan(index, Operation.SET_MAIN.apiValue, product.sku(), Operation.SET_MAIN,
                    "main", recordId,
                    oldValueChanged(change.expectedOldFilename(), null, current, null),
                    s3.matches(), before, after, s3.warnings(), List.of(
                            oldValueChanged(change.expectedOldFilename(), null, current, null)));
        }
        context.mainFilename = filename;
        return planResult(index, Operation.SET_MAIN, product.sku(), product.plusArtic(), null,
                current, null, filename, null,
                result(index, Operation.SET_MAIN.apiValue, "ready", "main", product.sku(),
                        recordId, before, after, s3));
    }

    private Plan planUpdateGallery(int index,
                                   Change change,
                                   PlanningContext context,
                                   ProductRow product,
                                   String filename,
                                   S3Check s3) {
        Integer id = parseRecordId(change.recordId());
        if (id == null) {
            return blockedPlan(index, Operation.UPDATE_GALLERY.apiValue, product.sku(),
                    Operation.UPDATE_GALLERY, "gallery", null,
                    message("GALLERY_RECORD_NOT_FOUND", "recordId must contain a valid dbo.img_prod.id."),
                    s3.matches(), null, new Value(filename, change.sortOrder()), s3.warnings(), List.of(
                            message("GALLERY_RECORD_NOT_FOUND", "recordId must contain a valid dbo.img_prod.id.")));
        }
        VirtualGallery row = context.gallery().stream()
                .filter(candidate -> candidate.id == id)
                .findFirst()
                .orElse(null);
        RecordId recordId = new RecordId("img_prod", String.valueOf(id));
        if (row == null || row.plusArtic != product.plusArtic()) {
            return blockedPlan(index, Operation.UPDATE_GALLERY.apiValue, product.sku(),
                    Operation.UPDATE_GALLERY, "gallery", recordId,
                    message("GALLERY_RECORD_NOT_FOUND", "Gallery row does not exist for this SKU."),
                    s3.matches(), null, new Value(filename, change.sortOrder()), s3.warnings(), List.of(
                            message("GALLERY_RECORD_NOT_FOUND", "Gallery row does not exist for this SKU.")));
        }

        int targetSortOrder = change.sortOrder() == null
                ? defaultSortOrder(row.sortOrder) : change.sortOrder();
        Value before = new Value(row.filename, row.sortOrder);
        Value after = new Value(filename, targetSortOrder);
        if (Objects.equals(row.filename, filename)
                && Objects.equals(row.sortOrder, targetSortOrder)) {
            return planResult(index, Operation.UPDATE_GALLERY, product.sku(), product.plusArtic(), id,
                    row.filename, row.sortOrder, filename, targetSortOrder,
                    result(index, Operation.UPDATE_GALLERY.apiValue, "noop", "gallery", product.sku(),
                            recordId, before, after, s3));
        }
        if (!Objects.equals(row.filename, change.expectedOldFilename())
                || !Objects.equals(row.sortOrder, change.expectedOldSortOrder())) {
            ApiMessage error = oldValueChanged(
                    change.expectedOldFilename(), change.expectedOldSortOrder(), row.filename, row.sortOrder);
            return blockedPlan(index, Operation.UPDATE_GALLERY.apiValue, product.sku(),
                    Operation.UPDATE_GALLERY, "gallery", recordId, error,
                    s3.matches(), before, after, s3.warnings(), List.of(error));
        }
        boolean duplicate = context.gallery().stream()
                .anyMatch(candidate -> candidate.id != id
                        && equalsFilename(candidate.filename, filename));
        if (duplicate) {
            ApiMessage error = message("DUPLICATE_GALLERY_ITEM",
                    "The same filename is already present in this gallery.", Map.of("filename", filename));
            return blockedPlan(index, Operation.UPDATE_GALLERY.apiValue, product.sku(),
                    Operation.UPDATE_GALLERY, "gallery", recordId, error,
                    s3.matches(), before, after, s3.warnings(), List.of(error));
        }

        String oldFilename = row.filename;
        Integer oldSortOrder = row.sortOrder;
        row.filename = filename;
        row.sortOrder = targetSortOrder;
        return planResult(index, Operation.UPDATE_GALLERY, product.sku(), product.plusArtic(), id,
                oldFilename, oldSortOrder, filename, targetSortOrder,
                result(index, Operation.UPDATE_GALLERY.apiValue, "ready", "gallery", product.sku(),
                        recordId, before, after, s3));
    }

    private Plan planAddGallery(int index,
                                Change change,
                                PlanningContext context,
                                ProductRow product,
                                String filename,
                                S3Check s3) {
        boolean duplicate = context.gallery().stream()
                .anyMatch(candidate -> equalsFilename(candidate.filename, filename));
        if (duplicate) {
            ApiMessage error = message("DUPLICATE_GALLERY_ITEM",
                    "The same filename is already present in this gallery.", Map.of("filename", filename));
            return blockedPlan(index, Operation.ADD_GALLERY.apiValue, product.sku(),
                    Operation.ADD_GALLERY, "gallery", null, error,
                    s3.matches(), null, new Value(filename, change.sortOrder()), s3.warnings(), List.of(error));
        }

        int sortOrder = change.sortOrder() == null ? context.nextSortOrder() : change.sortOrder();
        context.gallery().add(new VirtualGallery(context.nextTemporaryId--,
                product.plusArtic(), filename, sortOrder));
        RecordId recordId = new RecordId("img_prod", "new");
        return planResult(index, Operation.ADD_GALLERY, product.sku(), product.plusArtic(), null,
                null, null, filename, sortOrder,
                result(index, Operation.ADD_GALLERY.apiValue, "ready", "gallery", product.sku(),
                        recordId, null, new Value(filename, sortOrder), s3));
    }

    private FolioProductMediaSearchResponse.Item toSearchItem(
            MediaRow row,
            String searchedFilename,
            String match
    ) {
        String matchType = searchedFilename == null ? "exact"
                : exactFilename(row.filename(), searchedFilename) ? "exact" : match;
        S3Check s3 = checkS3(basename(row.filename()), null, false);
        String table = "main".equals(row.role()) ? "ALL_ARTC" : "img_prod";
        return new FolioProductMediaSearchResponse.Item(
                row.role(),
                row.sku(),
                row.productName(),
                row.filename(),
                matchType,
                row.plusArtic(),
                row.sortOrder(),
                new RecordId(table, row.recordKey()),
                new FolioProductMediaSearchResponse.S3State(!s3.matches().isEmpty(), s3.matches()),
                s3.warnings(),
                s3.errors()
        );
    }

    private S3Check checkS3(String filename,
                            FolioProductMediaChangeRequest.S3Proof proof,
                            boolean required) {
        String basename = basename(filename);
        if (basename == null || basename.isBlank()) {
            return required
                    ? new S3Check(List.of(), List.of(), List.of(message(
                    "S3_FILE_NOT_INDEXED", "Target filename is empty.")))
                    : new S3Check(List.of(), List.of(), List.of());
        }
        List<S3MediaIndexDao.Row> rows = s3Index.findByFileName(basename.toLowerCase(Locale.ROOT));
        List<S3Match> matches = rows.stream().map(row -> new S3Match(
                row.fullKey(), row.sizeBytes(), row.etag(), row.lastModified()
        )).toList();
        List<ApiMessage> warnings = new ArrayList<>();
        List<ApiMessage> errors = new ArrayList<>();
        if (rows.isEmpty()) {
            if (required) {
                errors.add(message("S3_FILE_NOT_INDEXED",
                        "Exact target filename is not present in s3_media_index.",
                        Map.of("filename", basename)));
            }
            return new S3Check(matches, warnings, errors);
        }

        Set<String> contentSignatures = rows.stream()
                .map(row -> row.sizeBytes() + "|" + normalizeEtag(row.etag()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (contentSignatures.size() > 1) {
            errors.add(message("S3_FILENAME_CONFLICT",
                    "Same basename points to S3 objects with different content.",
                    Map.of("filename", basename, "matches", rows.size())));
        } else if (rows.size() > 1) {
            warnings.add(message("IDENTICAL_S3_DUPLICATES",
                    "Several OVH/S3 objects have the same basename and identical content."));
        }

        if (proof != null) {
            boolean proofComplete = trimToNull(proof.fullKey()) != null
                    && proof.sizeBytes() != null
                    && trimToNull(proof.etag()) != null;
            boolean proofMatches = proofComplete && rows.stream().anyMatch(row ->
                    Objects.equals(row.fullKey(), proof.fullKey())
                            && row.sizeBytes() == proof.sizeBytes()
                            && Objects.equals(normalizeEtag(row.etag()), normalizeEtag(proof.etag())));
            if (!proofMatches) {
                errors.add(message("S3_PROOF_CHANGED",
                        "S3 key, size or ETag differs from the supplied proof."));
            }
        }
        return new S3Check(matches, warnings, errors);
    }

    private FolioProductMediaChangeResponse replayIfPresent(String requestId,
                                                             String requestHash,
                                                             int requested) {
        FolioProductMediaRequestDao.StoredRequest stored = requests.find(requestId);
        if (stored == null) {
            return null;
        }
        if (!Objects.equals(stored.requestHash(), requestHash)) {
            return invalidChangeResponse(requestId, false, requested,
                    message("IDEMPOTENCY_KEY_REUSED",
                            "externalRequestId was already used with different request content."));
        }
        try {
            return objectMapper.readValue(stored.responseJson(), FolioProductMediaChangeResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read stored Folio product media response", e);
        }
    }

    private FolioProductMediaChangeResponse saveAndResolveRace(
            String requestId,
            String requestHash,
            FolioProductMediaChangeResponse response,
            int requested
    ) {
        try {
            requests.save(requestId, requestHash, objectMapper.writeValueAsString(response));
            return response;
        } catch (DuplicateKeyException duplicate) {
            FolioProductMediaChangeResponse replay = replayIfPresent(requestId, requestHash, requested);
            return replay == null ? response : replay;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot store Folio product media response", e);
        }
    }

    private String requestHash(FolioProductMediaChangeRequest request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot calculate Folio product media request hash", e);
        }
    }

    private FolioProductMediaChangeResponse response(boolean previewOnly,
                                                      String externalRequestId,
                                                      List<Result> results,
                                                      List<ApiMessage> warnings,
                                                      List<ApiMessage> errors) {
        int ready = countStatus(results, "ready");
        int noop = countStatus(results, "noop");
        int blocked = countStatus(results, "blocked");
        int applied = countStatus(results, "applied");
        return new FolioProductMediaChangeResponse(
                blocked == 0 && errors.isEmpty(),
                previewOnly,
                externalRequestId,
                new FolioProductMediaChangeResponse.Summary(
                        results.size(), ready, noop, blocked, applied),
                results,
                warnings,
                errors
        );
    }

    private FolioProductMediaChangeResponse invalidChangeResponse(String externalRequestId,
                                                                   boolean previewOnly,
                                                                   int requested,
                                                                   ApiMessage error) {
        return new FolioProductMediaChangeResponse(
                false,
                previewOnly,
                externalRequestId,
                new FolioProductMediaChangeResponse.Summary(requested, 0, 0, requested, 0),
                List.of(),
                List.of(),
                List.of(error)
        );
    }

    private static int countStatus(List<Result> results, String status) {
        return (int) results.stream().filter(result -> status.equals(result.status())).count();
    }

    private static Result result(int index,
                                 String operation,
                                 String status,
                                 String role,
                                 String sku,
                                 RecordId recordId,
                                 Value before,
                                 Value after,
                                 S3Check s3) {
        return new Result(index, operation, status, role, sku, recordId, before, after,
                s3.matches(), s3.warnings(), s3.errors());
    }

    private static Plan planResult(int index,
                                   Operation operation,
                                   String sku,
                                   long plusArtic,
                                   Integer galleryId,
                                   String beforeFilename,
                                   Integer beforeSortOrder,
                                   String afterFilename,
                                   Integer afterSortOrder,
                                   Result result) {
        return new Plan(index, operation, sku, plusArtic, galleryId,
                beforeFilename, beforeSortOrder, afterFilename, afterSortOrder, result);
    }

    private static Plan blockedPlan(int index,
                                    String operationValue,
                                    String sku,
                                    Operation operation,
                                    String role,
                                    RecordId recordId,
                                    ApiMessage error,
                                    List<S3Match> s3Matches,
                                    Value before,
                                    Value after) {
        return blockedPlan(index, operationValue, sku, operation, role, recordId,
                error, s3Matches, before, after, List.of(), List.of(error));
    }

    private static Plan blockedPlan(int index,
                                    String operationValue,
                                    String sku,
                                    Operation operation,
                                    String role,
                                    RecordId recordId,
                                    ApiMessage error,
                                    List<S3Match> s3Matches,
                                    Value before,
                                    Value after,
                                    List<ApiMessage> warnings,
                                    List<ApiMessage> errors) {
        String apiOperation = operation == null ? operationValue : operation.apiValue;
        Result result = new Result(index, apiOperation, "blocked", role, sku, recordId,
                before, after, s3Matches, warnings, errors.isEmpty() ? List.of(error) : errors);
        return new Plan(index, operation, sku, 0, null,
                before == null ? null : before.filename(), before == null ? null : before.sortOrder(),
                after == null ? null : after.filename(), after == null ? null : after.sortOrder(), result);
    }

    private static Result copyResult(Result source,
                                     String status,
                                     RecordId recordId,
                                     List<ApiMessage> errors) {
        return new Result(source.index(), source.operation(), status, source.role(), source.sku(),
                recordId, source.before(), source.after(), source.s3Matches(), source.warnings(), errors);
    }

    private void logResult(String requestId, Result result) {
        log.info("[folio.media] requestId={} operation={} sku={} role={} before={} after={} result={}",
                requestId, result.operation(), result.sku(), result.role(),
                result.before(), result.after(), result.status());
    }

    private static ApiMessage oldValueChanged(String expectedFilename,
                                              Integer expectedSortOrder,
                                              String actualFilename,
                                              Integer actualSortOrder) {
        Map<String, Object> details = new LinkedHashMap<>();
        putNullable(details, "expectedOldFilename", expectedFilename);
        putNullable(details, "expectedOldSortOrder", expectedSortOrder);
        putNullable(details, "actualFilename", actualFilename);
        putNullable(details, "actualSortOrder", actualSortOrder);
        return message("OLD_VALUE_CHANGED",
                "Folio media value changed after preview; preview again before applying.", details);
    }

    private static FilenameValidation validateFilename(String value) {
        String filename = trimToNull(value);
        if (filename == null) {
            return new FilenameValidation(null,
                    message("INVALID_FILENAME", "filename is required."));
        }
        String suggestion = basename(filename);
        boolean pathOrUrl = filename.contains("/") || filename.contains("\\")
                || filename.contains("://");
        boolean invalid = pathOrUrl || ".".equals(filename) || "..".equals(filename)
                || filename.chars().anyMatch(Character::isISOControl);
        if (invalid) {
            Map<String, Object> details = new LinkedHashMap<>();
            if (suggestion != null && !suggestion.isBlank()) {
                details.put("normalizedSuggestion", suggestion);
            }
            return new FilenameValidation(filename,
                    message("INVALID_FILENAME", "Only a basename may be written to Folio.", details));
        }
        return new FilenameValidation(filename, null);
    }

    private static boolean filenameMatches(String stored, String searched, String match) {
        if ("exact".equals(match)) {
            return exactFilename(stored, searched);
        }
        return Objects.equals(normalizeFilename(stored), normalizeFilename(searched));
    }

    private static boolean exactFilename(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean equalsFilename(String left, String right) {
        String leftBase = basename(left);
        String rightBase = basename(right);
        return leftBase != null && rightBase != null && leftBase.equalsIgnoreCase(rightBase);
    }

    static String normalizeFilename(String value) {
        String base = basename(value);
        if (base == null) {
            return null;
        }
        String normalized = Normalizer.normalize(base, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\u2010', '-')
                .replace('\u2011', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2212', '-')
                .replace('_', '-');
        normalized = normalized.replaceAll("\\s+", "-");
        return normalized.replaceAll("-+", "-");
    }

    private static String basename(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String path = trimmed.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static Integer parseRecordId(String recordId) {
        try {
            int value = Integer.parseInt(defaultValue(trimToNull(recordId), ""));
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int defaultSortOrder(Integer sortOrder) {
        return sortOrder == null ? 0 : sortOrder;
    }

    private static String normalizeEtag(String etag) {
        String value = trimToNull(etag);
        if (value == null) {
            return "";
        }
        if (value.regionMatches(true, 0, "W/", 0, 2)) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static ApiMessage message(String code, String text) {
        return new ApiMessage(code, text);
    }

    private static ApiMessage message(String code, String text, Map<String, Object> details) {
        return new ApiMessage(code, text, details);
    }

    private static void putNullable(Map<String, Object> target, String key, Object value) {
        target.put(key, value == null ? "<null>" : value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private record IndexedChange(int index, Change change) {
    }

    private record S3Check(
            List<S3Match> matches,
            List<ApiMessage> warnings,
            List<ApiMessage> errors
    ) {
    }

    private record FilenameValidation(String filename, ApiMessage error) {
    }

    private record Plan(
            int index,
            Operation operation,
            String sku,
            long plusArtic,
            Integer galleryId,
            String beforeFilename,
            Integer beforeSortOrder,
            String afterFilename,
            Integer afterSortOrder,
            Result result
    ) {
    }

    private static final class VirtualGallery {
        private final int id;
        private final long plusArtic;
        private String filename;
        private Integer sortOrder;

        private VirtualGallery(int id, long plusArtic, String filename, Integer sortOrder) {
            this.id = id;
            this.plusArtic = plusArtic;
            this.filename = filename;
            this.sortOrder = sortOrder;
        }
    }

    private final class PlanningContext {
        private final ProductRow product;
        private final boolean forUpdate;
        private String mainFilename;
        private List<VirtualGallery> gallery;
        private int nextTemporaryId = -1;

        private PlanningContext(String sku, boolean forUpdate) {
            this.forUpdate = forUpdate;
            this.product = sku == null ? null : folio.findProduct(sku, forUpdate);
            this.mainFilename = product == null ? null : product.mainFilename();
        }

        private ProductRow product() {
            return product;
        }

        private List<VirtualGallery> gallery() {
            if (gallery == null) {
                if (product == null) {
                    gallery = new ArrayList<>();
                } else {
                    gallery = folio.findGalleryByPlusArtic(product.plusArtic(), forUpdate).stream()
                            .map(row -> new VirtualGallery(
                                    row.id(), row.plusArtic(), row.filename(), row.sortOrder()))
                            .collect(Collectors.toCollection(ArrayList::new));
                }
            }
            return gallery;
        }

        private int nextSortOrder() {
            return gallery().stream()
                    .map(row -> row.sortOrder)
                    .filter(Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElse(0) + 1;
        }
    }

    private enum Operation {
        SET_MAIN("set_main"),
        UPDATE_GALLERY("update_gallery"),
        ADD_GALLERY("add_gallery");

        private final String apiValue;

        Operation(String apiValue) {
            this.apiValue = apiValue;
        }

        private static Operation from(String value) {
            String normalized = trimToNull(value);
            if (normalized == null) {
                return null;
            }
            for (Operation operation : values()) {
                if (operation.apiValue.equalsIgnoreCase(normalized)) {
                    return operation;
                }
            }
            return null;
        }
    }
}
