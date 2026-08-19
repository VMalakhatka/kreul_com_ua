SET NOCOUNT ON

IF DB_NAME() <> 'Paint_Rus'
BEGIN
    RAISERROR('STOP wrong database', 16, 1)
    RETURN
END

DECLARE @warehouse_id int, @recno int, @inserted_recno int,
        @sku varchar(20), @old_date datetime,
        @count_before int, @count_after int,
        @identity_before int, @identity_after int,
        @source_before int, @source_after int,
        @accounting_before int, @accounting_after int,
        @backdated_detected bit, @insert_detected bit, @delete_detected bit

SELECT @warehouse_id = 12

SELECT @recno = MIN(m.RECNO)
  FROM dbo.SCL_MOVE m
  JOIN dbo.SCL_ARTC a
    ON a.ID_SCLAD = m.ID_SCLAD
   AND a.COD_ARTIC = m.NAME_PREDM
 WHERE m.ID_SCLAD = @warehouse_id
   AND m.TYPDOCM_PR <> 'С'
   AND m.STND_UCHET = 1
   AND m.DATE_PREDM IS NOT NULL

SELECT @sku = NAME_PREDM, @old_date = DATE_PREDM
  FROM dbo.SCL_MOVE
 WHERE RECNO = @recno

IF @recno IS NULL OR @sku IS NULL OR @old_date IS NULL
BEGIN
    RAISERROR('STOP no linked candidate movement', 16, 1)
    RETURN
END

SELECT @count_before = COUNT(*),
       @identity_before = CHECKSUM_AGG(BINARY_CHECKSUM(
           RECNO, DATE_PREDM, TYPDOCM_PR, NUMDOCM_PR,
           ORG_PREDM, VOZVRAT_PR, KOLC_PREDM
       )),
       @source_before = CHECKSUM_AGG(BINARY_CHECKSUM(
           RECNO, SUM_PREDM, SUM_VALUT, NALOGMONEY, NALOGVALUT
       )),
       @accounting_before = CHECKSUM_AGG(BINARY_CHECKSUM(
           RECNO, PARTIA, SROK, SUM_UCHET, SUM_UCVAL
       ))
  FROM dbo.SCL_MOVE
 WHERE ID_SCLAD = @warehouse_id
   AND NAME_PREDM = @sku
   AND TYPDOCM_PR <> 'С'
   AND STND_UCHET = 1

UPDATE dbo.SCL_MOVE
   SET DATE_PREDM = DATEADD(day, -1, DATE_PREDM)
 WHERE RECNO = @recno

SELECT @identity_after = CHECKSUM_AGG(BINARY_CHECKSUM(
           RECNO, DATE_PREDM, TYPDOCM_PR, NUMDOCM_PR,
           ORG_PREDM, VOZVRAT_PR, KOLC_PREDM
       ))
  FROM dbo.SCL_MOVE
 WHERE ID_SCLAD = @warehouse_id
   AND NAME_PREDM = @sku
   AND TYPDOCM_PR <> 'С'
   AND STND_UCHET = 1

SELECT @backdated_detected = CASE
    WHEN ISNULL(@identity_before, 0) <> ISNULL(@identity_after, 0) THEN 1
    ELSE 0
END

UPDATE dbo.SCL_MOVE
   SET DATE_PREDM = @old_date
 WHERE RECNO = @recno

