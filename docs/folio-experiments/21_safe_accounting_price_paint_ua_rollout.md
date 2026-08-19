# Установка safe-процедур и первый preview в Paint_Ua

Дата подготовки: 2026-08-18.

Этот документ описывает первый production gate. На момент подготовки
`Paint_Ua` не изменялась.

## Что устанавливается

SQL-файл:

`docs/folio-experiments/20_install_safe_accounting_price_procedures_paint_ua.sql`

Он создаёт только два новых объекта:

- `dbo.LAVKA_I_UCHET_1_TOVAR_SAFE`;
- `dbo.LAVKA_I_UCHET_TOVAR_SAFE`.

Штатные `dbo.I_UCHET_1_TOVAR` и `dbo.I_UCHET_TOVAR`, таблицы, документы,
остатки и учётные цены установочный файл не изменяет. Повторный запуск поверх
существующих `LAVKA_*` намеренно запрещён.

## Обязательные условия

1. Есть актуальная проверенная резервная копия `Paint_Ua`.
2. Менеджеры закрыли настольную ФОЛИО и не создают/не редактируют документы.
3. В SQL Query Analyzer явно выбрана база `Paint_Ua`.
4. Установку выполняет DBA-пользователь, способный создать procedure.
5. Имя рабочего database user Java берётся локально из production `.env` и не
   копируется в Git, чат или скриншоты.

## Ручная установка

1. Открыть SQL-файл целиком в отдельном окне SQL Query Analyzer.
2. Ещё раз проверить выбранную базу `Paint_Ua`.
3. Выполнить весь файл.
4. Ожидается одна итоговая строка:

   - `database_name = Paint_Ua`;
   - `server_version = 8.00...`;
   - `compatibility_level = 80`;
   - оба procedure ID не равны `NULL`.
   - оба `parameter_count` равны `20`.

Если показано сообщение `STOP`, закрыть это окно Query Analyzer. Защитный
`SET NOEXEC ON` намеренно не позволяет последующим `GO` создать объекты после
неуспешной проверки.

## Минимальные права Java

После успешной установки открыть **новое окно** Query Analyzer и выполнить
отдельно, подставив именно существующий database user production Java-сервиса.
Не выполнять буквальное имя-заглушку `FOLIO_SERVICE_DATABASE_USER`:

```sql
GRANT EXECUTE ON dbo.LAVKA_I_UCHET_1_TOVAR_SAFE
    TO [FOLIO_SERVICE_DATABASE_USER]
GO
GRANT EXECUTE ON dbo.LAVKA_I_UCHET_TOVAR_SAFE
    TO [FOLIO_SERVICE_DATABASE_USER]
GO
```

Не выдавать `db_owner`. Пароль для этих команд не нужен и в SQL не вставляется.

## Если старый установщик показал ошибки после итоговой строки

Версия установщика из коммита `9d95e24` содержала пример `GRANT` внутри
заключительного block comment. Старый SQL Query Analyzer сначала разделяет
текст по batch separator, а потом разбирает комментарии. Поэтому он разорвал
комментарий, попытался выполнить имя-заглушку как настоящее и показал:

- `Missing end comment mark`;
- `There is no such user or group 'FOLIO_SERVICE_DATABASE_USER'`;
- `Incorrect syntax near 'one'`.

Эти ошибки относятся к тексту после установочной транзакции. Они не означают,
что ФОЛИО пересчитала цены или изменила документы. В новом окне выполнить
только проверку:

```sql
SELECT DB_NAME() AS database_name,
       OBJECT_ID('dbo.LAVKA_I_UCHET_1_TOVAR_SAFE') AS one_sku_procedure_id,
       (SELECT COUNT(*) FROM dbo.syscolumns
         WHERE id=OBJECT_ID('dbo.LAVKA_I_UCHET_1_TOVAR_SAFE')) AS one_sku_parameter_count,
       OBJECT_ID('dbo.LAVKA_I_UCHET_TOVAR_SAFE') AS wrapper_procedure_id,
       (SELECT COUNT(*) FROM dbo.syscolumns
         WHERE id=OBJECT_ID('dbo.LAVKA_I_UCHET_TOVAR_SAFE')) AS wrapper_parameter_count
```

Если оба ID не `NULL` и оба parameter count равны `20`, установка уже успешно
завершилась: повторно установочный файл не запускать, выполнить только реальные
`GRANT EXECUTE`. Если оба ID равны `NULL`, можно запустить исправленный
установщик целиком. Любой смешанный результат остановить и передать на ручную
проверку; ничего не удалять самостоятельно.

## Деплой Java перед preview

Java должна содержать production-переход на
`dbo.LAVKA_I_UCHET_TOVAR_SAFE`. Без установленных процедур endpoint завершится
`FAILED` с сообщением, что обязательные safe-процедуры отсутствуют.

Для первого preview достаточно:

```text
LAVKA_FOLIO_ACCOUNTING_PRICE_API_ENABLED=true
LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_ENABLED=true
LAVKA_FOLIO_ACCOUNTING_PRICE_APPLY_ENABLED=false
LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_APPLY_ENABLED=false
```

`Paint_Ua` должна присутствовать в
`LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_ALLOWED_DATABASES`.

## Первый rollback-preview

Запускать только в согласованное окно без работы менеджеров:

```http
POST /admin/folio/accounting-prices/recalculate/native-full
Content-Type: application/json
```

```json
{
  "warehouseId": 5,
  "previewOnly": true,
  "confirmApply": false
}
```

После HTTP 202 опрашивать:

```http
GET /admin/folio/accounting-prices/recalculate/native-full/status
```

Допустимый финал:

- `PREVIEW_READY`; или
- `PREVIEW_READY_WITH_WARNINGS`.

Обязательные признаки preview:

- `running=false`;
- `committedChunks=0`;
- `error` отсутствует;
- каждый warning содержит точный SKU;
- `ZERO_ACCOUNTING_DENOMINATOR` дополнительно содержит `recno`, дату,
  формулу, числитель, знаменатель и количества;
- `NEGATIVE_CHRONOLOGICAL_STOCK` содержит операцию, состояние до/после и
  величину нехватки.

`PREVIEW_READY_WITH_WARNINGS` является успешным результатом: проблемные SKU
откатились, остальные были проверены, постоянных изменений нет.

## Postcheck

После preview:

1. `committedChunks` остаётся нулём.
2. В ФОЛИО открываются существующие документы и остатки выбранного склада.
3. Создание тестового документа выполняется только после завершения job и
   снятия окна обслуживания.
4. В `folio-accounting-price.log` есть старт, пропущенные SKU и завершение, но
   нет сырого SQL или реквизитов соединения.
5. Apply-флаги остаются выключенными до отдельного решения по результату
   preview.

Первый production apply не является частью этого gate.

## Upgrade первого NULL-курсора

Первый production preview от 2026-08-19 подтвердил legacy-особенность:
safe-процедура обработала первый SKU и вернула `n_cur=40` и следующий артикул,
но оставила выходной `art=NULL`, потому что входной курсор также был `NULL`.
Java безопасно откатила вызов и завершила job до любых постоянных изменений.

Для уже установленной версии выполнить один раз:

`docs/folio-experiments/22_upgrade_safe_accounting_price_first_cursor_paint_ua.sql`

Скрипт изменяет только wrapper, сохраняет выданные `EXECUTE` permissions и не
запускает перерасчёт. После результата `FIRST_NULL_CURSOR_FIXED` повторить тот
же rollback-preview; новый деплой Java для этого SQL-исправления не требуется.
