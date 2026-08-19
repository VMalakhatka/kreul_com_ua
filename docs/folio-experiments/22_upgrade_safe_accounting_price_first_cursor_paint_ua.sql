/*
  Paint_Ua ONLY. Upgrade an already installed safe wrapper.

  Fixes the first-call OUT contract: when input @art is NULL and one SKU was
  processed, the legacy inner procedure can return @art=NULL together with a
  non-NULL @new_art. The wrapper now remembers the first SKU and returns it as
  the processed @art.

  This script alters only dbo.LAVKA_I_UCHET_TOVAR_SAFE. It does not execute a
  recalculation and does not change Folio documents, stock or accounting data.
*/
SET NOEXEC OFF
GO

IF DB_NAME()<>'Paint_Ua'
BEGIN
    RAISERROR('STOP: this script may run only in Paint_Ua',16,1)
    SET NOEXEC ON
END

IF CONVERT(varchar(30),SERVERPROPERTY('ProductVersion')) NOT LIKE '8.%'
BEGIN
    RAISERROR('STOP: expected SQL Server 8.00',16,1)
    SET NOEXEC ON
END

IF ISNULL((SELECT cmptlevel
             FROM master.dbo.sysdatabases
            WHERE name=DB_NAME()),-1)<>80
BEGIN
    RAISERROR('STOP: Paint_Ua compatibility level must be 80',16,1)
    SET NOEXEC ON
END

IF @@TRANCOUNT<>0
BEGIN
    RAISERROR('STOP: upgrade must start without an open transaction',16,1)
    SET NOEXEC ON
END

IF OBJECT_ID('dbo.LAVKA_I_UCHET_1_TOVAR_SAFE') IS NULL
   OR OBJECT_ID('dbo.LAVKA_I_UCHET_TOVAR_SAFE') IS NULL
   OR (SELECT COUNT(*) FROM dbo.syscolumns
        WHERE id=OBJECT_ID('dbo.LAVKA_I_UCHET_1_TOVAR_SAFE'))<>20
   OR (SELECT COUNT(*) FROM dbo.syscolumns
        WHERE id=OBJECT_ID('dbo.LAVKA_I_UCHET_TOVAR_SAFE'))<>20
BEGIN
    RAISERROR('STOP: expected installed LAVKA safe procedure contract',16,1)
    SET NOEXEC ON
END
GO

ALTER PROCEDURE LAVKA_I_UCHET_TOVAR_SAFE
    @n_group int,
    @id_sclad int,
    @usredn bit=0,
    @uchet_rsc int,
    @period_rsc int,
    @uch_nal bit=1,
    @art varchar(20) OUT,
    @n_cur int OUT,
    @n_tot int OUT,
    @new_art varchar(20) OUT,
    @otr_date char(10)=NULL OUT,
    @problem_code varchar(40)=NULL OUT,
    @problem_art varchar(20)=NULL OUT,
    @problem_recno int=NULL OUT,
    @problem_date datetime=NULL OUT,
    @problem_formula varchar(40)=NULL OUT,
    @problem_numerator float=NULL OUT,
    @problem_denominator float=NULL OUT,
    @problem_quantity_before float=NULL OUT,
    @problem_movement_quantity float=NULL OUT
AS
BEGIN
    DECLARE @ret int, @lavka_processed_art varchar(20)

    SELECT @lavka_processed_art=@art
    IF @lavka_processed_art IS NULL
        SELECT @lavka_processed_art=MIN(COD_ARTIC)
          FROM SCL_ARTC
         WHERE ID_SCLAD=@id_sclad

    EXECUTE @ret=dbo.LAVKA_I_UCHET_1_TOVAR_SAFE
        @n_group=@n_group,
        @id_sclad=@id_sclad,
        @usredn=@usredn,
        @uchet_rsc=@uchet_rsc,
        @period_rsc=@period_rsc,
        @uch_nal=@uch_nal,
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

    IF @art IS NULL AND ISNULL(@n_cur,0)>0
        SELECT @art=@lavka_processed_art

    RETURN @ret
END
GO

SELECT DB_NAME() AS database_name,
       OBJECT_ID('dbo.LAVKA_I_UCHET_TOVAR_SAFE') AS wrapper_procedure_id,
       (SELECT COUNT(*) FROM dbo.syscolumns
         WHERE id=OBJECT_ID('dbo.LAVKA_I_UCHET_TOVAR_SAFE')) AS wrapper_parameter_count,
       'FIRST_NULL_CURSOR_FIXED' AS upgrade_status
GO

-- Expected: Paint_Ua, non-NULL procedure ID, parameter count 20 and
-- FIRST_NULL_CURSOR_FIXED. ALTER PROCEDURE preserves the existing EXECUTE
-- permissions; no GRANT is required again.
