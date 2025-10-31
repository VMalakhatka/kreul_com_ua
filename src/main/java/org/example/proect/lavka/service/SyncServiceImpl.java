package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.sync.SyncRunResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SyncServiceImpl implements SyncService {

    @Override
    public SyncRunResponse runOneBatch(
            Integer limit,
            Integer pageSizeWoo,
            String cursorAfter,
            Boolean dryRun
    ) {
        // Здесь потом будет:
        // 1. собрать seen из Woo (pageSizeWoo, cursorAfter)
        // 2. вызвать diffPage(...) у CardTovExportService
        // 3. разложить DiffResult: toUpdateFull, toCreateFull, toDelete
        // 4. если !dryRun ->:
        //    - toDelete => draft через Woo API
        //    - toUpdateFull/toCreateFull => bulk upsert в Woo API батчами <=100
        // 5. посчитать метрики, вернуть nextAfter из diffResult.nextAfter()

        // Сейчас — просто возвращаем "пустой ответ", чтобы контроллер уже жил.
        return new SyncRunResponse(
                true,           // ok
                0,              // processed
                0,              // created
                0,              // updated
                0,              // drafted
                cursorAfter,    // nextAfter (пока тот же курсор)
                false,          // last
                List.of()       // errors
        );
    }
}