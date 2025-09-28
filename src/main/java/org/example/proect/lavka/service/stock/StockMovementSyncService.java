package org.example.proect.lavka.service.stock;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.SclArtcDao;
import org.example.proect.lavka.dto.stock.StockMovementRequest;
import org.example.proect.lavka.dto.stock.StockQueryRequest;
import org.example.proect.lavka.dto.stock.StockQueryResponse;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class StockMovementSyncService {

    private final SclArtcDao sclArtcDao;
    private final StockQueryQuantytiService qtyService;
    private final LavkaApiProperties props;
    private final Clock clock = Clock.systemUTC(); // для тестируемости

    public StockQueryResponsePage resolve(StockMovementRequest req) {
        // Проверки входа
        if (req == null || req.locations() == null || req.locations().isEmpty()) {
            return empty(0, null, null);
        }
        if (req.from() == null || req.from().isBlank()) {
            // нет точки отсчёта — ничего не отдаем, считаем это «последняя»
            return empty(0, null, null);
        }

        // Пагинация
        int page = Optional.ofNullable(req.page()).orElse(0);
        int pageSize = Optional.ofNullable(req.pageSize()).orElse(props.getMovementPageSize());
        if (page < 0) page = 0;
        if (pageSize <= 0) pageSize = props.getMovementPageSize();
        // не позволяем запросить больше, чем наш безопасный максимум
        pageSize = Math.min(pageSize, props.getMovementPageSize());

        // Склады
        Set<Integer> sclads = new HashSet<>();
        req.locations().forEach(l -> {
            if (l != null && l.codes() != null) sclads.addAll(l.codes());
        });
        if (sclads.isEmpty()) {
            return empty(page, null, null);
        }

        // Границы периода: [from, now), ограничиваем максимумом дней
        Instant from;
        try {
            from = Instant.parse(req.from());
        } catch (Exception e) {
            return empty(page, null, null);
        }
        Instant to = Instant.now(clock);

        if (from.isAfter(to)) from = to;
        long days = Duration.between(from, to).toDays();
        int maxDays = props.getMovementMaxDays();
        if (maxDays > 0 && days > maxDays) {
            to = from.plus(Duration.ofDays(maxDays));
        }

        // Берем pageSize+1, чтобы понять есть ли следующая страница
        final int offset = Math.max(0, page * pageSize);
        final int limit  = pageSize + 1;

        List<String> skus = sclArtcDao.findSkusWithMovement(sclads, from, to, limit, offset);
        boolean hasMore = skus.size() > pageSize;
        if (hasMore) {
            skus = skus.subList(0, pageSize);
        }

        if (skus.isEmpty()) {
            // Нет движения в этой "странице" — считаем, что это последняя
            return empty(page, from, to);
        }

        // Считаем остатки для выбранных артикулов
        StockQueryRequest qtyReq = new StockQueryRequest(skus, req.locations());
        StockQueryResponse qtyRes = qtyService.resolve(qtyReq);

        boolean last = !hasMore;
        return new StockQueryResponsePage(qtyRes.items(), page, last, from, to);
    }

    private StockQueryResponsePage empty(int page, Instant from, Instant to) {
        return new StockQueryResponsePage(List.of(), page, true, from, to);
    }

    // Ответ: те же items, плюс текущая страница и флаг последней, и фактические границы на сервере
    public record StockQueryResponsePage(
            List<StockQueryResponse.SkuStock> items,
            int page,
            boolean last,
            Instant serverFrom,
            Instant serverTo
    ) {}
}