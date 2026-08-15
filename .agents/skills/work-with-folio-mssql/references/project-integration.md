# Текущая интеграция Java, WooCommerce и ФОЛИО

## Содержание

- [Архитектура данных](#архитектура-данных)
- [Счета](#счета)
- [Партнёры](#партнёры)
- [Документы клиента](#документы-клиента)
- [Медиа товаров](#медиа-товаров)
- [Заказы WooCommerce](#заказы-woocommerce)
- [Конфигурация](#конфигурация)
- [Проверка изменений](#проверка-изменений)
- [Известные зоны риска текущего кода](#известные-зоны-риска-текущего-кода)
- [Безопасность HTTP](#безопасность-http)

## Архитектура данных

Spring Boot 3.2.3 / Java 17 соединяет две системы:

- ФОЛИО на MS SQL через jTDS 1.3.1 — товары, партнёры, остатки, документы, изображения;
- WordPress/WooCommerce на MariaDB — заказы, товары сайта и локальные idempotency-записи некоторых потоков.

`SpringConfig` программно создаёт отдельные bean для обеих баз. В новой Folio-логике всегда ставь `@Qualifier("folioJdbcTemplate")` или `@Qualifier("folioNamedJdbc")` и `@Transactional(transactionManager = "mssqlTransactionManager")`.

Транзакция одного manager не охватывает вторую базу. Для межбазового процесса используй idempotency, порядок commit и безопасное повторение/компенсацию; не обещай распределённую атомарность.

## Счета

Основные файлы:

- `controller/FolioAccountController.java` — прямое API счёта;
- `service/folio/FolioAccountService.java` — serializable apply, preview/read и mutations;
- `service/folio/FolioNumberAllocator.java` — нумерация;
- `dao/folio/FolioAccountDao.java` — SQL и generated keys;
- `property/FolioAccountProperties.java` — подтверждённые defaults;
- `dto/folio/*FolioAccount*.java` — границы контракта;
- `docs/api/FOLIO_ACCOUNT_JS_API.md` и `ACCOUNT_WRITE_MAPPING.md` — полный внешний контракт.

Сервис уже содержит установленные паттерны: `mssqlTransactionManager`, serializable для создания, conditional reserve, lock reads для apply, read-only list/preview и idempotency. Расширяй их, не создавай параллельный «упрощённый» путь вставки.

`SCL_MOVE.RECNO` получай через callback на том же `Connection`: generated keys и fallback `@@IDENTITY`. Не заменяй на отдельный вызов `SCOPE_IDENTITY()`.

Живая таблица `LAVKA_FOLIO_ACCOUNT_REQUESTS` уже содержит:

- `EXTERNAL_REQUEST_ID varchar(64) NOT NULL` — clustered PK;
- `UNICUM_NUM numeric(18,0) NOT NULL` — без FK к `SCL_NAKL`;
- `CREATED_AT datetime NOT NULL DEFAULT getdate()`;
- `REQUEST_HASH varchar(64) NULL`;
- `EXTERNAL_DOCUMENT_NUMBER varchar(64) NULL`.

Java сейчас использует только связь request id → документ. Для payload-aware idempotency DDL не требуется: начни читать/писать существующий hash и возвращай conflict для того же id с другим payload. Ограничь DTO и итоговый Woo-derived key длиной 64, включая суффиксы.

`sourceInfo` текущий Java пишет в поля документа `L_CP1_PLAT`, но не читает и не пополняет справочник `_RECLAMA`. Живая схема подтверждает unique nonclustered PK `_RECLAMA(SIGNIFIC varchar(30) NOT NULL)`, однако FK от документа к справочнику нет. Поэтому база не отвергнет неизвестное значение. Если UI/бизнес-правило требует справочное значение, сначала читай/валидируй его явно; не копируй автоматическое post-commit пополнение из `INSERT_NAKL2` без отдельного решения.

## Партнёры

Основные файлы: `FolioPartnerController`, `FolioPartnerService`, `FolioPartnerDao`, `FolioPartnerItemResponse`, `FolioPartnersResponse`.

Legacy-пагинация DAO специально избегает `OFFSET/FETCH` и `ROW_NUMBER()`. При изменении фильтров сохраняй:

- параметризованный поиск;
- стабильный полный порядок `N_USER`, `NAME_USER`, `NAMEP_USER`;
- корректное исключение предыдущих строк;
- ограничение page size;
- точное отображение `N_USER` как `id/shortName`, `NAME_USER` как полного имени, `NAMEP_USER` как платёжного имени.

Не дополняй response телефоном/городом по неподтверждённой догадке о `_PARTNER_PL`.

## Документы клиента

`FolioCustomerDocumentController`, `FolioCustomerDocumentService` и
`FolioCustomerDocumentDao` дают read-only список и detail счетов, расходных
накладных и платежей клиента. Точная карта экранных подписей, колонок ФОЛИО и
полей API находится в [document-ui-field-map.md](document-ui-field-map.md).

Для краткого `documents[].additionalInfo` действуют разные прямые источники:

- `ACCOUNT`/`EXPENSE` — `SCL_NAKL.L_CP2_PLAT`, как и detail `additionalInfo`;
- `PAYMENT` — `SCL_PLAT.DOCUMN_POR`, как и detail `note`.

Не объединяй это поле с `sourceInfo`, основанием или другими комментариями.
Пустое значение нормализуется в `null`.

Каталог Paint_Ua подтверждает `CRM_DELETE_PARTNER` и `CRM_UPDATE_PARTNER_N_USER`; параметры и dependencies также выгружены. Комментарии говорят, что procedures не транзакционные и транзакция должна находиться на клиенте, но их полное безопасное поведение ещё не сведено в reference. Не вызывай их для обычного чтения и не строй изменение партнёра без отдельного разбора, client transaction, анализа hard-delete/cascade и восстановимой копии. FK `_PARTNER_PL.N_USER -> _PARTNER.N_USER` подтверждён, но правило выбора one-to-many строки — нет.

## Медиа товаров

Основные файлы: `FolioProductMediaController`, `FolioProductMediaService`, `FolioProductMediaDao`, DTO в `dto/folio/media`, а также MariaDB DAO `dao/wp/FolioProductMediaRequestDao`.

Установленные правила:

- main image хранится в `ALL_ARTC.S50`;
- gallery — строки `img_prod` по `PLUS_ARTIC`;
- filename — только точный basename, совместимый с CP1251;
- `previewOnly` сначала показывает изменения;
- apply идемпотентен через MariaDB-таблицу запросов;
- для update gallery идентифицируй строку по `id` и expected old values, потому что реальные дубликаты filename существуют;
- serializable-транзакция MS SQL защищает один apply, но MariaDB idempotency — отдельная система; проектируй повтор после частичного отказа явно.

При вставке `img_prod` fallback `@@IDENTITY` также обязан выполняться на том же соединении; проверь текущий метод перед рефакторингом.

Живая схема подтверждает `ALL_ARTC.PLUS_ARTIC bigint IDENTITY` и `img_prod.id int IDENTITY`, но на обоих `PLUS_ARTIC` нет индекса, unique constraint или FK. Не воспринимай логическую media-связь как обеспеченную базой; измеряй scan перед расширением массовых запросов и не добавляй индекс как побочное изменение без отдельного плана.

Полное `card_tov_export` подтверждает `ALL_ARTC.S50` как main image, но не обращается к gallery `img_prod`; view также не фильтрует active/status. Поле `SCL_SROK.S50P`, встречающееся в `I_GET_FROM_ARTC_TO_NAKL`, не связано с main image. Не переносить это сходство имён в media-модель.

## Заказы WooCommerce

`FolioOrderAccountController` и `FolioOrderAccountService` преобразуют заказ Woo в один или несколько счетов ФОЛИО.

- `processing`/`on-hold` → учитываемый счёт;
- `pc-draft` → неучитываемый;
- `completed` отвергается этим endpoint, потому что он предназначен для создания/учёта, а не постфактум синхронизации;
- склад выбирается по настроенным кандидатам/приоритету;
- отсутствующие позиции могут попасть в отдельный неучитываемый счёт «нет на складе»;
- один и тот же внешний request не должен создавать повторные документы.

Перед изменением этого потока проверяй поведение при частичном наличии, нескольких складах, повторе запроса и конфликте резерва.

## Конфигурация

Используй переменные среды, уже описанные в `application.properties`, но не копируй их текущие defaults в ответы или новые файлы. Важные классы настроек:

- `FolioAccountProperties` — тип документа, вид операции и реквизиты;
- `LavkaApiProperties` — в том числе лимит MS SQL параметров;
- `SpringConfig` — реальные DataSource/pool/transaction beans.

Кириллические defaults в `.properties` записывай Unicode escapes. Значения `VID_DOC`, склад, получатель и режим счётчика должны быть настраиваемыми и подтверждёнными для организации.

## Проверка изменений

1. Добавь unit-тест на ветку сервиса/DAO без живого подключения.
2. Проверь границы строк, CP1251 и кириллические lookalikes.
3. Проверь apply дважды с одним request id и конфликт с изменённым payload.
4. Проверь две конкурентные попытки резерва/номера.
5. Собери проект под Java 17.
6. Только с разрешения выполни preview/read на тестовой копии и затем минимальный apply с контрольными данными.

Общая test-конфигурация проекта исторически не полностью изолирует внешние интеграции. Если `mvn test` поднимает реальный context или упирается в JaCoCo 0.8.7 на локальном JDK 21, не отключай проверки молча: запускай узкий тест, фиксируй инфраструктурную причину и не выдавай её за дефект Folio-кода.

## Известные зоны риска текущего кода

Эти пункты получены статическим аудитом текущей ветки. Перед исправлением перепроверь строки кода, потому что проект мог измениться.

- `REQUEST_HASH varchar(64)` и `EXTERNAL_DOCUMENT_NUMBER varchar(64)` уже есть в `LAVKA_FOLIO_ACCOUNT_REQUESTS`, но Java их не читает/не пишет; повтор с другим телом может вернуть прежний документ вместо conflict. Исправление возможно без DDL.
- `externalRequestId` в DTO не ограничен живым максимумом 64; Woo-поток добавляет суффиксы, поэтому ошибка может возникнуть уже при INSERT idempotency.
- Высокоуровневый Woo retry способен заново распределить позиции уже после изменения остатков. Идемпотентность должна охватывать всю команду до повторного allocation.
- DTO allocations проверяет положительность частей, но сервис должен отдельно доказать, что их сумма равна исходному количеству.
- Путь добавления строки обязан наследовать тип, учёт, реквизиты партнёра/договора и прочие flags существующего документа; текущий код требует отдельной проверки на неоднородные строки.
- Текущий `FolioAccountService.addLine` передаёт организацию `null`, жёстко задаёт учёт/cash flags и не наследует договор из шапки. До исправления не считать этот путь эквивалентным штатной строке `INSERT_NAKL2`.
- Текущий `FolioAccountDao.updateLineQuantity` пересчитывает только `KOLTREB_PR`, `KOLC_PREDM`, `SUM_PREDM`; `SUM_ROZN`, `SUM_VALUT`, `BALL1..5` и другие производные могут остаться несогласованными.
- Текущие `deleteLine` и `cancelHeader`/`cancelLines` выполняют прямой `DELETE` или смену `STND_UCHET`; это не эквивалент подтверждённо более сложной `I_DELETE_NAKL` для партий, оплат, архивов и связанных документов.
- Неиспользуемый сейчас `FolioAccountDao.nextMovementId` вычисляет `MAX(SCL_MOVE.RECNO)+1`, хотя в живой базе `RECNO` — `IDENTITY`. Не подключать этот метод к вставке; generated key получать на том же JDBC connection.
- В старых `SclMoveDaoImpl` и `AssembleDaoImp` встречалась конкатенация списков SKU. Не продолжай этот паттерн: placeholders + chunking ниже 2100.
- Account list повторяет warehouse placeholders в запросе и не всегда ограничивает объём; считай все параметры, а не только длину входного списка.
- Старые мапперы используют `double`/`int` для дробных остатков и SQL `float`; новые пути не должны копировать эту потерю точности.
- `SCL_NAKL.COD_VALUT` в живой схеме — `varchar(4)`, а header-конфигурация Java использует `Integer`; это неявное преобразование и запрет потенциально допустимых нечисловых кодов.
- `partnerId` и часть полей Woo DTO принимаются контрактом, но их фактическое использование нужно подтвердить до обещания поведения API.
- `/sync/stock` в текущей ветке может быть заглушкой; наличие endpoint не доказывает реальную синхронизацию.
- Снимок Paint_Ua от 2026-08-11 подтверждает view `dbo.card_tov_export`; исполняемый `CardTovExportDaoImpl` читает его напрямую. Перед изменением списка колонок/фильтров используй только проверенное очищенное полное определение. Схема `dbo.LAVKA_FOLIO_ACCOUNT_REQUESTS` теперь отдельно подтверждена `01/02/03`-выгрузками.
- `V_FULL_AMOUNT`, `V_SCL_ARTC` и некоторые `V_PRICE_*` агрегируют или складывают `REZ_KOLCH` нескольких складских строк. Не считай quantity из view автоматически остатком одной строки `(COD_ARTIC, ID_SCLAD)`; сначала получи полное определение и сохрани складские фильтры.

Есть и документальные расхождения: краткий `02_FOLIO_ACCOUNT_API.md` всё ещё может говорить, что `SCL_ADDN` не меняется, хотя DAO уже выполняет вставку; ранний JSON-пример полного JS API может не удовлетворять текущей Bean Validation. При конфликте читай controller/DTO/service и обновляй docs вместе с кодом.

## Безопасность HTTP

Перед публикацией `/admin` и `/sync` endpoints проверь фактическую аутентификацию, сетевой периметр и авторизацию. Наличие слова `admin` в path не защищает операцию. Мутирующие endpoints должны иметь аудит request id, безопасные ошибки без секретов и ограничение доступа.

Статический аудит текущей ветки не нашёл входящей Spring Security-конфигурации; защита может существовать только во внешнем proxy, но это нельзя предполагать. До расширения write API подтвердить auth/authz — блокирующее требование.
