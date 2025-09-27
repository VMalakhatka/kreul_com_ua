package org.example.proect.lavka.service.stock;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.SclArtcDao;
import org.example.proect.lavka.dto.stock.StockQueryRequest;
import org.example.proect.lavka.dto.stock.StockQueryResponse;
import org.springframework.stereotype.Service;

import java.util.*;

    @Service
    @RequiredArgsConstructor
    public class StockQueryQuantytiService {
        private final SclArtcDao sclArtcDao;

        public StockQueryResponse resolve(StockQueryRequest req) {
            if (req.skus() == null || req.skus().isEmpty()) {
                return new StockQueryResponse(List.of());
            }

            // sku -> (locationId -> qty)
            Map<String, Map<Long, Integer>> acc = new HashMap<>();

            for (StockQueryRequest.LocationCodes loc : req.locations()) {
                Set<Integer> sclads = new HashSet<>(loc.codes()); // MSSQL ID_SCLAD

                // бьём SKU на пачки по 500
                for (int i = 0; i < req.skus().size(); i += 500) {
                    List<String> chunk = req.skus().subList(i, Math.min(i + 500, req.skus().size()));

                    var rows = sclArtcDao.findFreeBySkus(sclads, chunk);
                    for (var r : rows) {
                        acc.computeIfAbsent(r.sku(), k -> new HashMap<>())
                                .merge(loc.id(), r.freeQty(), Integer::sum);
                    }
                }
            }

            // собрать ответ в порядке запроса
            List<StockQueryResponse.SkuStock> items = new ArrayList<>(req.skus().size());
            for (String sku : req.skus()) {
                Map<Long, Integer> byLoc = acc.getOrDefault(sku, Map.of());

                List<StockQueryResponse.LocQty> lines = req.locations().stream()
                        .map(loc -> new StockQueryResponse.LocQty(
                                loc.id(),
                                byLoc.getOrDefault(loc.id(), 0)
                        ))
                        .toList();

                int total = lines.stream().mapToInt(StockQueryResponse.LocQty::qty).sum();

                items.add(new StockQueryResponse.SkuStock(sku, lines, total));
            }

            return new StockQueryResponse(items);
        }
    }