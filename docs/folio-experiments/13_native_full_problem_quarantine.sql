SET NOCOUNT ON

IF DB_NAME() <> 'Paint_Rus'
BEGIN
    RAISERROR('STOP wrong database', 16, 1)
    RETURN
END

DECLARE @warehouse int, @problem_art varchar(20), @quarantine_type varchar(20),
        @original_type varchar(20), @art varchar(20),
        @n_cur int, @n_tot int, @new_art varchar(20), @otr_date char(10)

SELECT @warehouse = 12,
       @problem_art = 'ЯЯЯЯЛЛХ-ПЛБР/А3',
       @art = 'ЯЯЯЯЛЛХ-ПЛБР/А3',
       @n_cur = 0,
       @n_tot = 0,
       @new_art = NULL,
       @otr_date = NULL,
       @quarantine_type = '9'

IF EXISTS(SELECT 1 FROM dbo.TIP_TOVR WHERE SIGNIFIC = @quarantine_type)
   OR EXISTS(SELECT 1 FROM dbo.SCL_ARTC WHERE TIP_TOVR = @quarantine_type)
BEGIN
    RAISERROR('STOP quarantine marker is already used', 16, 1)
    RETURN
END

SELECT @original_type = TIP_TOVR
  FROM dbo.SCL_ARTC WITH (UPDLOCK, HOLDLOCK)
 WHERE ID_SCLAD = @warehouse
   AND COD_ARTIC = @problem_art

IF @original_type IS NULL
BEGIN
    RAISERROR('STOP quarantine metadata not found', 16, 1)
    RETURN
END

INSERT dbo.TIP_TOVR(SIGNIFIC, TIP_TOVAR, CHECK_SAVE, SHOW_OSTATOK)
VALUES(@quarantine_type, @quarantine_type, 0, 0)

UPDATE dbo.SCL_ARTC
   SET TIP_TOVR = @quarantine_type
 WHERE ID_SCLAD = @warehouse
   AND COD_ARTIC = @problem_art

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

UPDATE dbo.SCL_ARTC
   SET TIP_TOVR = @original_type
 WHERE ID_SCLAD = @warehouse
   AND COD_ARTIC = @problem_art

DELETE dbo.TIP_TOVR
 WHERE SIGNIFIC = @quarantine_type
   AND TIP_TOVAR = @quarantine_type
   AND CHECK_SAVE = 0
   AND SHOW_OSTATOK = 0

SELECT @problem_art AS quarantined_sku,
       @art AS output_art,
       @new_art AS next_art,
       @otr_date AS problem_date,
       @n_cur AS current_units,
       @n_tot AS total_units,
       @original_type AS original_type,
       (SELECT TIP_TOVR
          FROM dbo.SCL_ARTC
         WHERE ID_SCLAD = @warehouse
           AND COD_ARTIC = @problem_art) AS restored_type,
       @@TRANCOUNT AS transaction_count
