package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioAccountDao;
import org.example.proect.lavka.dto.folio.AddFolioAccountItemRequest;
import org.example.proect.lavka.dto.folio.CreateFolioAccountItemRequest;
import org.example.proect.lavka.dto.folio.CreateFolioAccountRequest;
import org.example.proect.lavka.dto.folio.FolioAccountResponse;
import org.example.proect.lavka.dto.folio.FolioAccountSummaryResponse;
import org.example.proect.lavka.dto.folio.UpdateFolioAccountItemQuantityRequest;
import org.example.proect.lavka.property.FolioAccountProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class FolioAccountService {

    private static final Pattern FOLIO_NUMERIC_DOCUMENT_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private final FolioAccountDao dao;
    private final FolioNumberAllocator allocator;
    private final FolioAccountProperties properties;

    public FolioAccountService(FolioAccountDao dao,
                               FolioNumberAllocator allocator,
                               FolioAccountProperties properties) {
        this.dao = dao;
        this.allocator = allocator;
        this.properties = properties;
    }

    @Transactional(transactionManager = "mssqlTransactionManager", isolation = Isolation.SERIALIZABLE)
    public FolioAccountResponse create(CreateFolioAccountRequest request) {
        validateOperationType(request.operationType());
        validateCreateItems(request.items());

        var existing = dao.findDocumentIdByExternalRequestId(request.externalRequestId().trim());
        if (existing.isPresent()) {
            return get(existing.get());
        }

        if (!dao.warehouseExists(request.warehouseId())) {
            throw new FolioAccountConflictException("warehouse_not_found",
                    "Warehouse not found: " + request.warehouseId());
        }

        String folioOperationKind = valueOrDefault(request.folioOperationKind(), properties.getMovementVidDoc());
        boolean accountingEnabled = valueOrDefault(request.accountingEnabled(), true);
        boolean notCash = valueOrDefault(request.notCash(), true);
        boolean returnFlag = valueOrDefault(request.returnFlag(), false);

        if (accountingEnabled) {
            for (var item : request.items()) {
                assertStockAvailable(item.sku().trim(), request.warehouseId(), item.quantity(), false);
            }
        }

        long documentId = allocator.nextDocumentId();
        BigDecimal total = request.items().stream()
                .map(i -> i.price().multiply(i.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal folioDocumentNumber = parseFolioDocumentNumber(request.documentNumber());
        LocalDateTime folioDocumentDate = request.documentDate().toLocalDate().atStartOfDay();

        BigDecimal retailAmount = request.retailAmount() == null ? total : request.retailAmount();
        BigDecimal currencyAmount = request.currencyAmount() == null ? BigDecimal.ZERO : request.currencyAmount();
        LocalDateTime createdDate = LocalDateTime.now();

        dao.insertHeader(new FolioAccountDao.HeaderWrite(
                documentId,
                folioDocumentNumber,
                folioDocumentDate,
                total,
                request.comment(),
                properties.getTypeDoc(),
                accountingEnabled,
                properties.getTaxName(),
                properties.getTaxPercent(),
                blankToNull(request.sourceInfo()),
                blankToNull(request.additionalInfo()),
                properties.getPaymentFlag(),
                blankToNull(request.priceContractType()),
                notCash,
                properties.isValutaRouble(),
                properties.getCurrencyCode(),
                currencyAmount,
                properties.getSecondTaxPercent(),
                returnFlag,
                properties.getPartialPaymentFlag(),
                blankToNull(request.payerName()),
                blankToNull(request.receiverName()),
                request.controlDate(),
                blankToNull(request.folioUser()),
                retailAmount,
                properties.getMarkFlag(),
                folioOperationKind,
                blankToNull(request.payerShortName()),
                request.warehouseId(),
                createdDate,
                blankToNull(request.folioUser()),
                properties.getCashProductType(),
                properties.getTradeVatFlag()
        ));
        dao.insertAddn(new FolioAccountDao.AddnWrite(
                documentId,
                folioDocumentDate,
                blankToNull(request.payerCity()),
                blankToNull(request.directorName()),
                blankToNull(request.accountantName()),
                blankToNull(request.payerPhone()),
                blankToNull(request.sourceInfo()),
                blankToNull(request.additionalInfo()),
                blankToNull(request.deliveryInfo()),
                0
        ));

        int lineNumber = 1;
        for (var item : request.items()) {
            String sku = item.sku().trim();
            FolioAccountDao.StockRow product = accountingEnabled
                    ? lockStockOrThrow(sku, request.warehouseId(), item.quantity())
                    : dao.findStock(sku, request.warehouseId())
                            .orElseThrow(() -> new FolioAccountConflictException("stock_not_found",
                                    "Stock row not found: " + sku + " warehouse=" + request.warehouseId()));
            BigDecimal lineAmount = item.price().multiply(item.quantity());
            BigDecimal lineRetailAmount = item.retailAmount() == null ? lineAmount : item.retailAmount();
            BigDecimal lineCurrencyAmount = item.currencyAmount() == null ? BigDecimal.ZERO : item.currencyAmount();
            BigDecimal lineCurrencyPrice = item.currencyPrice() == null ? BigDecimal.ZERO : item.currencyPrice();
            if (accountingEnabled) {
                assertStockAvailable(product, item.quantity());
            }
            dao.insertLine(new FolioAccountDao.LineWrite(
                    documentId,
                    lineNumber++,
                    sku,
                    request.warehouseId(),
                    blankToNull(request.payerShortName()),
                    folioDocumentDate,
                    folioDocumentNumber,
                    request.comment(),
                    properties.getTypeDoc(),
                    accountingEnabled,
                    notCash,
                    blankToNull(request.priceContractType()),
                    lineCurrencyPrice,
                    String.valueOf(properties.getCurrencyCode()),
                    lineCurrencyAmount,
                    properties.isValutaRouble(),
                    properties.getPaymentFlag(),
                    returnFlag,
                    lineRetailAmount,
                    properties.getMarkFlag(),
                    folioOperationKind,
                    item.quantity(),
                    item.price(),
                    product.ball1(),
                    product.ball2(),
                    product.ball3(),
                    product.ball4(),
                    product.ball5()
            ));
            if (accountingEnabled) {
                reserveOrThrow(sku, request.warehouseId(), item.quantity());
            }
        }

        dao.rememberExternalRequest(request.externalRequestId().trim(), documentId);
        dao.refreshHeaderTotal(documentId);
        return get(documentId);
    }

    @Transactional(transactionManager = "mssqlTransactionManager", readOnly = true)
    public FolioAccountResponse get(long documentId) {
        return dao.findAccount(documentId, properties.getDocumentType())
                .orElseThrow(() -> new FolioAccountNotFoundException("Folio account not found: " + documentId));
    }

    @Transactional(transactionManager = "mssqlTransactionManager", readOnly = true)
    public List<FolioAccountSummaryResponse> list(LocalDate dateFrom,
                                                  LocalDate dateTo,
                                                  String payerName,
                                                  List<Integer> warehouseIds) {
        return dao.findAccounts(dateFrom, dateTo, payerName, warehouseIds,
                properties.getTypeDoc(), properties.getDocumentType());
    }

    @Transactional(transactionManager = "mssqlTransactionManager")
    public FolioAccountResponse updateQuantity(long documentId, long recno, UpdateFolioAccountItemQuantityRequest request) {
        var line = dao.lockLine(documentId, recno)
                .orElseThrow(() -> new FolioAccountNotFoundException("Folio account line not found: " + recno));
        ensureLineIsActive(line);

        BigDecimal delta = request.quantity().subtract(line.quantity());
        if (delta.compareTo(BigDecimal.ZERO) > 0) {
            assertStockAvailable(line.sku(), line.warehouseId(), delta, true);
            reserveOrThrow(line.sku(), line.warehouseId(), delta);
        } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
            dao.release(line.sku(), line.warehouseId(), delta.abs());
        }

        dao.updateLineQuantity(documentId, recno, request.quantity());
        dao.refreshHeaderTotal(documentId);
        return get(documentId);
    }

    @Transactional(transactionManager = "mssqlTransactionManager")
    public FolioAccountResponse addLine(long documentId, AddFolioAccountItemRequest request) {
        FolioAccountResponse account = get(documentId);
        if (!account.active()) {
            throw new FolioAccountConflictException("account_cancelled", "Cannot add line to cancelled account");
        }

        String sku = request.sku().trim();
        int warehouseId = account.warehouseId() == null ? 0 : account.warehouseId();
        if (warehouseId == 0) {
            throw new FolioAccountConflictException("warehouse_unknown", "Cannot detect account warehouse");
        }

        boolean skuAlreadyInDocument = account.items().stream()
                .anyMatch(i -> i.sku().equalsIgnoreCase(sku));
        if (skuAlreadyInDocument) {
            throw new FolioAccountConflictException("duplicate_sku", "SKU already exists in account: " + sku);
        }

        assertStockAvailable(sku, warehouseId, request.quantity(), true);
        int lineNumber = dao.nextLineNumber(documentId);
        var product = lockStockOrThrow(sku, warehouseId, request.quantity());
        dao.insertLine(new FolioAccountDao.LineWrite(
                documentId,
                lineNumber,
                sku,
                warehouseId,
                null,
                account.documentDate(),
                parseFolioDocumentNumber(account.documentNumber()),
                null,
                properties.getTypeDoc(),
                true,
                true,
                null,
                BigDecimal.ZERO,
                String.valueOf(properties.getCurrencyCode()),
                BigDecimal.ZERO,
                properties.isValutaRouble(),
                properties.getPaymentFlag(),
                false,
                request.price().multiply(request.quantity()),
                properties.getMarkFlag(),
                properties.getMovementVidDoc(),
                request.quantity(),
                request.price(),
                product.ball1(),
                product.ball2(),
                product.ball3(),
                product.ball4(),
                product.ball5()
        ));
        reserveOrThrow(sku, warehouseId, request.quantity());
        dao.refreshHeaderTotal(documentId);
        return get(documentId);
    }

    @Transactional(transactionManager = "mssqlTransactionManager")
    public FolioAccountResponse deleteLine(long documentId, long recno) {
        var line = dao.lockLine(documentId, recno)
                .orElseThrow(() -> new FolioAccountNotFoundException("Folio account line not found: " + recno));
        ensureLineIsActive(line);

        dao.release(line.sku(), line.warehouseId(), line.quantity());
        dao.deleteLine(documentId, recno);
        dao.refreshHeaderTotal(documentId);
        return get(documentId);
    }

    @Transactional(transactionManager = "mssqlTransactionManager")
    public FolioAccountResponse cancel(long documentId) {
        FolioAccountResponse account = get(documentId);
        if (!account.active()) {
            return account;
        }

        List<FolioAccountDao.MoveRow> lines = dao.lockLines(documentId);
        for (var line : lines) {
            dao.lockStock(line.sku(), line.warehouseId())
                    .orElseThrow(() -> new FolioAccountConflictException("stock_not_found",
                            "Stock row not found: " + line.sku() + " warehouse=" + line.warehouseId()));
            dao.release(line.sku(), line.warehouseId(), line.quantity());
        }

        dao.cancelLines(documentId);
        dao.cancelHeader(documentId);
        dao.refreshHeaderTotal(documentId);
        return get(documentId);
    }

    private void validateCreateItems(List<CreateFolioAccountItemRequest> items) {
        Set<String> seen = new HashSet<>();
        for (var item : items) {
            String skuKey = item.sku().trim().toUpperCase(Locale.ROOT);
            if (!seen.add(skuKey)) {
                throw new FolioAccountConflictException("duplicate_sku", "Duplicate SKU in request: " + item.sku());
            }
        }
    }

    private void validateOperationType(String operationType) {
        boolean allowed = properties.getAllowedOperationTypes().stream()
                .anyMatch(t -> t.equalsIgnoreCase(operationType.trim()));
        if (!allowed) {
            throw new FolioAccountConflictException("operation_type_not_allowed",
                    "Operation type is not allowed for this API: " + operationType);
        }
    }

    private BigDecimal parseFolioDocumentNumber(String documentNumber) {
        String value = documentNumber.trim();
        if (!FOLIO_NUMERIC_DOCUMENT_NUMBER.matcher(value).matches()) {
            throw new FolioAccountValidationException("document_number_not_numeric",
                    "SCL_NAKL.N_PLAT_POR is float in Folio, documentNumber must be numeric: " + documentNumber);
        }
        return new BigDecimal(value);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private static boolean valueOrDefault(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void assertStockAvailable(String sku, int warehouseId, BigDecimal quantity, boolean lock) {
        var stock = (lock ? dao.lockStock(sku, warehouseId) : dao.findStock(sku, warehouseId))
                .orElseThrow(() -> new FolioAccountConflictException("stock_not_found",
                        "Stock row not found: " + sku + " warehouse=" + warehouseId));

        assertStockAvailable(stock, quantity);
    }

    private FolioAccountDao.StockRow lockStockOrThrow(String sku, int warehouseId, BigDecimal quantity) {
        var stock = dao.lockStock(sku, warehouseId)
                .orElseThrow(() -> new FolioAccountConflictException("stock_not_found",
                        "Stock row not found: " + sku + " warehouse=" + warehouseId));
        assertStockAvailable(stock, quantity);
        return stock;
    }

    private void assertStockAvailable(FolioAccountDao.StockRow stock, BigDecimal quantity) {
        if (stock.freeQuantity().compareTo(quantity) < 0) {
            throw new FolioAccountConflictException("insufficient_stock",
                    "Insufficient free stock for " + stock.sku() + ": requested=" + quantity
                            + ", free=" + stock.freeQuantity()
                            + ", warehouse=" + stock.warehouseId());
        }
    }

    private void reserveOrThrow(String sku, int warehouseId, BigDecimal quantity) {
        int updated = dao.reserveIfAvailable(sku, warehouseId, quantity);
        if (updated == 0) {
            var stock = dao.findStock(sku, warehouseId);
            String available = stock.map(s -> String.valueOf(s.freeQuantity())).orElse("unknown");
            throw new FolioAccountConflictException("insufficient_stock",
                    "Insufficient free stock for " + sku + ": requested=" + quantity
                            + ", free=" + available
                            + ", warehouse=" + warehouseId);
        }
    }

    private static void ensureLineIsActive(FolioAccountDao.MoveRow line) {
        if (!line.active()) {
            throw new FolioAccountConflictException("line_cancelled", "Folio account line is not active: " + line.recno());
        }
    }
}
