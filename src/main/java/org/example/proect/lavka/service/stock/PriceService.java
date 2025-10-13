package org.example.proect.lavka.service.stock;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.stock.PriceDao;
import org.example.proect.lavka.dto.stock.PriceItem;
import org.example.proect.lavka.dto.stock.PriceQueryRequest;
import org.example.proect.lavka.dto.stock.PriceQueryResponse;
import org.example.proect.lavka.dto.stock.PriceRow;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class PriceService {

    private final PriceDao priceDao;
    private final LavkaApiProperties props;

    public PriceQueryResponse resolve(PriceQueryRequest req) {
        if (req == null || req.skus() == null || req.skus().isEmpty()
                || req.priceMap() == null || req.priceMap().isEmpty()) {
            return new PriceQueryResponse(List.of());
        }

        // обратная мапа: NAME_PRICE -> wooId
        Map<String, String> nameToWooId = new HashMap<>();
        for (var e : req.priceMap().entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String wooId = e.getKey().trim();
            String name  = e.getValue().trim();
            if (!wooId.isEmpty() && !name.isEmpty()) {
                nameToWooId.put(name, wooId);
            }
        }
        if (nameToWooId.isEmpty()) return new PriceQueryResponse(List.of());

        int stockId = props.getPriceStockId(); // используем значение из конфигов

        // 1) розничные цены (SCL_ARTC.CENA_ARTIC)
        Map<String, Double> retailBySku = priceDao.findRetailPrices(stockId, req.skus());

        // 2) доп.цены (SCL_PRIC) по последней дате
        List<PriceRow> rows = priceDao.findLatestPrices(
                stockId,
                req.skus(),
                new ArrayList<>(nameToWooId.keySet()) // список NAME_PRICE
        );

        // 3) подготовим карту "sku -> (wooId -> цена)" заполнив null-ами для всех запрошенных wooId
        Map<String, Map<String, Double>> extrasBySku = new LinkedHashMap<>();
        for (String sku : req.skus()) {
            Map<String, Double> blank = new LinkedHashMap<>();
            for (String wooId : req.priceMap().keySet()) {
                blank.put(wooId, null);
            }
            extrasBySku.put(sku, blank);
        }

        // 4) заполним значениями доп.цен
        for (PriceRow r : rows) {
            String wooId = nameToWooId.get(r.namePrice());
            if (wooId == null) continue;

            Double val = (r.rubPrice() != null) ? r.rubPrice() : r.valtPrice();
            if (val == null) continue;

            Map<String, Double> m = extrasBySku.get(r.sku());
            if (m != null) m.put(wooId, val);
        }

        // 5) собираем ответ: price = розничная, prices = карта доп.цен
        List<PriceItem> items = new ArrayList<>(req.skus().size());
        for (String sku : req.skus()) {
            Double retail = retailBySku.get(sku); // может быть null — ок
            Map<String, Double> extras = extrasBySku.getOrDefault(sku, Map.of());
            items.add(new PriceItem(sku, retail, extras));
        }

        return new PriceQueryResponse(items);
    }
}