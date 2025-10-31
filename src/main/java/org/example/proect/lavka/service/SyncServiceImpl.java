package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.wp.WpProductDao;
import org.example.proect.lavka.dto.CardTovExportOutDto;
import org.example.proect.lavka.dto.sync.SyncRunResponse;
import org.springframework.stereotype.Service;

import java.util.List;

import java.util.ArrayList;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SyncServiceImpl implements SyncService {

    private final WpProductDao wpProductDao;
    private final CardTovExportService cardTovExportService;

    @Override
    public SyncRunResponse runOneBatch(
            Integer limit,
            Integer pageSizeWoo,
            String startCursorAfter,
            Boolean dryRun
    ) {
        // 1. Настройки входа / защита
        final int runLimit=(limit == null || limit < 1)
                ? 100        // сколько просим у diffPage за один шаг
                : limit;
        final int maxChunkAsk = Math.min(runLimit, 1000);
        // Важно: это НЕ "весь лимит синхронизации", это только размер одной порции diffPage().
        // Если limit=1_000_000, мы всё равно не попросим за раз больше 1000.

        final int wooWindowSize = (pageSizeWoo == null || pageSizeWoo < 1)
                ? 200
                : Math.min(pageSizeWoo, 1000);

        final boolean isDry = (dryRun != null && dryRun);

        // 2. Счётчики всего прогона
        int totalProcessed = 0;
        int totalCreated   = 0;
        int totalUpdated   = 0;
        int totalDrafted   = 0;
        final List<String> allErrors = new ArrayList<>();

        // 3. Курсор — глобальный pointer по SKU
        String cursorAfter = (startCursorAfter == null || startCursorAfter.isBlank())
                ? null
                : startCursorAfter.trim();

        boolean reachedEndFromDiff = false;

        while (totalProcessed<runLimit) {
            String beforeCallCursor = cursorAfter;

            // (A) забираем окно Woo, начиная строго после cursorAfter
            List<CardTovExportService.ItemHash> seenWindow =
                    wpProductDao.collectSeenWindow(wooWindowSize, cursorAfter);
            while (!reachedEndFromDiff) {
                // (B) спрашиваем diffPage у MSSQL/Java
                CardTovExportService.DiffResult diff =
                        cardTovExportService.diffPage(
                                cursorAfter,
                                maxChunkAsk,     // просим не больше 1000 за раз
                                seenWindow
                        );

                // распаковываем
                List<String> toDelete = nzList(diff.toDelete());
                List<CardTovExportOutDto> toUpd = nzList(diff.toUpdateFull());
                List<CardTovExportOutDto> toAdd = nzList(diff.toCreateFull());

                int batchDel = toDelete.size();
                int batchUpd = toUpd.size();
                int batchAdd = toAdd.size();
                int batchProcessed = batchDel + batchUpd + batchAdd;

                // === тут будет реальная запись в Woo (bulk) ===
                if (isDry) {
                    totalDrafted += batchDel;
                    totalUpdated += batchUpd;
                    totalCreated += batchAdd;
                } else {
                    // (1) перевести удаляемые в draft
//                int draftedNow = wpProductDao.draftProductsBySkus(toDelete);
//                totalDrafted += draftedNow;

                    // (2) bulk upsert (обновить/создать) по toUpd + toAdd
                    //    TODO: реализовать wooRestClient.bulkUpsertProducts()
                    //    вернёт, например, {created:N1, updated:N2} и там же мы обновим мету _ms_hash
                    BulkResult bulkRes = fakeOrRealBulkUpsert(toUpd, toAdd);
                    totalCreated += bulkRes.created();
                    totalUpdated += bulkRes.updated();
                }

                totalProcessed += batchProcessed;

                // продвигаем курсор дальше
                cursorAfter = diff.nextAfter(); // может быть null

                reachedEndFromDiff = diff.last();
            }

            // (2) MSSQL/Woo не продвинули курсор
            //     либо nextAfter == null,
            //     либо остался тем же -> никуда дальше идти.
            if (cursorAfter == null
                    || cursorAfter.isBlank()
                    || Objects.equals(beforeCallCursor, cursorAfter)) {
                break;
            }

            // иначе — ещё есть что есть, крутимся дальше
            // (и не смотрим на limit, потому что limit теперь не стопор)
        }

        return new SyncRunResponse(
                true,
                totalProcessed,
                totalCreated,
                totalUpdated,
                totalDrafted,
                cursorAfter,          // последний курсор, куда дошли
                reachedEndFromDiff,   // true => "мы реально дошли до конца MSSQL"
                allErrors
        );
    }

    // helper
    private static <T> List<T> nzList(List<T> in) {
        return (in == null) ? List.of() : in;
    }

    // временный заглушечный контракт
    private record BulkResult(int created, int updated) {}

    private BulkResult fakeOrRealBulkUpsert(
            List<CardTovExportOutDto> toUpd,
            List<CardTovExportOutDto> toAdd
    ) {
        // пока просто считаем; потом заменишь на реальный вызов Woo REST /products/batch
        return new BulkResult(toAdd.size(), toUpd.size());
    }
}