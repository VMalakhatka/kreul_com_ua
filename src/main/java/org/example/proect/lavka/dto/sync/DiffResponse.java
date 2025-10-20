package org.example.proect.lavka.dto.sync;

import org.example.proect.lavka.dto.CardTovForSync;
import java.util.List;

public record DiffResponse(
        String nextAfter,
        boolean last,
        List<String> toCreate,           // есть у источника, отсутствует у Woo
        List<String> toUpdate,           // есть у обоих, но хешы различаются
        List<String> toDelete,           // есть у Woo в переданной странице, отсутствует у источника
        List<CardTovForSync> toCreateFull, // опционально: сразу полные
        List<CardTovForSync> toUpdateFull  // опционально: сразу полные
) {}