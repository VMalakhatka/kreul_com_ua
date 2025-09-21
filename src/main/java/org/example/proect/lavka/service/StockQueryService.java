package org.example.proect.lavka.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class StockQueryService {
    private final JdbcTemplate jdbc;
    public StockQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Верни карту SKU -> количество по поставщику и складу.
     * TODO: замени your_table/поля под свою схему.
     */
    public Map<String, Integer> loadSkuQty(String supplier, int stockId) {
        String sql = """
            SELECT sku, SUM(qty) AS qty
            FROM your_table
            WHERE supplier = ? AND stock_id = ?
            GROUP BY sku
        """;
        Map<String, Integer> res = new HashMap<>();
        /*
            jdbc.query(sql, rs -> res.put(rs.getString("sku"), rs.getInt("qty")),
                supplier, stockId);

         */
        return res;
    }

    /**
     * (Опционально) Вернуть productId -> SKU, если хочешь массово выставить SKU
     * из своей БД (например, по внутреннему ID).
     * Здесь просто пример: верни пустую мапу, и под себя допиши.
     */
    public Map<Long, String> loadProductIdToSkuForInit() {
        return Map.of(); // заполни, если нужно
    }
}
