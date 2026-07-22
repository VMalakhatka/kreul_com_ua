# ACCOUNT WRITE MAPPING

Статус: предварительный mapping для Java API `/admin/folio/accounts`.

Важно: в проектной документации не найден снимок реального `SCL_NAKL` для тестового счёта `UNICUM_NUM = 752327`. Поэтому ниже разделены:

- поля, подтверждённые текущими docs/экспериментами;
- поля, которые нельзя заполнять “по догадке” и требуют отдельного снимка/сравнения.

## SCL_NAKL

| API-поле | Таблица | Колонка | Правило | Подтверждение |
|---|---|---|---|---|
| `id` | `SCL_NAKL` | `UNICUM_NUM` | allocator | PK, docs: `SCL_NAKL.UNICUM_NUM` |
| `documentNumber` | `SCL_NAKL` | `N_PLAT_POR` | числовой номер, bind как `BigDecimal`; значения `WEB-...` сюда писать нельзя | live schema: `N_PLAT_POR float NOT NULL` |
| account type | `SCL_NAKL` | `TYPE_DOC` | константа `C` для счёта | live snapshot: existing accounts with `N_PLAT_POR=123456829/123456830` have `TYPE_DOC=C` |
| `documentDate` | `SCL_NAKL` | `DATE_P_POR` | напрямую | docs: поле даты документа, требуется подтверждение снимком |
| `totalAmount` | `SCL_NAKL` | `SUM_POR` | сумма строк `SUM_PREDM` | docs: сумма документа, точность требует проверки на ФОЛИО |
| `comment` | `SCL_NAKL` | `DOPN_SCHET` | напрямую | docs: поле присутствует; бизнес-смысл требует подтверждения снимком |
| active/status | `SCL_NAKL` | `STND_UCHET` | `1` для созданного, `0` для мягкой отмены | проектный эксперимент по учитываемости, требует проверки удаления/архива |

## SCL_MOVE

| API-поле | Таблица | Колонка | Правило | Подтверждение |
|---|---|---|---|---|
| `lineId` | `SCL_MOVE` | `RECNO` | генерируется SQL Server IDENTITY; читать через JDBC generated keys, fallback `@@IDENTITY` на том же соединении | live schema/runtime: explicit RECNO insert rejected because IDENTITY_INSERT is OFF; separate `SCOPE_IDENTITY()` call returned NULL |
| `id` | `SCL_MOVE` | `UNICUM_NUM` | ссылка на `SCL_NAKL.UNICUM_NUM` | FK по docs |
| line number | `SCL_MOVE` | `NUM_PREDMT` | следующий номер строки внутри документа | docs: поле строки, требует снимка |
| `sku` | `SCL_MOVE` | `NAME_PREDM` | напрямую | FK: `SCL_ARTC.COD_ARTIC` |
| `warehouseId` | `SCL_MOVE` | `ID_SCLAD` | напрямую | FK: `SCL_ARTC.ID_SCLAD` |
| `documentDate` | `SCL_MOVE` | `DATE_PREDM` | дата документа | существующий mapper читает поле движения |
| account type | `SCL_MOVE` | `TYPDOCM_PR` | константа `C`, должна совпадать с `SCL_NAKL.TYPE_DOC` | docs: `Scl_Move.Typdocm_Pr = Scl_Nakl.Type_Doc`; live schema: `varchar(1)`; live account rows have `C` |
| movement kind | `SCL_MOVE` | `VID_DOC` | константа `*РАЗОВАЯ` для тестового счёта | live account rows for `UNICUM_NUM=753524` |
| `quantity` | `SCL_MOVE` | `KOLTREB_PR` | напрямую | docs: требуемое количество; требует снимка |
| `quantity` | `SCL_MOVE` | `KOLC_PREDM` | напрямую | эксперимент: резерв уменьшается на количество |
| `price` | `SCL_MOVE` | `CENA_PREDM` | напрямую, `BigDecimal` | docs: цена строки; точность требует проверки |
| `amount` | `SCL_MOVE` | `SUM_PREDM` | `price * quantity` | docs: сумма строки; точность требует проверки |
| active/status | `SCL_MOVE` | `STND_UCHET` | `1` для активной строки, `0` при мягкой отмене всего счёта | требует проверки удаления/архива |

## SCL_ARTC

| API-поле | Таблица | Колонка | Правило | Подтверждение |
|---|---|---|---|---|
| `sku` | `SCL_ARTC` | `COD_ARTIC` | поиск товара | docs |
| `warehouseId` | `SCL_ARTC` | `ID_SCLAD` | поиск складской строки | docs |
| physical stock | `SCL_ARTC` | `KON_KOLCH` | только читать, не изменять для счёта | эксперимент |
| available stock | `SCL_ARTC` | `REZ_KOLCH` | создание/добавление/увеличение: минус количество; уменьшение/удаление/отмена: плюс количество | эксперимент |

## Идемпотентность

| API-поле | Таблица | Колонка | Правило | Подтверждение |
|---|---|---|---|---|
| `externalRequestId` | `LAVKA_FOLIO_ACCOUNT_REQUESTS` | `EXTERNAL_REQUEST_ID` | отдельная техническая таблица, не штатная таблица ФОЛИО | требование API |
| created account id | `LAVKA_FOLIO_ACCOUNT_REQUESTS` | `UNICUM_NUM` | ссылка на созданный документ | требование API |

## UNKNOWN / REQUIRES CONFIRMATION

Нельзя заполнять случайными значениями до сравнения реального счёта `UNICUM_NUM = 752327` или другого подтверждённого снимка:

- обязательные `NOT NULL` поля `SCL_NAKL`, если они есть в конкретной базе и не перечислены выше;
- обязательные `NOT NULL` поля `SCL_MOVE`, если они есть в конкретной базе и не перечислены выше;
- поля партии, срока, налогов и служебных идентификаторов строки;
- связь с партнёром/контрагентом `partnerId`;
- необходимость и обязательный набор полей `SCL_ADDN`;
- штатный алгоритм генерации `UNICUM_NUM` и `RECNO`, если он есть в ФОЛИО;
- поведение удаления всего счёта: физическое удаление, архивы `SCL_ARCN/SCL_ARCM/SCL_ARCR`, статусы, возврат резерва.

## Контрольная точка перед production write

Перед включением записи в реальную базу нужно получить:

1. `SELECT * FROM SCL_NAKL WHERE UNICUM_NUM = 752327`;
2. `SELECT * FROM SCL_MOVE WHERE UNICUM_NUM = 752327 ORDER BY NUM_PREDMT, RECNO`;
3. `SELECT * FROM SCL_ADDN WHERE UNICUM_NUM = 752327`;
4. схему `NOT NULL`/default для `SCL_NAKL` и `SCL_MOVE`.

После этого mapping нужно обновить и только затем расширять `INSERT`.
