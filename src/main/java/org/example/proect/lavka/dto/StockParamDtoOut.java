package org.example.proect.lavka.dto;

public record StockParamDtoOut(
        String codArtc,
        long idStock
        ,
        double minTvrZap,//"MIN_TVRZAP"
        double maxTvrZap //"MAX_TVRZAP"
        ,
         int tipTov,//"TIP_TOV"
        int tipOrder //"BALL5"
) { }
