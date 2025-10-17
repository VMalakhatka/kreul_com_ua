package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.CardTovExportDao;
import org.example.proect.lavka.dto.CardTovExportDto;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CardTovExportService {

    private final CardTovExportDao dao;

    public PageResult page(String afterSku, int limit) {
        List<CardTovExportDto> items = dao.findPage(afterSku, limit);

        String nextAfter = null;
        if (!items.isEmpty()) {
            nextAfter = items.get(items.size() - 1).getSku();
        }

        boolean lastPage = items.isEmpty() || items.size() < Math.max(1, limit);
        return new PageResult(items, nextAfter, lastPage);
    }

    public record PageResult(List<CardTovExportDto> items, String nextAfter, boolean last) {}
}