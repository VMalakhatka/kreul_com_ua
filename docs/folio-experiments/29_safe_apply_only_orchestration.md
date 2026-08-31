# SAFE_APPLY_ONLY: однопроходная оркестрация native-range

Дата: 2026-08-28. Оптимизация selected-SKU baseline проверена 2026-08-29.

## Область

Режим относится только к
`POST /admin/folio/accounting-prices/recalculate/native-range`. Полный
`native-full` сохраняет двухпроходную схему и отклоняет `SAFE_APPLY_ONLY`.

## SQL golden-master

База данных уже проверена точным Paint_Rus экспериментом
`18_safe_accounting_price_commit_golden_master_paint_rus.md`:

- чистый SKU восстановлен safe-процедурой и зафиксирован;
- проблемный SKU после частичного DML полностью откатан;
- следующий чистый SKU после проблемы зафиксирован;
- `NACH_KOLCH`, `KON_KOLCH`, `REZ_KOLCH`, структура движений, документы,
  `SCLAD_R.N_2/N_4` и baseline `TMP_MOVE` не повреждены;
- неизвестный исход остаётся fail-stop.

Обновление safe-процедуры для `N_2=1100` меняло только guard поддерживаемого
режима и не меняло доказанную per-SKU транзакционную модель.

## Java orchestration tests

`FolioAccountingPriceServiceTest` дополнен сценариями:

1. `SAFE_APPLY_ONLY` не выполняет rollback-preflight, чистый SKU вызывает
   процедуру один раз и увеличивает `committedChunks`;
2. диагностический `returnCode=20` откатывает проблемный SKU, сохраняет warning
   и позволяет зафиксировать следующий SKU;
3. неизвестная ошибка после успешного SKU откатывает текущую транзакцию,
   останавливает job как `FAILED_PARTIAL` и сохраняет предыдущий commit;
4. режим отклоняется для preview и для `native-full`.

После production-диагностики устранён отдельный источник задержки: прежняя
реализация перед каждым пакетом из 118–500 SKU дважды читала защищённое
состояние всех карточек и всех движений склада. Для `SAFE_APPLY_ONLY` теперь:

- начальный и финальный baseline читают только точный выбранный список SKU;
- postcheck внутри каждой транзакции по-прежнему сравнивает только текущий SKU;
- fingerprints всех успешно зафиксированных SKU строятся тремя set-based
  запросами и публикуются одним MariaDB batch вместо запросов на каждый SKU;
- транзакционная граница safe-процедуры не менялась: один SKU — один
  commit/rollback.

После production-гонки snapshot → apply добавлена отдельная обработка исчезшей
карточки. Exact-list baseline возвращает только реально существующие
`SCL_ARTC`; отсутствующий SKU не передаётся safe-процедуре, учитывается в
прогрессе как безопасно пропущенный и публикуется как структурированный warning
`SELECTED_PRODUCT_NO_LONGER_EXISTS`. Обработка остальных SKU продолжается, а
финальный snapshot должен перевести исчезнувшую карточку в `REMOVED`.

Unit contract подтверждает отсутствие full-warehouse baseline и отдельных
fingerprint-вызовов в `SAFE_APPLY_ONLY`. Paint_Rus strict preflight был успешен;
повторный safe rollback-golden-master завершился `ROLLED_BACK` (run
`4b00527f-0587-4698-a0f1-85d9835f03cc`). Exact-list rollback benchmark для 25
SKU прочитал 25 карточек и 33 движения: protected capture — 126 ms, пакетный
movement fingerprint — 46 ms, `TMP_MOVE` до/после — 379498 строк, внешний
rollback и чистая транзакционная граница подтверждены (run
`d005de71-3a17-49fd-8f7e-8164487450d0`). Это измерение доказывает выбранную
область чтения, но не обещает линейное время для любого production-склада.

Целевой Maven-набор прошёл 2026-08-28. PHP-файл кампании также прошёл syntax
check.

## Production contract

WordPress-кампания отправляет:

```json
{
  "warehouseId": 5,
  "skus": ["..."],
  "previewOnly": false,
  "confirmApply": true,
  "applyMode": "SAFE_APPLY_ONLY"
}
```

Известные проблемы остаются `FAILED`/warnings и не останавливают следующие
товары. Неизвестный return code, SQL/OUT/postcheck error останавливает кампанию.
`OUTCOME_UNKNOWN` запрещает автоматический retry. Повторная проверка живой
Paint_Rus лаборатории требует активного VPN; недоступность VPN не является
основанием обращаться к Paint_Ua.
