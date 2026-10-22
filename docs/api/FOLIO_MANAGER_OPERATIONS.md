# Операции менеджера ФОЛИО

Проверено: 2026-10-22, код и offline tests. Production не развёрнут; live DML и
исполнение расчёта на SQL Server 2000 в этой задаче не выполнялись.

## Источники и решения владельца

Исправленный `Макросы.xls`, SHA-256
`176f0db0fe1ba58aeaca1226dc738e5f52324c0bb3307a93b388d3c9568c53be`:
шесть операций, КиевОпт исключён. SQL/порядок извлечены из каталога
`folio-access-catalog` (770 запросов / 79 макросов); в работу включены только
четыре макроса карточек, SELECT по главному артикулу и «Сборка накладная».

Владелец 22.10.2026 подтвердил:

- только **Paint_Ua**, действия `-RUS` не выполняются;
- описание: отсутствующие ключи добавить, существующие обновить; все APPEND до UPDATE;
- цена готового товара: сумма стоимости списанных компонентов / полученное количество.
  Это явное правило для неоднозначного исходного `СБОРКА 6`, а не утверждение,
  что Access всегда суммировал строки;
- менеджер вводит часть полей сборки; точный набор и заполнение остальных полей
  **ещё уточняет**. Сейчас есть технический вход полной локальной таблицы Access.
  Упрощённый ввод и окончательная приёмка сборки не завершены.

## HTTP

`POST /admin/folio/manager/{preview|apply|status}`. `X-Auth-Token`, constant-time
comparison; отсутствующая конфигурация 503, неверный токен 401; `Cache-Control: no-store`.
Права конечного пользователя, nonce, владелец задания — на WordPress proxy.

Preview: `{"operation":"NAME","rows":[{"COD_ARTIC":"TEST-001","NAME_ARTIC":"Пример"}]}`.
Apply: `{"token":"<preview token>","confirmed":true}`. Status: `{"token":"<preview token>"}`.

| operation | Полный набор ключей rows | Действие |
|---|---|---|
| NAME | COD_ARTIC, NAME_ARTIC | SCL_ARTC.NAME_ARTIC, все склады |
| GROUPS | CODE_ARTIC, NGROUP_TVR, NGROUP_TV2, NGROUP_TV3, NGROUP_TV4, NGROUP_TV5, NGROUP_TV6 | Все шесть групп SCL_ARTC, все склады |
| UNIT | COD_ARTIC, EDIN_IZMER | SCL_ARTC.EDIN_IZMER, все склады |
| DESCRIPTION | COD_ARTIC, DESCRIPTION | ALL_ARTC: INSERT отсутствующих, затем UPDATE всех входных |
| BASE | ARTIC_BAZA | LEFT JOIN ALL_CENA; ARTIC, ARTIC_BAZA |
| ASSEMBLY | ARTIC_roditel, ARTIC_rebenok, KOL_rod_razob, kol_reb_pol, CENA_REB, SLOG | Временные данные; три результата исходного макроса |

Нет API произвольного SQL, таблиц, полей, БД. Колонки задаёт enum.
Строки: 1–500. Все значения — строки или JSON null. Пробелы по краям удаляются,
пустая строка становится NULL. Артикул до 20 CP1251 символов; имя 200, единица 10,
группы 30, описание 5000. Описание допускает переносы/табуляцию; остальные поля
не допускают управляющие символы. Для обновлений повторные артикулы запрещены;
SQL snapshot дополнительно отсекает разные написания одного ключа по collation.
Пустая группа **очищает** поле. Пропущенный ключ запрещён.

BASE сохраняет повторы входа и NULL/NULL для отсутствующего главного артикула.
Никакой DISTINCT или дополнительного отбора по складу. До 5000 строк результата.
Порядок между разными совпадениями не обещается исходным SELECT.

## Preview / apply / восстановление

Ответ: `token`, `status`, `sourceDatabase=Paint_Ua`, `operation`, нормализованный
`input`, `before`, `after`, `outputs`, `missing`, `issues`, `expiresAt`.
Target: `{input: <zero-based index>, sku, warehouse: <int|null>, values: {field: value|null}}`.
Таблица: `{columns:[...], rows:[[string|null,...],...]}`.

- PREVIEW → APPLIED, STALE, EXPIRED, EMPTY или UNKNOWN; промежуточный APPLYING.
- BASE/ASSEMBLY → READY; apply для READY не пишет.
- BLOCKED зарезервирован для явно показанных блокирующих issues.
- before максимум 5000 целевых карточек; missing перечисляет вход без существующей
  строки. Для DESCRIPTION эти артикулы будут вставлены; для NAME/GROUPS/UNIT — пропущены.
- Preview только читает постоянные таблицы. Сборка дополнительно пишет в `#fm_*`
  таблицы tempdb, удаляемые на том же соединении.
