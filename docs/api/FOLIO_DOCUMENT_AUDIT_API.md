# Независимый аудит заполнения денежных документов ФОЛИО

Статус: реализация Java, версия правил `2026-09-09.1`; локальная сборка и тесты
пройдены. Живая выборка нового DAO и production-деплой ещё не проверены.
Это новый отчёт качества, не изменение расчёта «Прибыли Лавки». Только SELECT.

## Источник правил и пределы

Основание: предоставленная владельцем книга «ПРАВИЛА ВНЕСЕНИЯ движения денег.xls»,
листы `расход`, `приход`, `отборы`, `алгоритм сверки`. Книга содержит персональные
и платёжные реквизиты, поэтому не включена в Git. В код перенесены только
обезличенные требования и номера строк. Названия некоторых организаций в книге
не содержат краткого кода: алиасы взяты из существующего Java-классификатора и
помечены в `rules.evidence`, а не объявлены живой сверкой справочника.

Пользователь явно подтвердил обязательный `IST_INF=зп` для зарплаты. Пустой
источник в зарплате — ERROR в этом аудите, **не основание менять действующий
отбор прибыли**. Синтетический тест повторяет форму кейса: документ августа
с начислением за июль и пустым источником, без публикации персональных данных.

`№1`, `№2`, `№1 и Н` и условные требования не трактуются как обязательная
буквальная строка: `RULE_REVIEW`. Текущие названия типов операций могут отличаться
от книги: несовпадение — `RULE_REVIEW`, отсутствие обязательного типа — `ERROR`.
Банковская аренда может относиться к исключению строки17: прежде исправления
источника нужна проверка. Налог/перевод/сторно не превращаются в обычную зарплату.

Не реализованы: товарные накладные, сверка с банковской выпиской, пары переводов,
автор и дата создания/коррекции, приложение и основание документа (колонки
листа `алгоритм сверки`, строка51; в новый DAO без подтверждения не добавлены),
идентичность реального контрагента, исторические привязки касс/карт/ФОП к складам,
полный набор правил для всех направлений и категорий. Лист3 с01.09.2026 не
распространяется на июль; «Старый Лист3 не использовать» не используется.
`VALID` означает только прохождение реализованных проверок, не правильность
всей операции. `coverage.rulesComplete=false` возвращается честно всегда в v1.

## Endpoint

```http
GET /admin/folio/document-audit?dateFrom=2026-08-01&dateTo=2026-08-31&pageSize=200
```

- `dateFrom`, `dateTo`: обязательные ISO даты, обе включительно, максимум366дней.
  SQL: `DATE_P_POR >= dateFrom AND < dateTo+1day`.
- `pageSize`: 1..500, default200.
- `afterPaymentId`: default0; следующая страница получает `page.nextAfterPaymentId`.
- `upperPaymentId`: верхний ID первой страницы; обязателен при afterPaymentId>0.
- `expectedRulesVersion`: передавайте rulesVersion первой страницы; изменение
  правил вернёт `DOCUMENT_AUDIT_RULES_CHANGED`, нужно начать новый аудит.

Нет фильтра прибыли `TYPE_POR=0`, склада, известной категории или проведённости.
Читаются **все SCL_PLAT по дате документа**, оба направления, касса/банк, включая
неизвестные признаки. Документ августа за июль будет в августовском аудите.
Это не отчёт начислений; период примечания не расширяет дату выборки.

## Ответ

Верхний уровень: `ok`, `status=PAGE_READY`, `rulesVersion`, `calculatedAt` (ISO),
`dateFrom`, `dateTo`, `coverage`, `page`, `summary`, `rules[]`, `items[]`,
`errorCode`, `errorId` (null при успехе).

```json
{
  "page": {
    "pageSize": 200,
    "afterPaymentId": 0,
    "upperPaymentId": 12345,
    "nextAfterPaymentId": 9876,
    "hasMore": true,
    "totalDocuments": 820
  },
  "summary": {
    "scope": "PAGE",
    "examined": 200,
    "byStatus": {"VALID": 150, "ERROR": 20, "RULE_REVIEW": 30},
    "byCategory": {"SALARY": 10, "UNCLASSIFIED": 30, "CUSTOMER_PAYMENT": 160}
  }
}
```

Числа примера иллюстративны; category counts реального ответа суммируются в
examined. Summary относится только к странице. `totalDocuments` — count всех
документов диапазона до upperPaymentId, не число ошибок и не число уже проверенных.
Последняя страница: hasMore=false, nextAfterPaymentId=null.

`coverage`:

- source=`SCL_PLAT`, dateBasis=`DOCUMENT_DATE_INCLUSIVE`, allWarehouses=true;
- directions=`INCOMING,OUTGOING,UNKNOWN`, registers=`CASH,BANK,UNKNOWN`;
- consistency=`LIVE_NOT_SNAPSHOT`;
- pageComplete: успешность чтения страницы; rulesComplete=false;
- unsupported[] и warnings[] — явные ограничения покрытия.

`items[]`:

