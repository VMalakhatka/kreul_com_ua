package org.example.proect.lavka.dto;

import org.example.proect.lavka.entity.enums.TypDocmPrOut;

import java.time.LocalDateTime;

public record MoveDtoOut(
        String NamePredm,
        double UnicumNum,
        long nDocum,
        String  numdcmDop,
        String orgPredm,
        LocalDateTime data,
        double quantity,
        TypDocmPrOut typDocmPr,
        String vidDoc,
        long idStock
) { }
