package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.WarehouseOrderPolicy;
import org.example.proect.lavka.service.folio.FolioAssemblyGraph;
import org.example.proect.lavka.service.folio.FolioProductAnalyticsService;
import org.example.proect.lavka.service.folio.FolioProductMovementClassifier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** One source transaction, including recipes and current internal account reservations.
 * No HTTP/MariaDB calls, writes, dirty reads or snapshot-generation relabelling. */
@Repository
public class FolioCurrentNetworkStockDao {
    private final JdbcTemplate jdbc;
    private final FolioAssemblyDao assembly;
    public FolioCurrentNetworkStockDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc, FolioAssemblyDao assembly) {
        this.jdbc = jdbc; this.assembly = assembly;
    }
    public record Account(int sourceWarehouseId, long documentId, BigDecimal quantity) {}
    public record Card(String sku, int warehouseId, String status, BigDecimal physicalQuantity,
                       BigDecimal availableQuantity, BigDecimal reservedQuantity,
                       WarehouseOrderPolicy orderPolicy, List<Account> internalTransferAccounts) {}
    public record Capture(boolean ok, int version, String captureId, String sourceDatabase, String method,
                          String startedAt, String capturedAt, List<Integer> warehouseIds,
                          List<Integer> analysisWarehouseIds, FolioAssemblyGraph.Graph assemblyGraph, List<Card> cards) {}

    @Transactional(transactionManager = "mssqlTransactionManager", readOnly = true, timeout = 60,
            isolation = Isolation.SERIALIZABLE)
    public Capture read(String database, List<Integer> warehouses, List<Integer> analysisWarehouses, List<String> roots) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), java.sql.Connection.TRANSACTION_SERIALIZABLE))
            throw new IllegalStateException("Current stock requires a source transaction");
        Instant started = Instant.now();
        // Assembly DAO verifies DB_NAME and participates in this same transaction.
        var graph = assembly.read(database, analysisWarehouses, roots);
        var skus = graph.nodes().stream().map(FolioAssemblyGraph.Node::sku).sorted().toList();
        if ((long)skus.size() * warehouses.size() > 200000)
            throw new IllegalArgumentException("Current stock scope exceeds 200000 warehouse cards");
        Map<String, Card> cards = new LinkedHashMap<>();
        Map<String, Map<Long, BigDecimal>> accounts = new HashMap<>();
        for (int offset = 0; offset < skus.size(); offset += 300) {
            var part = skus.subList(offset, Math.min(offset + 300, skus.size()));
            String scope = " ID_SCLAD IN (" + placeholders(warehouses.size()) + ") AND COD_ARTIC IN (" + placeholders(part.size()) + ")";
            jdbc.query(c -> {
                var p = c.prepareStatement("SELECT COD_ARTIC, ID_SCLAD, KON_KOLCH, REZ_KOLCH, MIN_TVRZAP, MAX_TVRZAP FROM dbo.SCL_ARTC WITH (HOLDLOCK) WHERE" + scope + " ORDER BY ID_SCLAD, COD_ARTIC");
                p.setQueryTimeout(15); int n = 1;
                for (int id : warehouses) p.setInt(n++, id); for (String sku : part) p.setString(n++, sku); return p;
            }, (RowCallbackHandler) rs -> {
                String sku = FolioAssemblyGraph.key(rs.getString("COD_ARTIC")); int id = rs.getInt("ID_SCLAD");
                BigDecimal physical = rs.getBigDecimal("KON_KOLCH"), free = rs.getBigDecimal("REZ_KOLCH");
                BigDecimal reserved = physical == null || free == null ? null : physical.subtract(free);
                String status = reserved == null || reserved.signum() < 0 ? "INVALID_STOCK" : "CAPTURED";
                var card = new Card(sku, id, status, physical, free, reserved,
                        FolioProductAnalyticsService.warehouseOrderPolicy(rs.getBigDecimal("MIN_TVRZAP"), rs.getBigDecimal("MAX_TVRZAP")), List.of());
                if (cards.put(key(sku, id), card) != null) throw new IllegalStateException("Duplicate current warehouse card");
            });
            // Source warehouse only; neither recipient inference nor receipt-date filtering.
            jdbc.query(c -> {
                var p = c.prepareStatement("""
                        SELECT m.NAME_PREDM, m.ID_SCLAD, m.UNICUM_NUM, m.KOLC_PREDM,
                               m.TYPDOCM_PR, n.TYPE_DOC, m.STND_UCHET AS LINE_ACCOUNTED,
                               n.STND_UCHET AS HEADER_ACCOUNTED, m.VOZVRAT_PR,
                               CASE WHEN ISNULL(n.VID_DOC,'')<>'' THEN n.VID_DOC ELSE m.VID_DOC END AS OPERATION_KIND
                          FROM dbo.SCL_MOVE m WITH (HOLDLOCK)
                          JOIN dbo.SCL_NAKL n WITH (HOLDLOCK) ON n.UNICUM_NUM=m.UNICUM_NUM
                         WHERE m.TYPDOCM_PR=? AND m.STND_UCHET=1 AND m.ID_SCLAD IN (
                        """ + placeholders(warehouses.size()) + ") AND m.NAME_PREDM IN (" + placeholders(part.size()) + ") ORDER BY m.ID_SCLAD, m.NAME_PREDM, m.UNICUM_NUM, m.RECNO");
                p.setQueryTimeout(15); p.setString(1, "С"); int n = 2;
                for (int id : warehouses) p.setInt(n++, id); for (String sku : part) p.setString(n++, sku); return p;
            }, (RowCallbackHandler) rs -> {
                boolean internal = FolioProductMovementClassifier.isInternalTransferReservation(
                        rs.getString("TYPDOCM_PR"), rs.getString("TYPE_DOC"), rs.getString("OPERATION_KIND"),
                        rs.getBoolean("LINE_ACCOUNTED"), rs.getBoolean("HEADER_ACCOUNTED"), rs.getBoolean("VOZVRAT_PR"), BigDecimal.ONE);
                if (!internal) return;
                BigDecimal qty = rs.getBigDecimal("KOLC_PREDM"); long doc = rs.getLong("UNICUM_NUM");
                if (qty == null || doc <= 0) throw new IllegalStateException("Invalid internal reservation quantity or document");
                if (qty.signum() <= 0) return;
                accounts.computeIfAbsent(key(FolioAssemblyGraph.key(rs.getString("NAME_PREDM")), rs.getInt("ID_SCLAD")), k -> new TreeMap<>())
                        .merge(doc, qty, BigDecimal::add);
            });
        }
        List<Card> complete = new ArrayList<>();
        for (String sku : skus) for (int id : warehouses) {
            var card = cards.get(key(sku, id));
            if (card == null) { complete.add(new Card(sku, id, "SKU_NOT_PRESENT", null, null, null, null, List.of())); continue; }
            var refs = accounts.getOrDefault(key(sku, id), Map.of()).entrySet().stream()
                    .map(e -> new Account(id, e.getKey(), e.getValue())).toList();
            BigDecimal internal = refs.stream().map(Account::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            String status = card.status();
            if (card.reservedQuantity() != null && internal.compareTo(card.reservedQuantity()) > 0) status = "INTERNAL_TRANSFER_RESERVE_MISMATCH";
            complete.add(new Card(sku, id, status, card.physicalQuantity(), card.availableQuantity(), card.reservedQuantity(), card.orderPolicy(), refs));
        }
        // All read locks remain held here: there is one serializable source cut across every batch.
        return new Capture(true, 1, UUID.randomUUID().toString(), database, "FOLIO_SERIALIZABLE_READ",
                started.toString(), Instant.now().toString(), List.copyOf(warehouses), List.copyOf(analysisWarehouses), graph, complete);
    }
    private static String key(String sku, int id) { return id + ":" + sku; }
    private static String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }
}