- `document`: paymentId (стабильный ключ), documentNumber (не уникален),
  documentDate, warehouseId, bank (true банк/false касса/null неизвестно),
  direction (`INCOMING`: TYPE_POR=true; `OUTGOING`: false; `UNKNOWN`: null),
  amount (decimal строка или null), currencyCode (сырое COD_VALUT/null),
  organizationCode (ORG_PREDM), organizationName (L_NAME_POR), purposeCode
  (CODCEL_POR), operationType (VID_DOC), sourceInfo (IST_INF), note (DOCUMN_POR),
  periodEvidence, resolvedPeriod (YYYY-MM только для однозначного маркера),
  amountField=`SCL_PLAT.SUM_POR`, amountCurrencyStatus=`NOT_CONFIRMED_FROM_COD_VALUT`,
  sensitiveValuesMasked.
- `category`: code, label, recognition=`RECOGNIZED|UNRECOGNIZED`, profitTreatment.
  `KNOWN_NON_OPERATING_CATEGORY` обозначает расчёты с поставщиком, клиентом или
  внутренний перевод; это не неопознанная категория и не новый отбор прибыли.
  Для прочих `NOT_RECALCULATED_BY_AUDIT`.
- `status`: `VALID|ERROR|RULE_REVIEW`, приоритет ERROR над RULE_REVIEW.
- `ruleIds[]`; `findings[]`: code, severity (`ERROR|RULE_REVIEW`), field, actual,
  expected, recommendation, ruleId. Даже при ERROR сохраняется известная category.

Сумма не конвертируется и не складывается между документами: COD_VALUT само по
себе не подтверждает единицу SUM_POR (в проекте есть особые валютные соглашения).
NULL не подменяется нулём. Не стройте сумму разных валют на фронте.

`rules[]`: id, categoryCode, label, sourceSheet, sourceRows[], evidence,
requiredSourceInfo, periodRequirement (`REQUIRED|CONDITIONAL|NONE`),
expectedOperationTypes[], organizationCodes[], limitations[]. Это частичный
каталог реализованных проверок. COMMON/PERIOD/COVERAGE также документированы.

## Пагинация, экспорт, безопасность

Состав первой страницы фиксирует upperPaymentId, а не снимок данных.
Все чтения NOLOCK, поэтому редактирование/удаление/незавершённые транзакции
между страницами возможны. Фронт должен проверять возрастающий курсор, одинаковые
границы/версию, отсутствие повторов paymentId. Не называть экспорт неизменяемым
снимком. Сохранять паспорт LIVE_NOT_SNAPSHOT, дату чтения, параметры, версию,
покрытие и фактически загруженное число строк; при сбое явно неполный экспорт.

Полные note/organizationName предназначены **только закрытому server-side
админ-реестру**. Не публиковать API, не записывать ответы/примечания в логи,
не включать реальные документы в fixtures. Длинные числовые реквизиты в кодах
purpose/source/organization маскируются, но `sensitiveValuesMasked` не означает,
что весь документ безопасен для публикации: полное note может содержать реквизиты.
Номер/дата/paymentId позволяют оператору найти документ без автоизменений.

Ошибка источника: HTTP503, ok=false, status=SOURCE_UNAVAILABLE,
errorCode=DOCUMENT_AUDIT_SOURCE_UNAVAILABLE, errorId для безопасного Java-лога,
page=null, summary=null, coverage.pageComplete=false, items=[] (не «0 документов»).
Неверные параметры возвращаются через общий контракт валидации.
У валидационных HTTP400 код находится в `code`, не `errorCode`, пояснение в
`message`; это относится также к `DOCUMENT_AUDIT_DISABLED` и
`DOCUMENT_AUDIT_RULES_CHANGED`. Фронт должен учитывать обе формы ответа.

Направление здесь всегда исходное TYPE_POR. Переосмысление прихода/расхода
относительно выбранного ФОП из строк62–65 листа `алгоритм сверки` относится к
отдельной сверке с выпиской и в общий аудит не переносится.
JDBC бюджет 90s/≤30s на запрос — кооперативный; ограничения сети/пула отдельные.
Флаг `lavka.folio.document-audit.enabled` (defaultfalse), env
`LAVKA_FOLIO_DOCUMENT_AUDIT_ENABLED`. Миграций и изменений ФОЛИО нет.

Доступ защищается существующим admin/VPN/proxy-контуром проекта; новый
контроллер не предоставляет самостоятельную авторизацию. Поэтому он по умолчанию
отключён: включать только после проверки ограничения доступа к этому пути на
proxy/VPN, не только capability в WordPress. WordPress обязан
проверять capability и nonce. Браузер не ходит в ФОЛИО напрямую.

## Проверка реализации

2026-09-09: 25 тестов нового аудита и 49 регрессионных тестов отчёта прибыли,
периодов и JDBC-бюджета прошли; Maven package успешен (Java17, offline).
Проверены: отсутствие зарплатного источника, сохранение категории и прежней
классификации прибыли, обязательный/спорный период, дата договора, описание
транспортного расхода, обе стороны/касса/банк в DAO без фильтра прибыли,
страницы/верхний ID/последняя страница, неизвестные документы, NULL суммы,
ошибка источника отдельно от пустого реестра, параметры и изменение rulesVersion.
Unit-тесты не заменяют ручную сверку документов и проверку живой схемы/плана SQL.
После frontend-review отдельно проверены реальные HTTP400/code для выключенного
аудита и HTTP503/errorCode при отказе источника через MockMvc.
