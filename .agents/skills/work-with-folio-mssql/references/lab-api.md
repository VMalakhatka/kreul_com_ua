# Локальная SQL-лаборатория Paint_Rus

## Содержание

- [Назначение](#назначение)
- [Граница безопасности](#граница-безопасности)
- [Подключение](#подключение)
- [Выполнение](#выполнение)
- [Интерпретация ответа](#интерпретация-ответа)
- [Экспериментальный цикл](#экспериментальный-цикл)

## Назначение

`folio-rus-lab` — отдельный Java/Docker-модуль для запросов к заброшенной копии `Paint_Rus`. Он не является вторым datasource production-приложения и не принимает server, database, login или JDBC URL из HTTP-запроса.

Используй его, когда требуется:

- проверить фактическую схему/данные копии без ручного `.rpt`;
- получить обезличенный baseline/after;
- воспроизвести один бизнес-процесс ФОЛИО;
- проверить штатную procedure с известным контрактом;
- подтвердить контроль задолженности, партий или учётных цен.

Не используй лабораторию против `Paint_Ua` или другой production-базы.

## Граница безопасности

Приложение на старте и перед запуском проверяет:

- точное `DB_NAME() = 'Paint_Rus'`;
- Microsoft SQL Server 2000 SP3+ (`8.00.760` или новее в пределах major version 8);
- compatibility level 80;
- code page 1251;
- чистый `@@TRANCOUNT`;
- отсутствие membership SQL-login во всех fixed server roles;
- отсутствие доступа SQL-login к любой другой пользовательской БД;
- выключенные server-wide `Cross DB Ownership Chaining` и database-level `Paint_Rus` `db chaining`;
- отсутствие фактического доступа SQL-login к другим рабочим пользовательским БД; согласованные демонстрационные `Northwind` и `pubs` разрешены, но отражаются отдельным счётчиком/предупреждением, а `Paint_Ua` всегда запрещена;
- топологию instance: количество других user databases и linked/remote servers, которое возвращается для диагностики, но само по себе не блокирует запуск.

API блокирует смену базы, многоточечные remote identifiers, dynamic SQL, все пользовательские namespaces `sp_`/`xp_`/`dt_`, external access, server/database administration, выдачу прав и другие escape-команды. Это **best effort**, не самостоятельная security boundary. Для текущей лаборатории согласован общий instance за VPN: обязательны SQL-login без server roles, доступ только к `Paint_Rus`, выключенный cross-database ownership chaining и loopback-only API. Отдельный disposable instance/VM остаётся более сильной будущей изоляцией, но не является условием текущего запуска.

Не открывай и не печатай `.env.folio-rus-lab`. Не запускай `env`, `set`, `printenv`, shell tracing или команды, которые могут вывести token/password. Не вставляй SQL-ответы в Skill/Git до обезличивания и `scripts/check_no_secrets.py`.

## Подключение

Найди корень репозитория и CLI:

```text
<repo>/folio-rus-lab/bin/folio-rus-lab
```

Сервис должен быть уже запущен пользователем либо в текущей задаче после явного сообщения, что VPN подключён. Самостоятельно не меняй VPN, firewall, SQL permissions или `.env`.

Всегда начни:

```text
folio-rus-lab/bin/folio-rus-lab health
folio-rus-lab/bin/folio-rus-lab preflight
```

Остановись, если health/preflight неуспешен, database отличается или guard не пройден. Guard запрещает fixed server role, фактический доступ этого login к другой рабочей пользовательской БД и cross-database ownership chaining. Исключение — только точные имена демонстрационных баз `Northwind` и `pubs`; предупреждение `SQL_LOGIN_CAN_ACCESS_ALLOWED_DEMO_DATABASES` для них ожидаемо. Наличие недоступных login баз или linked/remote server отражается диагностически. Невозможность прочитать защитный параметр означает fail closed. Если когда-либо получен `strictIsolation=false`, считай это нарушением границы и не выполняй SQL.

Для ручного контроля открой `http://127.0.0.1:18081/swagger-ui.html`, нажми `Authorize` и введи только `FOLIO_RUS_API_TOKEN`, без префикса `Bearer`. Swagger не сохраняет token после перезагрузки. Никогда не копируй его в Skill или отчёт.

## Выполнение

Передавай SQL только через UTF-8 файл или stdin; не помещай SQL/секреты в аргументы shell. Для создаваемого временного SQL-файла используй `apply_patch` и `/tmp`, не добавляй его в Git.

Read-only и пробный mutating SQL запускай с режимом по умолчанию:

```text
folio-rus-lab/bin/folio-rus-lab execute --file /tmp/folio-rus-query.sql
```

Это `ROLLBACK`: Java открывает одно физическое jTDS-соединение, ставит внешний transaction boundary и случайный transaction-scoped sentinel, полностью считывает rowsets/update counts и откатывает. Sentinel не позволяет принять новую транзакцию с тем же `@@TRANCOUNT` за исходную после внутреннего `COMMIT`. Не использует `Statement.setMaxRows`, потому что старый jTDS может реализовать его через `SET ROWCOUNT` и повлиять на DML.

Сохраняющий запуск допустим только после явной авторизации точного сценария:

```text
folio-rus-lab/bin/folio-rus-lab execute \
  --file /tmp/folio-rus-experiment.sql \
  --mode COMMIT \
  --allow-persistent-changes
```

`SELF_MANAGED` применять только к заранее разобранной procedure/сценарию с собственным transaction control. Для `COMMIT` и `SELF_MANAGED` API дополнительно требует `allowPersistentChanges=true` и точное подтверждение `Paint_Rus`; CLI добавляет их сам. При коде CLI `3`, `PERSISTENT_OUTCOME_UNKNOWN` или `ROLLBACK_EXECUTION_OUTCOME_UNKNOWN` никогда не повторяй команду: сначала новым соединением выполни минимальный read-only postcondition check.

По умолчанию выполняй один SQL-run одновременно. Задавай минимальные timeout, max rows и max bytes. Не обходи policy и не расширяй SQL-login ради удобства.

## Интерпретация ответа

Ответ не возвращает исходный SQL, только `sqlSha256`. `results` сохраняют порядок `ROWSET` и `UPDATE_COUNT`; числа возвращаются строками, чтобы не терять точность старых `numeric/float` ключей.

Диагностические логи содержат `runId`, `sqlSha256`, этапы выполнения, число результатов/строк/колонок, update counts, объём и итоговое состояние. Они намеренно не содержат SQL-текст и значения ячеек. Не включай подробное логирование JDBC или HTTP body: оно может раскрыть SQL, token и данные ФОЛИО.

Успешные состояния:

- `ROLLED_BACK` — внешний rollback подтверждён;
- `COMMITTED` — commit и чистый `@@TRANCOUNT` подтверждены;
- `SELF_MANAGED_COMPLETED` — сценарий закончил без открытой транзакции.

Стоп-состояния:

- `SQL_FAILED_ROLLED_BACK` — SQL упал, rollback подтверждён;
- `OUTPUT_LIMIT_ABORTED` — результат превысил лимит; не использовать неполный набор как доказательство;
- `TX_BOUNDARY_BROKEN` — batch/procedure изменила границу; изменения могли сохраниться;
- `COMMIT_OUTCOME_UNKNOWN` — потерян достоверный результат commit; не повторять, проверить postconditions новым read-only запросом;
- `SQL_FAILED` — исполнение/соединение не завершилось доказуемо.

Любой warning `*_MAY_HAVE_PERSISTED`, `*_OUTCOME_UNKNOWN` или `DATABASE_CONTEXT_CHANGED` означает: прекратить сценарий, не повторять, снять независимый read-only after и при необходимости восстановить копию.

## Экспериментальный цикл

1. Проверить VPN, backup/restore gate, `health`, `preflight`.
2. Снять минимальный read-only baseline по синтетическим ключам.
3. Сформулировать ожидаемые таблицы, row counts, суммы и UI-результат.
4. Выполнить одно действие сначала в `ROLLBACK`; если процедура ломает boundary, не переходить к commit.
5. Для согласованного сценария выполнить один `COMMIT` либо штатное действие UI.
6. Новым соединением снять after и сверить UI/штатный отчёт.
7. Повторить на восстановленной копии или получить независимую проверку.
8. Перенести только обезличенный инвариант с областью применимости, а не raw JSON/данные.
