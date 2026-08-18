# Golden-master: полный безопасный preview учётных цен `Paint_Rus`

Дата проверки: 2026-08-17.

Стенд: `Paint_Rus`, Microsoft SQL Server `8.00.760`, compatibility level 80,
`SQL_Ukrainian_CP1251_CI_AS`, CP1251. SQL-login лаборатории не состоит в
fixed server roles и не имеет доступа к другим рабочим пользовательским базам.

## Установленные объекты

DBA вручную установил в `Paint_Rus` отдельные объекты:

- `dbo.LAVKA_I_UCHET_1_TOVAR_SAFE`;
- `dbo.LAVKA_I_UCHET_TOVAR_SAFE`.

Штатные `dbo.I_UCHET_1_TOVAR` и `dbo.I_UCHET_TOVAR` не изменялись. Сервисному
database user выдан только `EXECUTE` на две новые procedures.

## Проверка одного SKU

Через jTDS и ограниченный login подтверждены два сценария:

1. обычный SKU возвращает `return_code=0`, текущий артикул и `new_art`;
2. синтетическое нулевое приходное движение при нулевом знаменателе возвращает
   `return_code=20`, `ZERO_ACCOUNTING_DENOMINATOR`, точные SKU/RECNO/дату,
   `AVERAGE_RECEIPT`, числитель, знаменатель и количества.

Оба вызова сохранили исходную внешнюю транзакцию. Лаборатория выполнила
`ROLLBACK`; независимый новый сеанс подтвердил отсутствие синтетического
движения и чистый `@@TRANCOUNT`.

## Java full-preview

Добавлен лабораторный маршрут:

```text
POST /api/v1/accounting-prices/safe-preview
GET  /api/v1/accounting-prices/safe-preview/status
```

Java сначала фиксирует отсортированный перечень `SCL_ARTC.COD_ARTIC`, затем
проверяет каждый SKU отдельно. Один SKU — одна внешняя транзакция и один
обязательный `ROLLBACK`. Код `20` и `otr_date` превращаются в diagnostics и не
останавливают следующий SKU. Неизвестный return code, SQL error или грязная
граница транзакции останавливают задачу.

Общий Java lock не позволяет одновременно выполнять произвольный lab SQL и
full-preview.

## Полный результат

Для первого полного опыта выбран склад 23:

- настройки: `N_2=1000`, `N_4 IS NULL`;
- карточек: 721;
- обработано: 721;
- чистых: 721;
- проблем деления на ноль: 0;
- отрицательных хронологических остатков: 0;
- итоговый статус: `COMPLETED`;
- постоянных записей: 0.

После окончания отдельный preflight и отдельный read-only SQL-run подтвердили:

- база осталась `Paint_Rus`;
- strict isolation сохранилась;
- общий lock освобождён;
- `transactionBefore=0` и `transactionAfter=0`.

## Область применимости

Golden-master подтверждает безопасный rollback-проход по отдельным SKU для
средней цены, `N_2=1000`, `N_4 IS NULL`. Он пока не подтверждает LIFO/FIFO,
партионный метод, складскую группу, commit учётных цен или установку объектов в
`Paint_Ua`.
