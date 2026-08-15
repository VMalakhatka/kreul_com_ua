# Views, procedures, triggers и функции ФОЛИО

## Содержание

- [Что подтверждают снимки](#что-подтверждают-снимки)
- [Снимок Paint_Ua от 2026-08-11](#снимок-paint_ua-от-2026-08-11)
- [Параметры, defaults и зависимости](#параметры-defaults-и-зависимости)
- [Чем эти объекты полезны](#чем-эти-объекты-полезны)
- [Безопасная инвентаризация SQL Server 2000](#безопасная-инвентаризация-sql-server-2000)
- [Чтение одного объекта](#чтение-одного-объекта)
- [Полная пакетная выгрузка без обрезания](#полная-пакетная-выгрузка-без-обрезания)
- [Как анализировать](#как-анализировать)
- [Приоритет для текущей интеграции](#приоритет-для-текущей-интеграции)
- [Секреты и публикация](#секреты-и-публикация)
- [Ограничения доказательств](#ограничения-доказательств)

## Что подтверждают снимки

Снимок дерева Paint_Ua показывает разделы Views, Stored Procedures, Users, Roles, Rules, Defaults, User Defined Data Types, User Defined Functions и Full-Text Catalogs. Это прямое подтверждение наличия таких классов объектов, но не полный список и не доказательство их использования текущим клиентом.

Частичный список stored procedures содержит, среди прочих:

- `_DELETE_ALL_IDX`, `_DELETE_ALL_TABLE` — по имени потенциально разрушительные maintenance-объекты; не выполнять;
- `A_TR_CHANGE_NR`, `A_TR_CHANGE_NS`, `A_TR_GET_NR`, `A_TR_GET_NS` — по одному снимку связь с `SCL_NR`/`SCL_NS` была только гипотезой; экспорт ниже её уточняет;
- `A_POTBOR_PARTIA*` — возможный выбор партий/остатков, только гипотеза;
- `A_SEL_PRIC_TV`, `A_SEL_PRIC_TVR` — возможная ценовая логика, только гипотеза;
- `B_ACCEP_*` — возможные accept-процессы, назначение требует отдельного разбора;
- `B_ALL_CALC_ARTIC` подтверждён как перерасчёт физического/свободного остатка, а `B_ARTC_VRECALC` — как перерасчёт валютных продажных цен; ни один из них не является штатным перерасчётом учётной себестоимости. Подробности смотри в [debt-and-accounting.md](debt-and-accounting.md).

Одинаковая дата создания у большого числа объектов может отражать restore/deploy, а не дату разработки бизнес-логики.

## Снимок Paint_Ua от 2026-08-11

`docs/vpr.rpt` — read-only экспорт `dbo.sysobjects` текущей Paint_Ua. Он подтверждает 471 объект, все с владельцем `dbo`:

| Тип | Количество |
|---|---:|
| stored procedure | 222 |
| view | 33 |
| trigger | 7 |
| default | 209 |
| UDF `FN`/`IF`/`TF` | 0 |
| rule | 0 |

У всех строк `is_encrypted = 0`. Это означает, что определения можно получить штатными средствами, но не разрешает публиковать их без проверки секретов.

Все семь triggers относятся только к CRM-таблицам:

| Trigger | Родительская таблица |
|---|---|
| `CRM_CONTACT_DELETE` | `CRM_CONTACTS` |
| `CRM_CONTACT_INSERT` | `CRM_CONTACTS` |
| `CRM_CONTACT_UPDATE` | `CRM_CONTACTS` |
| `Groups_Delete` | `CRM_PRODUCTGROUPS` |
| `insert_groups` | `CRM_PRODUCTGROUPS` |
| `update_groups` | `CRM_PRODUCTGROUPS` |
| `update_our_dopprice` | `CRM_OURDOPPRICE` |

В этом снимке нет triggers на `SCL_NAKL`, `SCL_MOVE`, `SCL_ADDN`, `SCL_ARTC`, `SCL_NS`, `SCL_NR`, `NSF_ORG`, `_PARTNER`, `ALL_ARTC` или `img_prod`. Это факт конкретного снимка, а не бессрочная гарантия: перед production-записью повтори каталогизацию.

Из 33 views особенно релевантны:

- `card_tov_export` — напрямую читается текущим `CardTovExportDaoImpl`; при изменении товарного API полное определение этого view имеет наивысший приоритет;
- `card_tov`, `V_FULL_AMOUNT`, `V_SCL_ARTC` — товар, цены и агрегированные остатки;
- `V_PRICE_*`, `v_PRICE_FOR_SITE`, `V_Cena_Magazin` — семейство ценовых представлений;
- `V_RAZBORKA*`, `V_OBR*`, `V_ZakImp`, `V_ZakUkr` — локальные товарные/заказные расчёты, назначение подтверждай полным текстом.

Видимые фрагменты старого сокращённого отчёта дополнительно подтверждают:

- `card_tov` читает как минимум `SCL_ARTC`, `ALL_CENA` и `ALL_ARTC`;
- полное короткое view `V_Cena_Magazin` возвращает `SCL_ARTC.COD_ARTIC` и `UCHET_CENA AS RUB_PRICE` для `ID_SCLAD = 7`;
- `V_FULL_AMOUNT` начинает агрегировать `SUM(REZ_KOLCH)`, а `V_SCL_ARTC` складывает `REZ_KOLCH` двух alias; неполный `WHERE` не позволяет копировать их как каноническую формулу;
- `V_RAZBORKA` связывает локальную `ALL_RAZBORKA` с двумя строками `ALL_ARTC` через `PLUS_ARTIC`;
- `V_Razmeri_Tovara` вычисляет объём из трёх размеров `/ 1000000000` и вес как `BALL3 / 1000`, но конец фильтра обрезан;
- определения `CRM_DELETE_PARTNER` и `CRM_UPDATE_PARTNER_N_USER` помечены автором как нетранзакционные, с транзакцией на клиенте. Полное тело перед mutation обязательно.

Полное определение `card_tov_export` теперь разобрано: view возвращает 21 поле для склада 7, включает `ALL_ARTC.S50` как main image, но не содержит gallery `img_prod`, цены, остаток, active/status filter или гарантированный порядок. Подробности и ограничения находятся в [core-procedure-behavior.md](core-procedure-behavior.md).

`A_TR_GET_NS`, `A_TR_CHANGE_NS`, `A_TR_GET_NR`, `A_TR_CHANGE_NR` имеют тип procedure, а не trigger. Полный разбор подтверждает, что GET вызывает CHANGE и мутирует `SCL_NS`/`SCL_NR` без атомарной проверки; количества имеют `numeric(18,0)`, а NR содержит дефект party-ветки. Не вызывай это семейство без воспроизведения штатного сценария на копии базы.

`docs/contentr.rpt` содержит 590 строк `dbo.syscomments` для тех же 471 объектов, но его текстовый столбец был обрезан клиентом примерно до 255 символов на строку. У длинных модулей сохранены лишь начала отдельных 4000-символьных частей. Поэтому найденный фрагмент является доказательством, а отсутствие имени/операции — нет. Этот файл нельзя использовать для восстановления полного алгоритма, параметров или side effects.

`docs/contentr_full.rpt` содержит 4763 куска для всех 262 procedures/views/triggers из каталога; дубликатов и разрывов частей нет. Для восстановления дополняй не последний кусок до 200 символов: текстовый отчёт удалил граничные пробелы. Текущая raw-копия не прошла secret scan: обе потенциальные строки с реквизитами подключения относятся к `dbo.B_VIEW_SCLAD_GRP1`. Не цитируй их и не используй raw как переносимое знание. Для Skill допустимы только проверенные обезличенные выводы; файл должен оставаться вне Git.

## Параметры, defaults и зависимости

Полные структурные итоги находятся в [live-database-snapshot.md](live-database-snapshot.md). Практически важное:

- `03_default_bindings.rpt` полностью сопоставляет 209 defaults с 209 колонками; UDT-bindings нет. Большинство значений — технические `0/1`, которые не доказывают корректное бизнес-значение.
- `05_module_paramert.rpt` охватывает все 222 procedures: 1383 параметра, 30 procedures без параметров. Все строковые параметры — CP1251 `varchar/char`, Unicode нет.
- `INSERT_NAKL2` имеет 100 параметров, из них 6 INOUT; это сильный признак сложного внутреннего алгоритма, а не удобного стабильного API для Java.
- `I_NSF_GET_NEW` имеет 5 параметров, `I_GET_LAST_NUMBER` — 9, `I_SET_LAST_NUMBER` — 6, `I_TEST_NUM_NAKL` — 10. У GET/allocator-процедур есть INOUT, поэтому контракт нельзя восстанавливать только по имени.
- `A_TR_GET_NS/NR` имеют по 7 входных параметров, CHANGE-пары — по 6; параметр `Amount` у GET не output, поэтому результат нужно искать в result set/return contract полного текста.
- `04_depandenses.rpt` содержит 784 уникальные связи для 223 из 262 программируемых объектов. Отсутствие остальных 39 — ожидаемо: объект без записи `sysdepends` не попадает в отчёт.
- `INSERT_NAKL2` связан с документами, строками, дополнениями, остатками, ценами, сроками, платежами и партнёрами; `I_DELETE_NAKL` — с активными/архивными документами и платежами; `I_RZV_NEUCH` вызывает перераспределение платежных движений. Не подменяй эти процессы прямым DML без отдельного доказательства эквивалентности.
- `card_tov_export` зависит от `ALL_ARTC` и `SCL_ARTC`; `card_tov` также от `ALL_CENA` и `SCL_PRIC`. Входящих отслеженных зависимостей с `img_prod` и `LAVKA_FOLIO_ACCOUNT_REQUESTS` нет, но dynamic SQL всё ещё возможен.

Текущий Java-модуль не вызывает эти procedures через `CallableStatement`/`EXEC`. Используй их определения для сравнения бизнес-инвариантов, а не как автоматическую рекомендацию перейти на stored procedures. Безопасные карточки уже разобранных объектов находятся в [core-procedure-behavior.md](core-procedure-behavior.md); raw-файл повторно читать ради обычной задачи не нужно.

## Чем эти объекты полезны

В порядке практической ценности:

1. **Triggers** — показывают скрытые записи, аудит и влияние на `@@IDENTITY`; обязательны перед production INSERT/UPDATE.
2. **Views** — раскрывают канонические JOIN, фильтры активных/архивных строк и формулы отчётов.
3. **Stored procedures** — могут содержать полный штатный транзакционный алгоритм, но также временные таблицы, session state и опасные mutations.
4. **UDF** — раскрывают повторно используемые расчёты, декодирование и округление.
5. **Rules/Defaults/UDT** — уточняют допустимые значения и наследуемые defaults старой схемы.

Наличие штатной procedure не означает, что Java должна её вызывать. Сначала проверь входы, outputs, transaction handling, права, temp tables, side effects, error signalling и совместимость с jTDS.

## Безопасная инвентаризация SQL Server 2000

Выполняй только после разрешения на read-only metadata access. Для SQL Server 2000 владелец объекта выполняет роль, близкую к современному schema; сохраняй полное имя как `owner.object`. Этот запрос перечисляет пользовательские объекты и не запускает их:

```sql
SELECT USER_NAME(o.uid) AS object_owner,
       o.name AS object_name,
       CASE o.xtype
           WHEN 'V'  THEN 'VIEW'
           WHEN 'P'  THEN 'PROCEDURE'
           WHEN 'TR' THEN 'TRIGGER'
           WHEN 'FN' THEN 'SCALAR_FUNCTION'
           WHEN 'IF' THEN 'INLINE_TABLE_FUNCTION'
           WHEN 'TF' THEN 'TABLE_FUNCTION'
           WHEN 'R'  THEN 'RULE'
           WHEN 'D'  THEN 'DEFAULT'
       END AS object_type,
       o.crdate AS create_date,
       OBJECTPROPERTY(o.id, 'IsEncrypted') AS is_encrypted,
       CASE WHEN o.xtype = 'TR' THEN USER_NAME(p.uid) ELSE NULL END AS target_owner,
       CASE WHEN o.xtype = 'TR' THEN p.name ELSE NULL END AS target_object
FROM dbo.sysobjects o
LEFT JOIN dbo.sysobjects p ON p.id = o.parent_obj
WHERE o.xtype IN ('V', 'P', 'TR', 'FN', 'IF', 'TF', 'R', 'D')
  AND OBJECTPROPERTY(o.id, 'IsMSShipped') = 0
ORDER BY object_type, object_owner, object_name
```

Коды: `V` view, `P` SQL stored procedure, `TR` trigger, `FN` scalar UDF, `IF` inline table-valued UDF, `TF` table-valued UDF, `R` old-style Rule, `D` Default/DEFAULT constraint. User Defined Data Types находятся не в этом наборе объектов; исследуй их отдельно через `sp_help`/`dbo.systypes` только по конкретной задаче.

Используй именно SQL Server 2000-интерфейсы `dbo.sysobjects`, `dbo.syscomments`, `dbo.sysdepends`, `sp_helptext` и `sp_depends`. Не подменяй их отсутствующими на сервере 2000 `sys.objects`, `sys.sql_modules` или `OBJECT_DEFINITION`.

Не запрашивай `Users`/`Roles` вместе с каталогом знаний: имена учётных записей и grants относятся к security-аудиту, а не к бизнес-схеме.

## Чтение одного объекта

Сначала метаданные, затем определение:

```sql
EXEC sp_help N'dbo.OBJECT_NAME'
EXEC sp_helptext N'dbo.OBJECT_NAME'
```

Параметры procedure/function можно получить отдельно:

```sql
SELECT PARAMETER_NAME, ORDINAL_POSITION, PARAMETER_MODE,
       DATA_TYPE, CHARACTER_MAXIMUM_LENGTH,
       NUMERIC_PRECISION, NUMERIC_SCALE
FROM INFORMATION_SCHEMA.PARAMETERS
WHERE SPECIFIC_SCHEMA = 'dbo'
  AND SPECIFIC_NAME = 'OBJECT_NAME'
ORDER BY ORDINAL_POSITION
```

Зависимости как вспомогательная подсказка:

```sql
EXEC sp_depends N'dbo.OBJECT_NAME'
```

`sp_helptext` возвращает определение строками по 255 символов; сохраняй порядок и собирай их без добавления собственной пунктуации. Для точечного машинного экспорта того же объекта допустимо чтение частей `dbo.syscomments`:

```sql
SELECT c.number, c.colid, c.text
FROM dbo.syscomments c
WHERE c.id = OBJECT_ID(N'dbo.OBJECT_NAME')
  AND c.encrypted = 0
ORDER BY c.number, c.colid
```

`sp_depends`/`dbo.sysdepends` неполны при dynamic SQL, временных таблицах, позднем создании объектов и cross-database именах; внешние базы `sp_depends` не показывает. Перепроверь все имена непосредственно в тексте. Если `OBJECTPROPERTY(..., 'IsEncrypted') = 1`, `dbo.syscomments.encrypted = 1` или `sp_helptext` сообщает encryption, зафиксируй объект как закрытый и не пытайся обходить шифрование.

Не вызывай объект даже с `NULL`, тестовыми параметрами или внутри `ROLLBACK`: procedure может выполнять DDL, внешние вызовы, nested transaction либо необратимое действие. Не используй для каталогизации `sp_refreshview`, `sp_recompile`, `DBCC`, `xp_cmdshell`, OLE Automation или попытки «починить» dependency metadata.

## Полная пакетная выгрузка без обрезания

Query Analyzer и другие старые клиенты могут обрезать широкий `syscomments.text`. Следующий read-only запрос делит каждый исходный блок максимум 4000 символов на части по 200 символов; такой результат можно сохранить как `content_full.rpt` даже при старом лимите ширины колонки:

```sql
SELECT USER_NAME(o.uid) AS object_owner,
       o.name AS object_name,
       o.xtype AS object_type,
       c.number AS module_number,
       c.colid AS source_fragment_number,
       n.part_no,
       SUBSTRING(c.text, ((n.part_no - 1) * 200) + 1, 200)
           AS definition_fragment
FROM dbo.sysobjects o
JOIN dbo.syscomments c ON c.id = o.id
CROSS JOIN (
    SELECT 1 AS part_no UNION ALL SELECT 2 UNION ALL SELECT 3
    UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
    UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
    UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
    UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
    UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18
    UNION ALL SELECT 19 UNION ALL SELECT 20
) n
WHERE o.xtype IN ('V', 'P', 'TR', 'FN', 'IF', 'TF')
  AND OBJECTPROPERTY(o.id, 'IsMSShipped') = 0
  AND c.encrypted = 0
  AND DATALENGTH(c.text) > ((n.part_no - 1) * 400)
ORDER BY USER_NAME(o.uid), o.name,
         c.number, c.colid, n.part_no
```

Собирай определение строго по `module_number`, `source_fragment_number`, `part_no`. Не сортируй по тексту и не вставляй собственные разделители. Для нескольких приоритетных объектов надёжнее отдельно сохранить вывод `EXEC sp_helptext N'dbo.OBJECT_NAME'`.

Перед анализом пакетного файла:

1. держи raw-экспорт вне Git;
2. запусти `python3 .agents/skills/work-with-folio-mssql/scripts/check_no_secrets.py /закрытый/raw/content_full.rpt`;
3. если есть находка, немедленно прекрати публикацию, не показывай значение и создай очищенную копию в отдельном пути;
4. только после повторной проверки используй очищенный текст как переносимое знание.

## Как анализировать

Для каждого объекта зафиксируй:

- owner, type, имя и родитель trigger;
- параметры, result sets/return code и используемые temp tables;
- читаемые и изменяемые таблицы;
- transaction statements, isolation, locking hints и error signalling;
- generated keys, triggers и дополнительный audit;
- кириллические literals, code page, date parsing и dynamic SQL;
- вызывающие объекты/Java-код и evidence фактического использования;
- статус: read-only, mutation, maintenance/destructive либо unknown.

Views анализируй как формулу чтения, но не считай их автоматически обновляемыми. Для procedure отдельно отличай бизнес-операцию от repair/recalc/admin routine.

## Приоритет для текущей интеграции

Не выгружай сотни определений в Skill. Сначала исследуй объекты, которые касаются:

1. `SCL_NAKL`, `SCL_MOVE`, `SCL_ADDN`, `SCL_ARTC`, `NSF_ORG` и generated keys;
2. `_PARTNER` и связанных справочников;
3. `ALL_ARTC`, `img_prod`, `SCL_PRIC`, партий и резервов;
4. triggers на таблицах, куда уже пишет Java;
5. views/procedures, на которые ссылается исполняемый код или отчёт ФОЛИО.

Для релевантного объекта добавляй краткую карточку в `docs/00_DATABASE_CATALOG.md` либо отдельный очищенный SQL в `docs`, а в Skill сохраняй только устойчивые выводы и путь к источнику.

Для текущего Java-модуля эти приоритетные определения уже разобраны и сведены в `core-procedure-behavior.md`. Повторно выгружай/сравнивай их после изменения базы в таком порядке:

1. `card_tov_export`;
2. `I_NSF_GET_NEW`, `I_GET_LAST_NUMBER`, `I_SET_LAST_NUMBER`, `I_TEST_NUM_NAKL`;
3. `INSERT_NAKL2`, `I_RZV_NEUCH`, `I_DELETE_NAKL`, `i_sch2nakl`, `i_uchet_add`;
4. `A_TR_GET_NS`, `A_TR_CHANGE_NS`, `A_TR_GET_NR`, `A_TR_CHANGE_NR`;
5. `ins_partys`, `I_SAVE_PARTY`, `A_POTBOR_PARTIA*`; эти определения разобраны, а `K_POTBOR_PARTIA*` исследуй отдельно только по конкретной задаче;
6. `I_DOLG_DOC`, `I_DOLG_HIS`, `I_PERERS_UCH`, `I_UCHET_TOVAR`, `I_UCHET_1_TOVAR`, `I_SET_UCH_PRICE`, `B_ALL_CALC_ARTIC`, `B_ARTC_VRECALC`; безопасные выводы находятся в `debt-and-accounting.md`;
7. остальные views/procedures цен, партнёров и локального обмена только по конкретной задаче.

По имени считай `_DELETE_ALL_IDX`, `_DELETE_ALL_TABLE`, `B_DELETE_SCLAD`, `I_DELETE_*` и `I_DEL_ARC_NAKL` потенциально разрушительными. Даже их полный текст исследуй только чтением; никогда не запускай ради проверки.

## Секреты и публикация

Определение SQL может содержать hardcoded пароль, токен, server/IP, connection string, UNC path, имя инфраструктурной учётной записи или адрес внешней базы.

Безопасный поток:

1. выгрузить raw-определения во временную папку вне Git;
2. запустить `python3 .agents/skills/work-with-folio-mssql/scripts/check_no_secrets.py <папка>`;
3. вручную проверить flagged-файлы, не копируя найденные значения в чат;
4. заменить чувствительные литералы нейтральными placeholders в отдельной очищенной копии;
5. повторно запустить проверку;
6. добавить в проект только очищенную копию и минимальные выводы.

Не удаляй все строковые literals автоматически: среди них находятся кириллические типы документов и бизнес-правила. Очищай только инфраструктурные секреты, сохраняя смысл алгоритма.

## Ограничения доказательств

- Имя объекта — подтверждение существования, но не назначения.
- Текст определения — подтверждение написанного алгоритма, но не факта вызова.
- Dependency metadata — подсказка, не полный граф.
- Create date — дата объекта в этой базе, не обязательно дата исходного кода.
- Фактический вызов подтверждается trace/audit или caller-кодом; trace на production требует отдельного разрешения и оценки нагрузки.
- Любую мутацию сначала воспроизводи на восстановимой копии и сверяй с UI ФОЛИО.
