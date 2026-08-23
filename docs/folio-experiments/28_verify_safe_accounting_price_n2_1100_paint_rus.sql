/*
  Paint_Rus ONLY. Rollback-only golden-master for the lab API.

  Preconditions:
    - script 26 was installed manually in SQL Query Analyzer;
    - execute this file through folio-rus-lab in its default ROLLBACK mode;
    - warehouse 22 remains N_2=1100 and N_4 IS NULL;
    - SKU A-AZ РАЗНОЕ exists on warehouse 22.

  Do not run this file directly in Query Analyzer: it intentionally requires
  the outer transaction supplied and verified by folio-rus-lab.
*/
SET NOCOUNT ON

IF DB_NAME() <> 'Paint_Rus'
BEGIN
    RAISERROR('STOP wrong database', 16, 1)
    RETURN
END

IF @@TRANCOUNT < 1
BEGIN
    RAISERROR('STOP external rollback transaction is required', 16, 1)
    RETURN
END

IF NOT EXISTS (
    SELECT 1
      FROM dbo.SCLAD_R
     WHERE ID_SCLAD=22
       AND N_2=1100
       AND N_4 IS NULL
)
BEGIN
    RAISERROR('STOP warehouse 22 is not the expected N_2=1100 standalone scope', 16, 1)
    RETURN
END

IF NOT EXISTS (
    SELECT 1
      FROM dbo.SCL_ARTC
     WHERE ID_SCLAD=22
       AND COD_ARTIC='A-AZ РАЗНОЕ'
)
BEGIN
    RAISERROR('STOP test SKU is missing', 16, 1)
    RETURN
END

DECLARE @rc int, @art varchar(20), @n_cur int, @n_tot int,
        @new_art varchar(20), @otr_date char(10),
        @problem_code varchar(40), @problem_art varchar(20),
        @problem_recno int, @problem_date datetime,
        @problem_formula varchar(40), @problem_numerator float,
        @problem_denominator float, @problem_quantity_before float,
        @problem_movement_quantity float, @tran_before int,
        @movement_count_before int, @movement_count_after int,
        @movement_quantity_before float, @movement_quantity_after float,
        @tmp_count_before int, @tmp_count_after int,
        @initial_before float, @initial_after float,
        @physical_before float, @physical_after float,
        @reserved_before float, @reserved_after float

SELECT @art='A-AZ РАЗНОЕ', @n_cur=0, @n_tot=0, @new_art=NULL,
       @otr_date=NULL, @problem_code=NULL, @problem_art=NULL,
       @problem_recno=NULL, @problem_date=NULL, @problem_formula=NULL,
       @problem_numerator=NULL, @problem_denominator=NULL,
       @problem_quantity_before=NULL, @problem_movement_quantity=NULL,
       @tran_before=@@TRANCOUNT

SELECT @initial_before=NACH_KOLCH,
       @physical_before=KON_KOLCH,
       @reserved_before=REZ_KOLCH
  FROM dbo.SCL_ARTC
 WHERE ID_SCLAD=22
   AND COD_ARTIC='A-AZ РАЗНОЕ'

SELECT @movement_count_before=COUNT(*),
       @movement_quantity_before=ISNULL(SUM(KOLC_PREDM),0)
  FROM dbo.SCL_MOVE
 WHERE ID_SCLAD=22
   AND NAME_PREDM='A-AZ РАЗНОЕ'

SELECT @tmp_count_before=COUNT(*) FROM dbo.TMP_MOVE

EXECUTE @rc=dbo.LAVKA_I_UCHET_TOVAR_SAFE
    @n_group=NULL,
    @id_sclad=22,
    @usredn=0,
    @uchet_rsc=0,
    @period_rsc=0,
    @uch_nal=1,
    @art=@art OUT,
    @n_cur=@n_cur OUT,
    @n_tot=@n_tot OUT,
    @new_art=@new_art OUT,
    @otr_date=@otr_date OUT,
    @problem_code=@problem_code OUT,
    @problem_art=@problem_art OUT,
    @problem_recno=@problem_recno OUT,
    @problem_date=@problem_date OUT,
    @problem_formula=@problem_formula OUT,
    @problem_numerator=@problem_numerator OUT,
    @problem_denominator=@problem_denominator OUT,
    @problem_quantity_before=@problem_quantity_before OUT,
    @problem_movement_quantity=@problem_movement_quantity OUT

SELECT @initial_after=NACH_KOLCH,
       @physical_after=KON_KOLCH,
       @reserved_after=REZ_KOLCH
  FROM dbo.SCL_ARTC
 WHERE ID_SCLAD=22
   AND COD_ARTIC='A-AZ РАЗНОЕ'

SELECT @movement_count_after=COUNT(*),
       @movement_quantity_after=ISNULL(SUM(KOLC_PREDM),0)
  FROM dbo.SCL_MOVE
 WHERE ID_SCLAD=22
   AND NAME_PREDM='A-AZ РАЗНОЕ'

SELECT @tmp_count_after=COUNT(*) FROM dbo.TMP_MOVE

SELECT @rc AS return_code,
       CASE WHEN @rc IN (0,20) THEN 'EXPECTED' ELSE 'UNEXPECTED' END AS return_contract,
       @art AS processed_art,
       @new_art AS next_art,
       @n_cur AS current_units,
       @n_tot AS total_units,
       @otr_date AS problem_date,
       @problem_code AS problem_code,
       @problem_art AS problem_art,
       @problem_recno AS problem_recno,
       @problem_date AS problem_operation_date,
       @problem_formula AS problem_formula,
       @problem_numerator AS problem_numerator,
       @problem_denominator AS problem_denominator,
       @problem_quantity_before AS problem_quantity_before,
       @problem_movement_quantity AS problem_movement_quantity,
       @tran_before AS transaction_count_before,
       @@TRANCOUNT AS transaction_count_after,
       @initial_before AS initial_quantity_before,
       @initial_after AS initial_quantity_after,
       @physical_before AS physical_quantity_before,
       @physical_after AS physical_quantity_after,
       @reserved_before AS reserved_quantity_before,
       @reserved_after AS reserved_quantity_after,
       @movement_count_before AS movement_count_before,
       @movement_count_after AS movement_count_after,
       @movement_quantity_before AS movement_quantity_before,
       @movement_quantity_after AS movement_quantity_after,
       @tmp_count_before AS tmp_count_before,
       @tmp_count_after AS tmp_count_after

IF @@TRANCOUNT<>@tran_before
   OR ISNULL(@initial_before,-999999999999.0)<>ISNULL(@initial_after,-999999999999.0)
   OR ISNULL(@physical_before,-999999999999.0)<>ISNULL(@physical_after,-999999999999.0)
   OR ISNULL(@reserved_before,-999999999999.0)<>ISNULL(@reserved_after,-999999999999.0)
   OR @movement_count_before<>@movement_count_after
   OR @movement_quantity_before<>@movement_quantity_after
   OR @tmp_count_before<>@tmp_count_after
BEGIN
    RAISERROR('STOP protected invariant changed', 16, 1)
    RETURN
END
