package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioAccountDao;
import org.example.proect.lavka.dto.folio.CreateFolioAccountItemRequest;
import org.example.proect.lavka.dto.folio.CreateFolioAccountRequest;
import org.example.proect.lavka.dto.folio.FolioAccountResponse;
import org.example.proect.lavka.dto.folio.FolioOrderAccountRequest;
import org.example.proect.lavka.dto.folio.FolioOrderAccountResponse;
import org.example.proect.lavka.property.FolioAccountProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class FolioOrderAccountService {

    private static final String SUPPORTED_SCHEMA = "folio-order-preview/v1";

    private final FolioAccountService accountService;
    private final FolioAccountDao accountDao;
    private final FolioAccountProperties properties;

    public FolioOrderAccountService(FolioAccountService accountService,
                                    FolioAccountDao accountDao,
                                    FolioAccountProperties properties) {
        this.accountService = accountService;
        this.accountDao = accountDao;
        this.properties = properties;
    }

    @Transactional(transactionManager = "mssqlTransactionManager", isolation = Isolation.SERIALIZABLE)
    public FolioOrderAccountResponse createFromWooOrder(FolioOrderAccountRequest request) {
        boolean previewOnly = Boolean.TRUE.equals(request.previewOnly());
        validateRequest(request);

        FolioOrderAccountRequest.Header header = request.folioAccountHeader();
        OrderMode orderMode = orderMode(request);
        boolean accountingEnabled = orderMode.accountingEnabled();
        AllocationResult allocation = accountingEnabled
                ? allocateAccounting(request)
                : allocateSingleNonAccounting(request, "non_accounting");

        List<FolioOrderAccountResponse.Document> documents = new ArrayList<>();
        List<FolioOrderAccountResponse.ApiMessage> warnings = new ArrayList<>(allocation.warnings());

        for (var group : allocation.accountingGroups().values()) {
            documents.add(previewOnly
                    ? previewDocument(group, header, accountingEnabled, orderMode.documentType())
                    : createDocument(request, group, accountingEnabled, orderMode.documentType()));
        }

        if (!allocation.missingItems().isEmpty()) {
            DocumentGroup missingGroup = new DocumentGroup(
                    selectMissingWarehouse(request).orElseThrow(() -> new FolioAccountValidationException(
                            "missing_warehouse_unknown",
                            "Cannot create non-accounting missing-stock account: no Folio warehouse candidate found"
                    )),
                    request.folioAccountHeader().externalRequestId() + ":missing",
                    "missing_stock",
                    allocation.missingItems()
            );
            documents.add(previewOnly
                    ? previewDocument(missingGroup, header, false, "missing_stock_account")
                    : createDocument(request, missingGroup, false, "missing_stock_account"));
        }

        return new FolioOrderAccountResponse(
                documents.stream().noneMatch(d -> "failed".equals(d.documentStatus())),
                previewOnly,
                request.wooOrder().id(),
                documents,
                warnings,
                List.of()
        );
    }

    private AllocationResult allocateAccounting(FolioOrderAccountRequest request) {
        Map<Integer, DocumentGroup> groups = new LinkedHashMap<>();
        List<AllocatedItem> missing = new ArrayList<>();
        List<FolioOrderAccountResponse.ApiMessage> warnings = new ArrayList<>();
        Map<String, BigDecimal> availableCache = new LinkedHashMap<>();

        for (var item : request.items()) {
            String sku = item.sku().trim();
            for (var allocation : item.allocations()) {
                BigDecimal remaining = allocation.quantity();
                List<FolioOrderAccountRequest.WarehouseCandidate> candidates = sortedWarehouses(allocation.folioWarehouses());

                for (var candidate : candidates) {
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                    int warehouseId = parseWarehouseId(candidate.id(), sku);
                    String stockKey = sku + "\u0000" + warehouseId;
                    BigDecimal available = availableCache.computeIfAbsent(stockKey,
                            k -> lockOrReadStock(sku, warehouseId).map(FolioAccountDao.StockRow::freeQuantity).orElse(BigDecimal.ZERO));
                    if (available.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }

                    BigDecimal quantity = available.min(remaining);
                    availableCache.put(stockKey, available.subtract(quantity));
                    remaining = remaining.subtract(quantity);

                    String externalId = request.folioAccountHeader().externalRequestId() + ":wh:" + warehouseId;
                    groups.computeIfAbsent(warehouseId, id -> new DocumentGroup(id, externalId, "created", new ArrayList<>()))
                            .items()
                            .add(new AllocatedItem(item.orderItemId(), sku, quantity, item.unitPrice(), warehouseId, "allocated"));
                }

                if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                    Integer missingWarehouse = candidates.isEmpty() ? null : parseWarehouseId(candidates.get(0).id(), sku);
                    missing.add(new AllocatedItem(item.orderItemId(), sku, remaining, item.unitPrice(), missingWarehouse, "missing_stock"));
                    warnings.add(new FolioOrderAccountResponse.ApiMessage(
                            "INSUFFICIENT_AVAILABLE_STOCK",
                            "Not enough free stock for " + sku + "; remainder will be written to a non-accounting account",
                            details(
                                    "sku", sku,
                                    "orderItemId", item.orderItemId(),
                                    "missingQuantity", remaining
                            )
                    ));
                }
            }
        }

        return new AllocationResult(groups, mergeItems(missing), warnings);
    }

    private AllocationResult allocateSingleNonAccounting(FolioOrderAccountRequest request, String status) {
        int warehouseId = request.folioAccountHeader().warehouseId() == null
                ? selectMissingWarehouse(request).orElseThrow(() -> new FolioAccountValidationException(
                        "warehouse_unknown",
                        "Cannot create non-accounting account: no header warehouseId and no allocation warehouse candidates"
                ))
                : request.folioAccountHeader().warehouseId();
        List<AllocatedItem> items = new ArrayList<>();
        for (var item : request.items()) {
            items.add(new AllocatedItem(item.orderItemId(), item.sku().trim(), item.quantity(), item.unitPrice(), warehouseId, status));
        }
        Map<Integer, DocumentGroup> groups = new LinkedHashMap<>();
        groups.put(warehouseId, new DocumentGroup(
                warehouseId,
                request.folioAccountHeader().externalRequestId() + ":wh:" + warehouseId + ":nonaccounting",
                status,
                mergeItems(items)
        ));
        return new AllocationResult(groups, List.of(), List.of());
    }

    private FolioOrderAccountResponse.Document createDocument(FolioOrderAccountRequest source,
                                                             DocumentGroup group,
                                                             boolean accountingEnabled,
                                                             String documentType) {
        FolioOrderAccountRequest.Header base = source.folioAccountHeader();
        List<AllocatedItem> mergedItems = mergeItems(group.items());
        CreateFolioAccountRequest request = new CreateFolioAccountRequest(
                group.externalRequestId(),
                documentNumberOrAllocated(base.documentNumber()),
                base.documentDate(),
                group.warehouseId(),
                base.operationType(),
                base.partnerId(),
                truncate(base.comment(), 5),
                base.controlDate(),
                truncate(base.folioOperationKind(), 20),
                truncate(base.payerName(), 50),
                truncate(base.receiverName(), 50),
                truncate(base.payerShortName(), 8),
                truncate(base.folioUser(), 20),
                truncate(base.sourceInfo(), 30),
                truncate(missingAdditionalInfo(base.additionalInfo(), group, accountingEnabled), 30),
                truncate(base.priceContractType(), 10),
                base.notCash(),
                accountingEnabled,
                base.returnFlag(),
                mergedItems.stream()
                        .map(i -> BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                mergedItems.stream()
                        .map(AllocatedItem::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                truncate(base.payerCity(), 28),
                truncate(base.directorName(), 75),
                truncate(base.accountantName(), 75),
                truncate(base.payerPhone(), 20),
                truncate(base.deliveryInfo(), 150),
                mergedItems.stream()
                        .map(i -> new CreateFolioAccountItemRequest(
                                i.sku(),
                                i.quantity(),
                                i.price(),
                                null,
                                null,
                                i.amount()
                        ))
                        .toList()
        );

        FolioAccountResponse account = accountService.create(request);
        return new FolioOrderAccountResponse.Document(
                account.documentId(),
                account.documentNumber(),
                documentType,
                group.status(),
                group.warehouseId(),
                accountingEnabled,
                group.externalRequestId(),
                account.createdDate(),
                toResponseItems(mergedItems)
        );
    }

    private FolioOrderAccountResponse.Document previewDocument(DocumentGroup group,
                                                               FolioOrderAccountRequest.Header header,
                                                               boolean accountingEnabled,
                                                               String documentType) {
        List<AllocatedItem> mergedItems = mergeItems(group.items());
        return new FolioOrderAccountResponse.Document(
                null,
                documentNumberOrPreview(header.documentNumber()),
                documentType,
                "preview",
                group.warehouseId(),
                accountingEnabled,
                group.externalRequestId(),
                LocalDateTime.now(),
                toResponseItems(mergedItems)
        );
    }

    private List<FolioOrderAccountResponse.Item> toResponseItems(List<AllocatedItem> items) {
        return items.stream()
                .map(i -> new FolioOrderAccountResponse.Item(
                        i.orderItemId(),
                        i.sku(),
                        i.quantity(),
                        i.price(),
                        i.amount(),
                        i.warehouseId(),
                        i.status()
                ))
                .toList();
    }

    private List<AllocatedItem> mergeItems(List<AllocatedItem> source) {
        Map<String, AllocatedItem> merged = new LinkedHashMap<>();
        for (var item : source) {
            String key = item.warehouseId() + "\u0000" + item.sku();
            AllocatedItem existing = merged.get(key);
            if (existing == null) {
                merged.put(key, item);
                continue;
            }
            if (existing.price().compareTo(item.price()) != 0) {
                throw new FolioAccountValidationException("duplicate_sku_different_price",
                        "Cannot merge duplicate SKU with different prices in one Folio account: " + item.sku());
            }
            merged.put(key, new AllocatedItem(
                    existing.orderItemId(),
                    existing.sku(),
                    existing.quantity().add(item.quantity()),
                    existing.price(),
                    existing.warehouseId(),
                    existing.status()
            ));
        }
        return new ArrayList<>(merged.values());
    }

    private Optional<FolioAccountDao.StockRow> lockOrReadStock(String sku, int warehouseId) {
        return accountDao.lockStock(sku, warehouseId);
    }

    private List<FolioOrderAccountRequest.WarehouseCandidate> sortedWarehouses(
            List<FolioOrderAccountRequest.WarehouseCandidate> warehouses) {
        return warehouses.stream()
                .sorted(Comparator
                        .comparing((FolioOrderAccountRequest.WarehouseCandidate w) ->
                                w.priority() == null ? Integer.MAX_VALUE : w.priority())
                        .thenComparing(FolioOrderAccountRequest.WarehouseCandidate::id))
                .toList();
    }

    private Optional<Integer> selectMissingWarehouse(FolioOrderAccountRequest request) {
        if (request.folioAccountHeader().warehouseId() != null) {
            return Optional.of(request.folioAccountHeader().warehouseId());
        }
        return request.items().stream()
                .flatMap(i -> i.allocations().stream())
                .flatMap(a -> a.folioWarehouses().stream())
                .sorted(Comparator
                        .comparing((FolioOrderAccountRequest.WarehouseCandidate w) ->
                                w.priority() == null ? Integer.MAX_VALUE : w.priority())
                        .thenComparing(FolioOrderAccountRequest.WarehouseCandidate::id))
                .map(w -> parseWarehouseId(w.id(), ""))
                .findFirst();
    }

    private OrderMode orderMode(FolioOrderAccountRequest request) {
        String status = normalize(request.wooOrder().status());
        if ("processing".equals(status)) {
            return new OrderMode(true, "account");
        }
        if ("pc-draft".equals(status)) {
            return new OrderMode(false, "non_accounting_account");
        }
        if ("completed".equals(status)) {
            throw new FolioAccountValidationException(
                    "unsupported_woo_order_status",
                    "Woo status completed is расходная накладная, not account. This endpoint creates only Folio accounts."
            );
        }
        return new OrderMode(valueOrDefault(request.folioAccountHeader().accountingEnabled(), true), "account");
    }

    private int parseWarehouseId(String value, String sku) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new FolioAccountValidationException("warehouse_id_not_numeric",
                    "Folio warehouse id must be numeric: " + value + (sku == null || sku.isBlank() ? "" : " for " + sku));
        }
    }

    private String documentNumberOrAllocated(String documentNumber) {
        if (documentNumber != null && !documentNumber.trim().isEmpty()) {
            return documentNumber.trim();
        }
        return accountDao.nextVisibleDocumentNumber(properties.getTypeDoc()).stripTrailingZeros().toPlainString();
    }

    private String documentNumberOrPreview(String documentNumber) {
        if (documentNumber != null && !documentNumber.trim().isEmpty()) {
            return documentNumber.trim();
        }
        return "AUTO";
    }

    private String missingAdditionalInfo(String value, DocumentGroup group, boolean accountingEnabled) {
        if (accountingEnabled) {
            return value;
        }
        String marker = "Нет на складе";
        if (value == null || value.trim().isEmpty()) {
            return marker;
        }
        String result = value.trim() + " / " + marker;
        return result.length() > 30 ? result.substring(0, 30) : result;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private Map<String, Object> details(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return result;
    }

    private boolean valueOrDefault(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private void validateRequest(FolioOrderAccountRequest request) {
        if (!SUPPORTED_SCHEMA.equals(request.schemaVersion())) {
            throw new FolioAccountValidationException("unsupported_schema_version",
                    "Unsupported Folio order schema: " + request.schemaVersion());
        }
        FolioOrderAccountRequest.Header header = request.folioAccountHeader();
        requireText(header.externalRequestId(), "folio_account_header.externalRequestId");
        requireText(header.operationType(), "folio_account_header.operationType");
        requireText(header.folioOperationKind(), "folio_account_header.folioOperationKind");
        requireText(header.payerName(), "folio_account_header.payerName");
        requireText(header.receiverName(), "folio_account_header.receiverName");
        requireText(header.payerShortName(), "folio_account_header.payerShortName");
        requireText(header.folioUser(), "folio_account_header.folioUser");
        requireText(header.sourceInfo(), "folio_account_header.sourceInfo");
        requireText(header.additionalInfo(), "folio_account_header.additionalInfo");
        if (header.documentDate() == null) {
            throw new FolioAccountValidationException("missing_document_date", "Missing folio_account_header.documentDate");
        }
        if (header.controlDate() == null) {
            throw new FolioAccountValidationException("missing_control_date", "Missing folio_account_header.controlDate");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new FolioAccountValidationException("missing_required_field", "Missing required field: " + field);
        }
    }

    private record AllocationResult(Map<Integer, DocumentGroup> accountingGroups,
                                    List<AllocatedItem> missingItems,
                                    List<FolioOrderAccountResponse.ApiMessage> warnings) {
    }

    private record OrderMode(boolean accountingEnabled,
                             String documentType) {
    }

    private record DocumentGroup(int warehouseId,
                                 String externalRequestId,
                                 String status,
                                 List<AllocatedItem> items) {
    }

    private record AllocatedItem(Long orderItemId,
                                 String sku,
                                 BigDecimal quantity,
                                 BigDecimal price,
                                 Integer warehouseId,
                                 String status) {
        BigDecimal amount() {
            return price.multiply(quantity);
        }
    }
}
