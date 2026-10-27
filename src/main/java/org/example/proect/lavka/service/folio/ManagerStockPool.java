package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioAccountDao;
import org.example.proect.lavka.dto.folio.FolioOrderAccountRequest;
import java.math.BigDecimal;
import java.util.*;

/** A manager explicitly counts auxiliary free stock without moving physical stock. */
public record ManagerStockPool(int targetWarehouseId, List<Integer> auxiliaryWarehouseIds) {
    public ManagerStockPool {
        if (targetWarehouseId <= 0 || auxiliaryWarehouseIds == null || auxiliaryWarehouseIds.isEmpty()
                || auxiliaryWarehouseIds.size() > 20 || auxiliaryWarehouseIds.stream().anyMatch(i -> i == null || i <= 0 || i == targetWarehouseId)
                || new HashSet<>(auxiliaryWarehouseIds).size() != auxiliaryWarehouseIds.size())
            throw new FolioAccountValidationException("invalid_manager_pool", "Select one target and unique auxiliary warehouses");
        auxiliaryWarehouseIds = List.copyOf(auxiliaryWarehouseIds);
    }
    public void validate(FolioOrderAccountRequest request) {
        if (!List.of("processing", "on-hold").contains(request.wooOrder().status()) || !Boolean.TRUE.equals(request.folioAccountHeader().accountingEnabled()))
            throw new FolioAccountValidationException("invalid_manager_pool", "Manager pool requires an accounting order");
        boolean target = false;
        for (var line : request.items()) for (var allocation : line.allocations()) for (var candidate : allocation.folioWarehouses()) {
            int id;
            try { id = Integer.parseInt(candidate.id()); } catch (NumberFormatException e) { throw new FolioAccountValidationException("invalid_manager_pool", "Invalid warehouse"); }
            if (auxiliaryWarehouseIds.contains(id)) throw new FolioAccountValidationException("overlapping_manager_pool", "An auxiliary warehouse cannot also be an order destination");
            if (id == targetWarehouseId) target = true;
        }
        if (!target) throw new FolioAccountValidationException("invalid_manager_pool", "Target is absent from allocation candidates");
    }
    private Map<Integer, FolioAccountDao.StockRow> rows(FolioAccountDao dao, String sku, boolean lock) {
        List<Integer> ids = new ArrayList<>(auxiliaryWarehouseIds); ids.add(targetWarehouseId); Collections.sort(ids);
        Map<Integer, FolioAccountDao.StockRow> rows = new LinkedHashMap<>();
        for (int id : ids) (lock ? dao.lockStock(sku, id) : dao.findStock(sku, id)).ifPresent(row -> rows.put(id, row));
        // A target card must already exist, even when all physical units are on receipt.
        return rows;
    }
    public BigDecimal available(FolioAccountDao dao, String sku, boolean lock) {
        var rows = rows(dao, sku, lock);
        if (!rows.containsKey(targetWarehouseId)) return BigDecimal.ZERO;
        // Do not clamp the target: its negative free quantity records earlier pooled commitments.
        return rows.values().stream().map(FolioAccountDao.StockRow::freeQuantity).reduce(BigDecimal.ZERO, BigDecimal::add).max(BigDecimal.ZERO);
    }
    public void requireAvailable(FolioAccountDao dao, String sku, BigDecimal quantity) {
        if (available(dao, sku, true).compareTo(quantity) < 0)
            throw new FolioAccountConflictException("insufficient_manager_pool", "Pooled free stock changed: " + sku);
    }
    public void reserve(FolioAccountDao dao, String sku, BigDecimal quantity) {
        var rows = rows(dao, sku, true);
        BigDecimal other = rows.entrySet().stream().filter(e -> e.getKey() != targetWarehouseId)
                .map(e -> e.getValue().freeQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!rows.containsKey(targetWarehouseId) || dao.reserveUsingPool(sku, targetWarehouseId, quantity, other) != 1)
            throw new FolioAccountConflictException("insufficient_manager_pool", "Pooled free stock changed: " + sku);
    }
}
