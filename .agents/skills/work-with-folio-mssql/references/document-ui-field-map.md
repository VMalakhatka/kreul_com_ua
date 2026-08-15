# Соответствие полей UI документов ФОЛИО

## Уровень доказательности

Карта составлена по текущему Java `FolioCustomerDocumentDao`, ранее
подтверждённым mapping создания счёта и снимкам штатного UI, предоставленным
2026-08-15. В таблицу включены только уже подтверждённые соответствия. Подпись,
похожая по смыслу, сама по себе не считается доказательством колонки.

## Счёт и расходная накладная

Оба документа читаются из `SCL_NAKL`; различаются кириллическим `TYPE_DOC`:
`С` — счёт, `Р` — расходная накладная.

| Поле штатного UI | Таблица.колонка | Customer documents API | Подтверждение |
|---|---|---|---|
| номер документа | `SCL_NAKL.N_PLAT_POR` | `documentNumber` | текущий DAO и UI |
| дополнительный номер | `SCL_NAKL.DOPN_SCHET` | `documentNumberSuffix` | текущий DAO |
| дата | `SCL_NAKL.DATE_P_POR` | `documentDate` | текущий DAO и UI |
| сумма | `SCL_NAKL.SUM_POR` | `totalAmount` | текущий DAO и UI |
| плательщик/кому | `SCL_NAKL.ORGANIZNKL` | detail `payerName` | текущий DAO |
| получатель/кто выдал | `SCL_NAKL.MY_ORGANIZ` | detail `receiverName` | текущий DAO |
| краткое имя клиента | `SCL_NAKL.BRIEFORG` | принадлежность `partner.shortName` | текущий DAO |
| тип операции | `SCL_NAKL.VID_DOC` | `operationKind` | текущий DAO и UI |
| основание | `SCL_NAKL.OSNOVANIE` | detail `basis` | текущий DAO |
| контракт | `SCL_NAKL.CONTR_POR` | detail `contractCode` | текущий DAO и UI |
| «Откуда узнал» | `SCL_NAKL.L_CP1_PLAT` | detail `sourceInfo` | write mapping и UI |
| «Инф» | `SCL_NAKL.L_CP2_PLAT` | list/detail `additionalInfo` | write mapping, DAO и UI |
| контрольный срок | `SCL_NAKL.CONTRLDATE` | `documentRequisites` не включает; используется в других отчётах | mapping и UI |
| примечание документа | `SCL_NAKL.PRIMECH_NC` | detail `note` | текущий DAO |

Для `ACCOUNT` и `EXPENSE` поле списка `additionalInfo` всегда читается только из
`L_CP2_PLAT`. Не объединять его с `L_CP1_PLAT`, `PRIMECH_NC` или `OSNOVANIE`.

## Платёж

Платёж читается из `SCL_PLAT` и идентифицируется `UNICUM_PLT`.

| Поле штатного UI | Таблица.колонка | Customer documents API | Подтверждение |
|---|---|---|---|
| номер/выписка N | `SCL_PLAT.N_PLAT_POR` | `documentNumber` | текущий DAO и UI |
| дата | `SCL_PLAT.DATE_P_POR` | `documentDate` | текущий DAO и UI |
| сумма | `SCL_PLAT.SUM_POR` | `totalAmount` | текущий DAO и UI |
| плательщик | `SCL_PLAT.L_NAME_POR` | detail `payerName` | текущий DAO |
| краткое имя клиента | `SCL_PLAT.ORG_PREDM` | принадлежность `partner.shortName` | текущий DAO |
| вид операции | `SCL_PLAT.VID_DOC` | `operationKind` | текущий DAO и UI |
| основание | `SCL_PLAT.OSNOVANIE` | detail `basis` | текущий DAO и UI |
| контракт | `SCL_PLAT.CONTR_POR` | detail `contractCode` | текущий DAO и UI |
| источник информации | `SCL_PLAT.IST_INF` | detail `sourceInfo` | текущий DAO и UI |
| «Примечание» | `SCL_PLAT.DOCUMN_POR` (`varchar(100) NULL`) | detail `note`; list `additionalInfo` | live schema, текущий DAO и UI 2026-08-15 |
| учитываемый документ | `SCL_PLAT.STND_UCHET` | `accounted` | текущий DAO и UI |

Для `PAYMENT` поле списка `additionalInfo` является прямым alias экранного
«Примечание» (`DOCUMN_POR`). Оно не объединяется с `IST_INF` или `OSNOVANIE`.

## Не подтверждено

На предоставленных экранах есть и другие поля, но точное соответствие части из
них ещё не зафиксировано воспроизводимым сравнением UI ↔ строка БД. Не добавляй
их в API по сходству названий: сначала выбери один документ, сними `SCL_NAKL`
или `SCL_PLAT`, измени ровно одно поле в штатной программе и сравни before/after.
