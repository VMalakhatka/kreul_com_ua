package org.example.proect.lavka.service.stock;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.SclArtcDao;
import org.example.proect.lavka.dto.stock.*;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NoMovementService {

    private final SclArtcDao dao;
    private final LavkaApiProperties props;
    private final Clock clock = Clock.systemUTC();

    public StockNoMovementResponsePage resolve(StockNoMovementRequest req) {
        if (req == null || req.locations() == null || req.locations().isEmpty()) {
            return empty(0, null, null);
        }

        // Пагинация
        int page = Optional.ofNullable(req.page()).orElse(0);
        int pageSize = Optional.ofNullable(req.pageSize()).orElse(props.getMovementPageSize());
        if (page < 0) page = 0;
        if (pageSize <= 0) pageSize = props.getMovementPageSize();

        // Склады
        Set<Integer> sclads = req.locations().stream()
                .filter(Objects::nonNull)
                .filter(l -> l.codes() != null)
                .flatMap(l -> l.codes().stream())
                .collect(Collectors.toSet());
        if (sclads.isEmpty()) return empty(page, null, null);

        // Даты
        if (req.from() == null || req.from().isBlank() || req.to() == null || req.to().isBlank()) {
            return empty(page, null, null);
        }
        Instant from, to;
        try {
            from = Instant.parse(req.from());
            to   = Instant.parse(req.to());
        } catch (Exception e) {
            return empty(page, null, null);
        }
        if (from.isAfter(to)) from = to;

        // Типы операций (опционально)
        List<String> ops = (req.opTypes() == null) ? List.of()
                : req.opTypes().stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).toList();

        int offset = page * pageSize;
        int limit  = pageSize + 1;

        var rows = dao.findSkusWithoutMovement(sclads, ops, from, to, limit, offset);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }

        return new StockNoMovementResponsePage(rows, page, !hasMore, from, to);
    }

    private StockNoMovementResponsePage empty(int page, Instant from, Instant to) {
        return new StockNoMovementResponsePage(List.of(), page, true, from, to);
    }
}