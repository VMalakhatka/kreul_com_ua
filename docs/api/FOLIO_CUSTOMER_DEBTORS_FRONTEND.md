# Снимок должников ФОЛИО: инструкция для фронта

## Главное правило

Построение нового снимка не делает предыдущий снимок недействительным.

Фронт должен отдельно показывать:

1. состояние новой фоновой генерации из `building`;
2. готовый снимок, из которого отображается отчёт, из `activeSnapshot`.

Если `activeSnapshot` присутствует, кнопка формирования отчёта и список
должников должны оставаться доступными даже при `status=BUILDING` и
`running=true`.

## Запрос статуса

```http
GET /admin/folio/customer-debtors/snapshot/status
```

Типичный ответ во время обновления:

```json
{
  "ok": true,
  "refreshAccepted": false,
  "running": true,
  "generationId": 2,
  "status": "BUILDING",
  "triggerSource": "SCHEDULED",
  "asOfDate": "2026-08-15",
  "startedAt": "2026-08-15T00:10:00.007",
  "completedAt": null,
  "totalClients": 0,
  "error": null,
  "building": {
    "generationId": 2,
    "triggerSource": "SCHEDULED",
    "asOfDate": "2026-08-15",
    "startedAt": "2026-08-15T00:10:00.007"
  },
  "activeSnapshot": {
    "generationId": 1,
    "asOfDate": "2026-08-14",
    "completedAt": "2026-08-14T21:41:25.646",
    "totalClients": 1698
  }
}
```

Плоские поля `generationId/status/asOfDate/...` оставлены для обратной
совместимости и описывают последнюю генерацию. Для нового интерфейса источниками
являются именно `building` и `activeSnapshot`.

## Логика интерфейса

### Есть `activeSnapshot`

- сразу загрузить и показать `GET /admin/folio/customer-debtors`;
- не отключать фильтры, пагинацию и кнопку формирования отчёта;
- над таблицей показать спокойное уведомление:

> Показаны данные на 14.08.2026. Обновление за 15.08.2026 начато 15.08.2026 в 00:10 и ещё выполняется.

- дату показанных данных брать из `activeSnapshot.asOfDate`, а не из верхнего
  `asOfDate` строящейся генерации.

### Нет `activeSnapshot`, но есть `building`

Это первая генерация, готовых данных ещё нет. Только в этом случае отчёт нужно
временно заблокировать и показать:

> Первый снимок задолженности ещё строится. Готового отчёта пока нет.

### Новая генерация завершилась

Когда `building` стал `null`, а `activeSnapshot.generationId` изменился:

- повторно загрузить список должников;
- обновить дату и количество клиентов;
- убрать уведомление о построении.

### Новая генерация завершилась ошибкой

Если верхний `status=FAILED`, но `activeSnapshot` присутствует:

- продолжить показывать активный снимок;
- вывести предупреждение, что обновление не завершилось;
- не скрывать рабочий отчёт.

## Частота опроса

Статус достаточно проверять раз в 30–60 секунд только пока есть `building`.
Опрос каждые 5 секунд не ускоряет расчёт и создаёт лишний шум в логах. После
завершения фоновой генерации polling нужно остановить.

## Служебная диагностика SQL Server

```http
GET /admin/folio/customer-debtors/snapshot/database-activity
```

Этот endpoint предназначен для ручной диагностики администратора:

- `RUNNING` — `I_DOLG_DOC` выполняется;
- `BLOCKED` — процедура заблокирована другой сессией;
- `IDLE_SESSION` — связанная сессия сейчас спит;
- `NOT_DETECTED` — в момент запроса выполнение не найдено;
- `UNAVAILABLE` — проверить не удалось.

Не вызывать его автоматически из страницы отчёта, не использовать для
блокировки интерфейса и не делать вывод о завершении всей генерации по одному
`NOT_DETECTED`: между последовательными вызовами процедуры возможен короткий
промежуток.

## Рекомендуемый псевдокод

```javascript
const status = await getSnapshotStatus();

if (status.activeSnapshot) {
  enableReport();
  await loadDebtors();
  showSnapshotDate(status.activeSnapshot.asOfDate);

  if (status.building) {
    showRefreshNotice(status.building, status.activeSnapshot);
    startStatusPolling(30000);
  }
} else if (status.building) {
  disableReportUntilFirstSnapshot();
  startStatusPolling(30000);
} else {
  showSnapshotUnavailable(status.error);
}
```