- Preview действует 15 минут; до 100 заданий, хранение результатов в памяти 24 часа.
  После рестарта токен теряется. WordPress сохраняет input/before/after/status в audit.
- Apply: shared `FolioAccountingPriceDao.acquireRecalculationMutex`,
  `mssqlTransactionManager`, 180 секунд, `UPDLOCK,HOLDLOCK` snapshot и сравнение
  с preview до записи. Проверяются типы/длины колонок и отсутствие триггеров назначения.
  INSERT отсутствующих описаний использует штатные defaults ALL_ARTC (включая
  FASOVKA_KALM); API не создаёт складских карточек.
- Все записи одной операции в одной транзакции; проверяется количество и каждое
  сохранённое значение. STALE ничего не пишет. Ошибка/неизвестный commit → UNKNOWN:
  никаких автоматических повторов. Один token применяется один раз.
- Восстановление UNKNOWN: status, затем проверка БД администратором по audit.
  Автоматической обратной записи нет: другие пользователи могли изменить карточки.

## Сборка

Источник `Салимон_Запросы_из_Фолио.mdb`, SHA-256
`6972fcfb8ce4702d0a087ccba401fd211d9cf9b8ce9d9b8ddc065dd85b43c59a`.
Порядок: 1, 12, 13, 14, 16, 17, **18**, 2, 3, 4, 5, 6, 7, 8, 9, 91.
Очистки локальных таблиц заменены новыми временными таблицами каждого соединения;
неиспользуемая таблица поиска не создаётся. SELECT 18 читается до шага 2.

`#fm_input`, `#fm_simple`, `#fm_assembly` не являются таблицами ФОЛИО. Нет записи
в SCL_NAKL/SCL_MOVE или вызовов создания накладной. Все сравнения артикулов выполняет
SQL Server с `COLLATE database_default`; постоянные источники читаются в транзакции
REPEATABLE_READ, запросы имеют timeout. Цена/курс берутся строго со склада 7 и по
именам `Я_ЦЕНА_ДОЛ`, `Я_КУРС_ГРН`, как в оригинале.

Колонки локального Access проверены Jackcess read-only:
`KOL_rod_razob` Double, `kol_reb_pol` Integer (16 бит), `CENA_REB` Single,
`SLOG` Integer; их DefaultValue=0. В результатах KOL_R Double, PRICE Single
(DefaultValue=1; запросы передают явные цены). Технический API требует явного SLOG
0/1, не сбрасывает его при запуске. Null и отсутствующая колонка не равны default.
`kol_reb_pol` при вводе округляется half-even и проверяется диапазон smallint;
временные таблицы сохраняют smallint/real. Round в формулах — ties-to-even,
не голый T-SQL ROUND. Из-за float-арифметики нужна контрольная сверка на legacy runtime
на полушагах округления; offline tests этого не доказывают.

После шага 4 стоимость компонентов суммируется один раз на получаемый артикул;
`PRICE = -SUM(component.PRICE * component.KOL_R) / produced.KOL_R`.
Если стоимость хотя бы одного компонента NULL, итог также NULL (не тихая частичная сумма).
Повторный получаемый артикул в сложной сборке отклоняется, чтобы строки не умножали
стоимость друг друга. Неоднозначные связи шага 12/2 отклоняются, а не выбираются случайно.
Сохранён LEFT JOIN шага 8 с ALL_RAZBORKA слева: `ПРОСТАЯ` ставится только совпавшим ART_R.

Outputs: `ASSEMBLY_18` (ARTIC,KOL_R,PRICE,ARTIC_REB,cborka),
`ASSEMBLY_9` и `ASSEMBLY_91` (ARTIC,KOL_R,PRICE,cborka,ARTIC_REB),
`ASSEMBLY_INPUT` — рассчитанный вход для проверки. До 5000 строк на таблицу.
Нулевой делитель/нечисловой SCHET_ART/переполнение останавливают расчёт, постоянные
таблицы не меняются. Окончательная сверка трёх выгрузок с эталоном Access ожидает
подтверждённого менеджерского входа.

## Проверки и запуск

Unit tests: FolioManagerServiceTest, FolioManagerControllerTest,
FolioAssemblyCalculatorTest; контроль транзакции, stale, rollback, повторов,
нового/существующего описания, шести групп, null, CP1251, повторов BASE,
auth, последовательности расчёта и cleanup. SQL sequencing tests используют mocks;
они не являются сравнением чисел с Access или исполнением на SQL Server 2000.
Maven package с пропуском интеграционных тестов. Перед production требуется deploy
Java + WordPress и чтение preview на контрольном наборе; запись — отдельное действие
менеджера. Упрощённый ввод сборки остаётся отдельным незакрытым уточнением.
