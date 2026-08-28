# SAFE_APPLY_ONLY: однопроходная оркестрация native-range

Дата: 2026-08-28.

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
