SET NOCOUNT ON

IF DB_NAME() <> 'Paint_Rus'
BEGIN
    RAISERROR('STOP wrong database', 16, 1)
    RETURN
END

DECLARE @warehouse int, @p char(1), @r char(1), @c char(1), @eps float,
        @sku varchar(20), @last_sku varchar(20), @typ char(1),
        @qty float, @initial float, @running float, @before float,
        @recno int, @unicum float, @numdoc float, @movement_date datetime,
        @marker varchar(20), @art varchar(20), @new_art varchar(20),
        @otr_date char(10), @n_cur int, @n_tot int, @calls int,
        @completed bit

SELECT @warehouse = 12, @p = CHAR(207), @r = CHAR(208),
       @c = CHAR(209), @eps = 0.00000000001, @marker = '9'

IF EXISTS(SELECT 1 FROM dbo.TIP_TOVR WHERE SIGNIFIC = @marker)
   OR EXISTS(SELECT 1 FROM dbo.SCL_ARTC WHERE TIP_TOVR = @marker)
BEGIN
    RAISERROR('STOP quarantine marker is already used', 16, 1)
    RETURN
END

CREATE TABLE #problems(
    sku varchar(20) NOT NULL PRIMARY KEY,
    problem_code varchar(50) NOT NULL,
    recno int NOT NULL,
    unicum_num float NULL,
    document_number float NULL,
    movement_date datetime NOT NULL,
    document_type char(1) NOT NULL,
    operation_quantity float NOT NULL,
    quantity_before float NOT NULL,
    quantity_after float NOT NULL
)

DECLARE movements CURSOR LOCAL STATIC READ_ONLY FOR
 SELECT m.NAME_PREDM, m.TYPDOCM_PR, m.KOLC_PREDM,
        a.NACH_KOLCH, m.RECNO, m.UNICUM_NUM, m.NUMDOCM_PR,
        m.DATE_PREDM
   FROM dbo.SCL_MOVE m
   JOIN dbo.SCL_ARTC a
     ON a.COD_ARTIC = m.NAME_PREDM
    AND a.ID_SCLAD = m.ID_SCLAD
  WHERE m.ID_SCLAD = @warehouse
    AND m.STND_UCHET = 1
    AND m.TYPDOCM_PR <> @c
  ORDER BY m.NAME_PREDM, m.DATE_PREDM,
           m.TYPDOCM_PR, m.NUMDOCM_PR, m.RECNO

OPEN movements
FETCH NEXT FROM movements INTO @sku, @typ, @qty, @initial,
                               @recno, @unicum, @numdoc, @movement_date
WHILE @@FETCH_STATUS = 0
BEGIN
    IF @last_sku IS NULL OR @last_sku <> @sku
    BEGIN
        SELECT @last_sku = @sku, @running = ISNULL(@initial, 0)
    END

    SELECT @before = @running,
           @running = @running + CASE WHEN @typ = @p THEN @qty ELSE -@qty END

    IF NOT EXISTS(SELECT 1 FROM #problems WHERE sku = @sku)
    BEGIN
        IF @typ = @p AND ABS(@running) <= @eps
            INSERT #problems VALUES(
                @sku, 'ZERO_ACCOUNTING_QUANTITY_DENOMINATOR', @recno,
                @unicum, @numdoc, @movement_date, @typ, @qty,
                @before, @running)
        ELSE IF @typ = @r AND (@before <= 0 OR @running < -@eps)
            INSERT #problems VALUES(
                @sku, 'NEGATIVE_CHRONOLOGICAL_STOCK', @recno,
                @unicum, @numdoc, @movement_date, @typ, @qty,
                @before, @running)
    END

    FETCH NEXT FROM movements INTO @sku, @typ, @qty, @initial,
                                   @recno, @unicum, @numdoc, @movement_date
END
CLOSE movements
DEALLOCATE movements

CREATE TABLE #original_types(
    sku varchar(20) NOT NULL PRIMARY KEY,
    original_type varchar(20) NULL
)

INSERT #original_types(sku, original_type)
 SELECT a.COD_ARTIC, a.TIP_TOVR
   FROM dbo.SCL_ARTC a WITH (UPDLOCK, HOLDLOCK)
   JOIN #problems p ON p.sku = a.COD_ARTIC
  WHERE a.ID_SCLAD = @warehouse

INSERT dbo.TIP_TOVR(SIGNIFIC, TIP_TOVAR, CHECK_SAVE, SHOW_OSTATOK)
VALUES(@marker, @marker, 0, 0)

UPDATE dbo.SCL_ARTC
   SET TIP_TOVR = @marker
  FROM dbo.SCL_ARTC a
  JOIN #problems p ON p.sku = a.COD_ARTIC
 WHERE a.ID_SCLAD = @warehouse

CREATE TABLE #unexpected(
    input_art varchar(20) NULL,
    problem_art varchar(20) NULL,
    next_art varchar(20) NULL,
    problem_date char(10) NULL
)

SELECT @art = NULL, @new_art = NULL, @otr_date = NULL,
       @n_cur = 0, @n_tot = 0, @calls = 0, @completed = 0

WHILE @calls < 500
BEGIN
    SELECT @new_art = NULL, @otr_date = NULL, @n_cur = 0

    EXEC dbo.I_UCHET_TOVAR
         @n_group = NULL,
         @id_sclad = @warehouse,
         @usredn = 0,
         @uchet_rsc = 0,
         @period_rsc = 0,
         @uch_nal = 0,
         @art = @art OUTPUT,
         @n_cur = @n_cur OUTPUT,
         @n_tot = @n_tot OUTPUT,
         @new_art = @new_art OUTPUT,
         @otr_date = @otr_date OUTPUT

    SELECT @calls = @calls + 1

    IF @otr_date IS NOT NULL
    BEGIN
        INSERT #unexpected VALUES(@art, @art, @new_art, @otr_date)
        BREAK
    END

    IF @new_art IS NULL
    BEGIN
        SELECT @completed = 1
        BREAK
    END

    SELECT @art = @new_art
END

UPDATE dbo.SCL_ARTC
   SET TIP_TOVR = o.original_type
  FROM dbo.SCL_ARTC a
  JOIN #original_types o ON o.sku = a.COD_ARTIC
 WHERE a.ID_SCLAD = @warehouse

DELETE dbo.TIP_TOVR
 WHERE SIGNIFIC = @marker
   AND TIP_TOVAR = @marker
   AND CHECK_SAVE = 0
   AND SHOW_OSTATOK = 0

SELECT @warehouse AS warehouse_id,
       (SELECT COUNT(*) FROM #problems) AS quarantined_skus,
       (SELECT COUNT(*) FROM #problems
         WHERE problem_code = 'NEGATIVE_CHRONOLOGICAL_STOCK') AS negative_skus,
       (SELECT COUNT(*) FROM #problems
         WHERE problem_code = 'ZERO_ACCOUNTING_QUANTITY_DENOMINATOR') AS zero_denominator_skus,
       @calls AS procedure_calls,
       @completed AS completed,
       (SELECT COUNT(*) FROM #unexpected) AS unexpected_problems,
       (SELECT COUNT(*)
          FROM dbo.SCL_ARTC a
          JOIN #original_types o ON o.sku = a.COD_ARTIC
         WHERE a.ID_SCLAD = @warehouse
           AND ISNULL(a.TIP_TOVR, '') <> ISNULL(o.original_type, '')) AS unrestored_types,
       @@TRANCOUNT AS transaction_count

SELECT TOP 20 * FROM #problems ORDER BY sku
SELECT * FROM #unexpected