INSERT dbo.SCL_MOVE (
    UNICUM_NUM, NUM_PREDMT, NAME_PREDM, KOLTREB_PR, KOLC_PREDM,
    CENA_PREDM, SUM_PREDM, ORG_PREDM, DATE_PREDM, NUMDOCM_PR,
    NUMDCM_DOP, TYPDOCM_PR, STND_UCHET, NOT_NAL, CONTRACT_N,
    VALUT_CENA, COD_VALUT, SUM_VALUT, NACENKA, VALUTROUBL,
    OPLATA_SCH, NALOGMONEY, NALOGVALUT, VOZVRAT_PR, SUM_UCHET,
    SUM_UCVAL, KOLC_OPL, SUM_OPL, SUMVAL_OPL, SUM_ROZN,
    OTMETKA, VID_DOC, PARTIA, SROK, ID_SCLAD,
    BALL1, BALL2, BALL3, BALL4, BALL5,
    NAL_PR_RUB, NAL_PR_VAL, OS_OTM
)
SELECT
    UNICUM_NUM, NUM_PREDMT, NAME_PREDM, KOLTREB_PR, KOLC_PREDM,
    CENA_PREDM, SUM_PREDM, ORG_PREDM, DATE_PREDM, NUMDOCM_PR,
    NUMDCM_DOP, TYPDOCM_PR, STND_UCHET, NOT_NAL, CONTRACT_N,
    VALUT_CENA, COD_VALUT, SUM_VALUT, NACENKA, VALUTROUBL,
    OPLATA_SCH, NALOGMONEY, NALOGVALUT, VOZVRAT_PR, SUM_UCHET,
    SUM_UCVAL, KOLC_OPL, SUM_OPL, SUMVAL_OPL, SUM_ROZN,
    OTMETKA, VID_DOC, PARTIA, SROK, ID_SCLAD,
    BALL1, BALL2, BALL3, BALL4, BALL5,
    NAL_PR_RUB, NAL_PR_VAL, OS_OTM
  FROM dbo.SCL_MOVE
 WHERE RECNO = @recno

SELECT @inserted_recno = CONVERT(int, @@IDENTITY)

SELECT @count_after = COUNT(*),
       @identity_after = CHECKSUM_AGG(BINARY_CHECKSUM(
           RECNO, DATE_PREDM, TYPDOCM_PR, NUMDOCM_PR,
           ORG_PREDM, VOZVRAT_PR, KOLC_PREDM
       )),
       @source_after = CHECKSUM_AGG(BINARY_CHECKSUM(
           RECNO, SUM_PREDM, SUM_VALUT, NALOGMONEY, NALOGVALUT
       )),
       @accounting_after = CHECKSUM_AGG(BINARY_CHECKSUM(
           RECNO, PARTIA, SROK, SUM_UCHET, SUM_UCVAL
       ))
  FROM dbo.SCL_MOVE
 WHERE ID_SCLAD = @warehouse_id
   AND NAME_PREDM = @sku
   AND TYPDOCM_PR <> 'С'
   AND STND_UCHET = 1

SELECT @insert_detected = CASE
    WHEN @count_after = @count_before + 1
     AND (ISNULL(@identity_after, 0) <> ISNULL(@identity_before, 0)
       OR ISNULL(@source_after, 0) <> ISNULL(@source_before, 0)
       OR ISNULL(@accounting_after, 0) <> ISNULL(@accounting_before, 0))
    THEN 1 ELSE 0
END

DELETE dbo.SCL_MOVE WHERE RECNO = @inserted_recno
DELETE dbo.SCL_MOVE WHERE RECNO = @recno

SELECT @count_after = COUNT(*),
       @identity_after = CHECKSUM_AGG(BINARY_CHECKSUM(
           RECNO, DATE_PREDM, TYPDOCM_PR, NUMDOCM_PR,
           ORG_PREDM, VOZVRAT_PR, KOLC_PREDM
       )),
       @source_after = CHECKSUM_AGG(BINARY_CHECKSUM(
           RECNO, SUM_PREDM, SUM_VALUT, NALOGMONEY, NALOGVALUT
       )),
       @accounting_after = CHECKSUM_AGG(BINARY_CHECKSUM(
           RECNO, PARTIA, SROK, SUM_UCHET, SUM_UCVAL
       ))
  FROM dbo.SCL_MOVE
 WHERE ID_SCLAD = @warehouse_id
   AND NAME_PREDM = @sku
   AND TYPDOCM_PR <> 'С'
   AND STND_UCHET = 1

SELECT @delete_detected = CASE
    WHEN @count_after = @count_before - 1
     AND (ISNULL(@identity_after, 0) <> ISNULL(@identity_before, 0)
       OR ISNULL(@source_after, 0) <> ISNULL(@source_before, 0)
       OR ISNULL(@accounting_after, 0) <> ISNULL(@accounting_before, 0))
    THEN 1 ELSE 0
END

SELECT @backdated_detected AS backdated_update_detected,
       @insert_detected AS inserted_movement_detected,
       @delete_detected AS deleted_movement_detected,
       @@TRANCOUNT AS transaction_count_inside
