package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.CardTovExportDao;
import org.example.proect.lavka.dto.CardTovExportDto;
import org.example.proect.lavka.dto.CardTovExportOutDto;
import org.example.proect.lavka.service.category.WooCategoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CardTovExportService {

    private final CardTovExportDao dao;
    private final WooCategoryService wooCategoryService;

    public PageResult page(String afterSku, int limit) {
        List<CardTovExportDto> items = dao.findPage(afterSku, limit);

        // маппим в компактное DTO с groupId
        List<CardTovExportOutDto> mapped = items.stream()
                .map(this::mapWithGroup)
                .toList();

        String nextAfter = mapped.isEmpty() ? null : mapped.get(mapped.size() - 1).sku();
        boolean lastPage = mapped.isEmpty() || mapped.size() < Math.max(1, limit);

        return new PageResult(mapped, nextAfter, lastPage);
    }

    private CardTovExportOutDto mapWithGroup(CardTovExportDto d) {
        // выбираем самый глубокий непустой уровень (от TV6 к TVR)
        String l1 = nz(d.getNGROUP_TVR());
        String l2 = nz(d.getNGROUP_TV2());
        String l3 = nz(d.getNGROUP_TV3());
        String l4 = nz(d.getNGROUP_TV4());
        String l5 = nz(d.getNGROUP_TV5());
        String l6 = nz(d.getNGROUP_TV6());

        Long groupId = null;
        if (notBlank(l1) || notBlank(l2) || notBlank(l3) || notBlank(l4) || notBlank(l5) || notBlank(l6)) {
            groupId = wooCategoryService.ensureCategoryPath(
                    emptyToNull(l1),
                    emptyToNull(l2),
                    emptyToNull(l3),
                    emptyToNull(l4),
                    emptyToNull(l5),
                    emptyToNull(l6)
            );
        }

        return new CardTovExportOutDto(
                d.getSku(),
                d.getName(),
                d.getImg(),
                d.getEDIN_IZMER(),
                d.getGlobal_unique_id(),
                d.getWeight(),
                d.getLength(),
                d.getWidth(),
                d.getHeight(),
                d.getStatus(),
                d.getVES_EDINIC(),
                d.getDESCRIPTION(),
                d.getRAZM_IZMER(),
                d.getGr_descr(),
                groupId
        );
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    public record PageResult(List<CardTovExportOutDto> items, String nextAfter, boolean last) {}
}