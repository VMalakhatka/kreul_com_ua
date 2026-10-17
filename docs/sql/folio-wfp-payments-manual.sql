-- MANUAL migration only. Not a startup/HTTP/Flyway migration.
-- First validate in Paint_Rus. Production requires a separate reviewed backup/deploy decision.
-- Change this guard to the exact approved database ONLY for that approved execution.
IF DB_NAME()<>'Paint_Rus' BEGIN RAISERROR('Wrong database for reviewed migration',16,1) RETURN END
IF OBJECT_ID('dbo.LAVKA_WFP_PAYMENTS','U') IS NOT NULL
 BEGIN RAISERROR('Table exists; inspect schema, do not replace ledger',16,1) RETURN END
CREATE TABLE dbo.LAVKA_WFP_PAYMENTS (
 OPERATION_KEY varchar(64) NOT NULL PRIMARY KEY,
 REQUEST_HASH varchar(64) NOT NULL,
 SOURCE_SCOPE varchar(64) NOT NULL,
 MERCHANT_HASH varchar(64) NOT NULL,
 REFERENCE varchar(64) NOT NULL,
 ORDER_ID numeric(18,0) NOT NULL,
 AMOUNT_CENTS numeric(18,0) NOT NULL,
 PAYER_KEY varchar(8) NOT NULL,
 CONFIRMED_AT varchar(20) NOT NULL,
 PAYMENT_ID float NOT NULL,
 PAYMENT_NUMBER float NOT NULL,
 CREATED_AT datetime NOT NULL,
 CONSTRAINT UQ_LAVKA_WFP_PAYMENT UNIQUE(SOURCE_SCOPE,MERCHANT_HASH,REFERENCE),
 CONSTRAINT UQ_LAVKA_WFP_NATIVE_PAYMENT UNIQUE(PAYMENT_ID)
)
-- Grant only SELECT/INSERT on this table to the approved application principal through the DBA.
-- No UPDATE/DELETE, automatic cleanup or cross-database access is required.
