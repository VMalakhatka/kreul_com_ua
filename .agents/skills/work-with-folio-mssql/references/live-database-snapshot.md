# Живой снимок Paint_Ua от 2026-08-11

## Содержание

- [Область доказательств](#область-доказательств)
- [Платформа](#платформа)
- [Покрытие выгрузок](#покрытие-выгрузок)
- [Ключи и связи](#ключи-и-связи)
- [Критичные живые схемы](#критичные-живые-схемы)
- [Defaults, параметры и зависимости](#defaults-параметры-и-зависимости)
- [Форма текущих данных](#форма-текущих-данных)
- [Ограничения снимка](#ограничения-снимка)

## Область доказательств

Семь read-only отчётов в `docs/01_*.rpt`…`docs/07_*.rpt` и исправленный `docs/02_index_correct.rpt` описывают конкретную базу `Paint_Ua` на дату выгрузки. Все доступные отчёты проверены `scripts/check_no_secrets.py`: вероятные секреты не найдены. Используй их как более сильный источник, чем старое руководство, но не как бессрочную гарантию перед будущей записью.

`docs/contentr_full.rpt` — отдельная полная выгрузка: 4763 куска покрывают все 222 procedures, 33 views и 7 triggers без разрывов. Сканер нашёл две потенциальные строки с реквизитами подключения в одном объекте `dbo.B_VIEW_SCLAD_GRP1`. Считай raw-файл закрытым: не цитируй, не добавляй в Skill/Git и не передавай дальше. Проверенные обезличенные выводы находятся в [core-procedure-behavior.md](core-procedure-behavior.md).

## Платформа

- Microsoft SQL Server `8.00.760`, Service Pack 3, Developer Edition.
- База `Paint_Ua`, compatibility level `80`.
- Server/database collation `SQL_Ukrainian_CP1251_CI_AS`, code page `1251`.
- Язык исследованной сессии `us_english`, `@@DATEFIRST = 7`; не полагайся на локализованный разбор строковых дат.
- Все 314 символьных поля в 27 исследованных таблицах имеют эту CP1251-collation; Unicode-полей в выборке нет.
- Пользовательских типов данных в исследованной базе не найдено.

Не переноси в Skill точную версию ОС из `@@VERSION`: это инфраструктурная деталь, не нужная для работы с ФОЛИО.

## Покрытие выгрузок

| Отчёт | Подтверждённое покрытие | Ограничение |
|---|---|---|
| `01_live_colums_corect.rpt` | 640 колонок, 27 таблиц, 13 `IDENTITY`, 115 default bindings; без computed/rowguid/Rules; `_RECLAMA` включена | имя файла содержит старые опечатки; `sysindexes.rows` даёт приблизительные оценки; заменяет `01_live_colums.rpt` |
| `02_index_correct.rpt` | 38 индексов / 71 строка ключей, 27 FK / 35 строк колонок, 0 CHECK; `_RECLAMA` включена | cascade/disabled/trusted в отчёт не входили; UQ constraints нет, но есть отдельные unique indexes |
| `03_default_bindings.rpt` | все 209 defaults из каталога: 209 колонок в 56 таблицах, полные определения | default действует при пропуске колонки, но не заменяет явно переданный `NULL` |
| `04_depandenses.rpt` | 784 уникальные связи для 223 из 262 procedures/views/triggers | `sysdepends` неполон; объект без строки зависимости отсутствует из отчёта |
| `05_module_paramert.rpt` | все 222 procedures, 1383 параметра; 30 procedures без параметров | не показывает result sets, return status, defaults параметров, транзакции и side effects |
| `06_referece_code.rpt` | обезличенные типы документов, суффиксы счётчиков и агрегаты | `NOLOCK` и агрегаты дают только форму снимка, не инвариант |
| `07_enveroment.rpt` | версия SQL Server, compatibility, collation, code page и session locale | состояние конкретной инсталляции/сессии |

`01_live_colums_corect.rpt` добавляет к старому отчёту ровно четыре колонки `_RECLAMA` и её приблизительную строку totals; других изменений схемы нет. `02_index_correct.rpt` добавляет ровно PK `_RECLAMA`. Оба пробела закрыты.

## Ключи и связи

Критичные подтверждённые ключи:

| Таблица | Ключ/индекс |
|---|---|
| `SCL_NAKL` | PK `(UNICUM_NUM)`, nonclustered; других индексов нет |
| `SCL_ADDN` | PK `(UNICUM_NUM)`, nonclustered |
| `SCL_MOVE` | PK `(RECNO)`, nonclustered; `RECNO IDENTITY`; индексы `(UNICUM_NUM, NAME_PREDM, ID_SCLAD)` и `(NAME_PREDM, DATE_PREDM, ID_SCLAD)` |
| `SCL_ARTC` | PK `(COD_ARTIC, ID_SCLAD)`, nonclustered |
| `NSF_ORG` | PK `(NAME_USER, ID_SCLAD)` |
| `_PARTNER` | PK `(N_USER)` |
| `_PARTNER_PL` | PK `(RECNO_PARTPLAT)`, `IDENTITY` |
| `_RECLAMA` | единственный индекс `pk__RECLAMA_1`: unique nonclustered PK `(SIGNIFIC)` |
| `ALL_ARTC` | PK `(COD_ARTIC)`; `PLUS_ARTIC bigint IDENTITY`, но без unique/index |
| `img_prod` | единственный индекс — clustered PK `(id)`; `id IDENTITY` |
| `LAVKA_FOLIO_ACCOUNT_REQUESTS` | clustered PK `(EXTERNAL_REQUEST_ID)` |
| `SCL_NS`, `SCL_NR` | PK `(RECNO)` и unique business-key indexes по товару, складу, пользователю, партии и сроку |
| `SCL_SROK` | PK нет; nonunique индекс `(ARTICUL, ID_SCLAD, PARTIA, SROK)` |

Критичные FK:

- `SCL_ADDN.UNICUM_NUM -> SCL_NAKL.UNICUM_NUM`;
- `SCL_MOVE.UNICUM_NUM -> SCL_NAKL.UNICUM_NUM`;
- `SCL_MOVE.(NAME_PREDM, ID_SCLAD) -> SCL_ARTC.(COD_ARTIC, ID_SCLAD)`;
- `SCL_NAKL.ID_SCLAD`, `SCL_MOVE.ID_SCLAD`, `SCL_ARTC.ID_SCLAD -> SCLAD_R.ID_SCLAD`;
- `_PARTNER_PL.N_USER -> _PARTNER.N_USER`; физически это отношение one-to-many, а не гарантированная одна карточка;
- `SCL_NS`, `SCL_NR`, `SCL_PRIC`, `SCL_SROK -> SCL_ARTC` по товару и складу;
- `SCL_ADDP -> SCL_PLAT`; `SCL_PMOV -> SCL_PLAT`, `SCL_NAKL`, `SCLAD_R`, `SCL_ARTC`;
- архивные `SCL_ARCM -> SCL_ARCN`, `SCL_ARTC`, `SCLAD_R`; `SCL_ARCR -> SCL_ARCN`.

FK между `ALL_ARTC` и `img_prod` нет. FK от `LAVKA_FOLIO_ACCOUNT_REQUESTS.UNICUM_NUM` к `SCL_NAKL` тоже нет. Для `_RECLAMA` нет FK в любую сторону и CHECK. На исследованных таблицах нет CHECK constraints и нет UQ constraints; в частности, не подтверждена уникальность `(SCL_MOVE.UNICUM_NUM, NUM_PREDMT)`. Целостность, не покрытая PK/FK/unique index, должна обеспечиваться одной проверенной транзакцией приложения.

## Критичные живые схемы

Подтверждённые поля, которые особенно важны для Java:

- `SCL_NAKL.UNICUM_NUM float(53) NOT NULL`, `N_PLAT_POR float(53) NOT NULL`, `TYPE_DOC varchar(1) NOT NULL`, `VID_DOC varchar(20) NULL`, `DOPN_SCHET varchar(5) NULL`, `STND_UCHET bit NOT NULL`, `ID_SCLAD int NULL`.
- `SCL_MOVE.RECNO int IDENTITY NOT NULL`, `UNICUM_NUM float(53) NOT NULL`, `NUM_PREDMT smallint NOT NULL`, `NAME_PREDM varchar(20) NOT NULL`, `KOLC_PREDM/SUM_PREDM float(53) NOT NULL`, `PARTIA varchar(20) NULL`, `SROK datetime NULL`.
- `SCL_ARTC.COD_ARTIC varchar(20) NOT NULL`, `ID_SCLAD int NOT NULL`, `KON_KOLCH/REZ_KOLCH float(53) NOT NULL`.
- `NSF_ORG.ID_SCLAD int NOT NULL`, `NAME_USER varchar(50) NOT NULL`, `N_3 float(53) NOT NULL DEFAULT 0`.
- `ALL_ARTC.COD_ARTIC varchar(20) NOT NULL`, `S50 varchar(50) NULL`, `PLUS_ARTIC bigint IDENTITY NOT NULL`.
- `img_prod.id int IDENTITY NOT NULL`, `PLUS_ARTIC bigint NULL`, `image varchar(100) NULL`, `sort_order int NULL`.

Точная текущая схема idempotency:

| Колонка | Тип | Ограничение |
|---|---|---|
| `EXTERNAL_REQUEST_ID` | `varchar(64)` | NOT NULL, clustered PK |
| `UNICUM_NUM` | `numeric(18,0)` | NOT NULL, без FK |
| `CREATED_AT` | `datetime` | NOT NULL, `DEFAULT getdate()` |
| `REQUEST_HASH` | `varchar(64)` | NULL |
| `EXTERNAL_DOCUMENT_NUMBER` | `varchar(64)` | NULL |

Колонки `REQUEST_HASH` и `EXTERNAL_DOCUMENT_NUMBER` уже существуют, поэтому payload-aware idempotency можно реализовать без DDL. Текущий Java-код их не читает и не пишет. DTO должен ограничивать итоговый `EXTERNAL_REQUEST_ID`, включая добавляемые Woo-суффиксы, максимумом 64.

## Defaults, параметры и зависимости

Из 209 defaults 189 задают `0`, 11 — `1`, четыре — пустую строку, два — `getdate()`, два — символьные значения и один — `suser_sname()`. Для интеграции особенно подтверждены:

- `NSF_ORG.N_3 = 0`;
- нулевые defaults у 10 колонок `SCL_NAKL`, 21 колонки `SCL_MOVE`, `SCL_ADDN.UNICUM_NUM`, 9 колонок `SCL_ARTC` и количеств `SCL_NS`/`SCL_NR`;
- `ALL_ARTC.FASOVKA_KALM = 1`, `PR_CENA = 0`, `sort_order = 1`;
- `LAVKA_FOLIO_ACCOUNT_REQUESTS.CREATED_AT = getdate()`.

Не превращай нулевой default в бизнес-правило: он лишь позволяет пропустить колонку в конкретной форме INSERT.

Все 588 строковых параметров procedures имеют CP1251-collation; Unicode-параметров нет. Важные сигнатуры:

- `I_NSF_GET_NEW`: 5 параметров, `method` и `nsf` — INOUT;
- `I_GET_LAST_NUMBER`: 9 параметров, номер — INOUT, дата — `char(10)`;
- `I_SET_LAST_NUMBER`: 6 IN; `I_TEST_NUM_NAKL`: 10 IN;
- `INSERT_NAKL2`: 100 параметров, 6 INOUT — это сложный штатный алгоритм, не простой стабильный API;
- `I_DELETE_NAKL`: 7 параметров, `unicum_num` — INOUT; `I_RZV_NEUCH`: 6 IN;
- `A_TR_GET_NS/NR`: по 7 IN; `A_TR_CHANGE_NS/NR`: по 6 IN; количества — `numeric(18,0)`;
- `CRM_UPDATE_PARTNER_N_USER`: два `varchar(8)`; `CRM_DELETE_PARTNER`: id `varchar(8)` и режим hard-delete;
- `ins_partys`: 39 параметров; `I_SAVE_PARTY`: 14.

`sysdepends` подтверждает, но не исчерпывает следующие связи:

- `I_NSF_GET_NEW -> NSF_ORG, SCLAD_R`;
- `I_GET_LAST_NUMBER -> NSF_ORG, SCLAD_R, SCL_NAKL, SCL_ARCN`;
- `I_SET_LAST_NUMBER -> NSF_ORG, SCLAD_R`;
- `INSERT_NAKL2` связан как минимум с 15 объектами, включая `SCL_NAKL`, `SCL_MOVE`, `SCL_ADDN`, `SCL_ARTC`, `SCL_PRIC`, `SCL_SROK`, платежи, партнёров и справочники;
- `I_DELETE_NAKL` связан с 11 таблицами документов/архива/платежей и вызывает `INS_ARC_MOVE`;
- `I_RZV_NEUCH` связан с 9 таблицами и вызывает `ip_add_pmov`, `ip_reras_pmov`;
- `A_TR_GET_NS -> A_TR_CHANGE_NS, SCL_NS, SCL_NR`; `A_TR_CHANGE_NS` меняет `SCL_NS`;
- `A_TR_GET_NR -> A_TR_CHANGE_NR`; `A_TR_CHANGE_NR` меняет `SCL_NR`;
- `card_tov_export -> ALL_ARTC, SCL_ARTC`; `card_tov` также зависит от `ALL_CENA`, `SCL_PRIC`;
- входящих отслеженных связей с `img_prod` и `LAVKA_FOLIO_ACCOUNT_REQUESTS` нет.

Текущий Java-модуль не вызывает эти business procedures через `CallableStatement`/`EXEC`; рассматривай сигнатуры и определения как эталон сравнения логики, а не разрешение начать вызов.

## Форма текущих данных

- В агрегате всех строк `SCL_MOVE` по типу/учётности/cash ни в одной группе не было заполненных `PARTIA` или `SROK`; `SCL_SROK` по приблизительной оценке пуст. Это сильное доказательство формы текущего снимка, но не универсальная гарантия для будущего товара или другой базы.
- В `NSF_ORG` подтверждены глобальные суффиксы `ПN`, `ПU`, `РU`, `СN`, `СU` для `ID_SCLAD=0` и `РU`, `СU` для склада 7. Первые буквы кириллические, вторые — латинские.
- В шапках присутствуют кириллические типы `Б`, `П`, `Р`, `С`. `VID_DOC` имеет много исторических вариантов, включая различия регистра и похожие сокращения; выбирай значение из конфигурации конкретного workflow.
- Оценки строк из `sysindexes.rows` и агрегаты с `NOLOCK` для `ALL_ARTC`/`img_prod` расходятся. Не используй эти числа как контроль целостности или постоянную характеристику базы.
- `_PARTNER_PL` была приблизительно пустой; это не отменяет подтверждённый FK и возможность нескольких строк на партнёра в будущем.
- `_RECLAMA`: `SIGNIFIC varchar(30) NOT NULL` с CP1251 collation; `PLANIR float(53) NULL`, `CHAR10 varchar(10) NULL` CP1251, `INT1 int NULL`; identity/computed/default/Rule нет.

## Ограничения снимка

- Перед mutation повтори метаданные целевых таблиц и triggers: схема могла измениться после 2026-08-11.
- Не выводи side effects только из `sysdepends`: dynamic SQL, временные таблицы, cross-database и устаревшие записи делают граф неполным.
- Не выводи result set или return contract только из `INFORMATION_SCHEMA.PARAMETERS`.
- Не используй approximate/NOLOCK counts для сверки миграции; для точного числа нужен согласованный `COUNT(*)` на разрешённой копии или в согласованном окне.
- Снимок относится к Paint_Ua. Перед экспериментом на Paint_Rus повтори metadata/schema drift preflight: копия может иметь другую версию объектов и данные.
