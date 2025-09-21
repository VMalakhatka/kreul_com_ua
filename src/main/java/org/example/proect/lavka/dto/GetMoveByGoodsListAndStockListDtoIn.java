package org.example.proect.lavka.dto;

import java.util.List;

public record GetMoveByGoodsListAndStockListDtoIn(
        List<String> namePredmList,
        List<Long> idList,
        String start,
        String end
) {
}
