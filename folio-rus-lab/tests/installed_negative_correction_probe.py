"""Installed-wrapper rollback probe for this approved Paint_Rus laboratory.

Reuses two known movement rows and one price row inside the outer transaction;
does not consume identity values. Requires confirmed exclusive/restorable lab.
Usage: python3 .../installed_negative_correction_probe.py CASE | bin/folio-rus-lab execute
Never run outside the managed lab API. Never use COMMIT mode.
"""
import sys

BASELINE_SQL = r"""
SELECT DB_NAME() AS database_name
SELECT COUNT(*) AS rows_count,CHECKSUM_AGG(BINARY_CHECKSUM(*)) AS rows_hash
 FROM dbo.SCL_MOVE WHERE RECNO IN (4910380,5023205)
SELECT COUNT(*) AS rows_count,CHECKSUM_AGG(BINARY_CHECKSUM(*)) AS rows_hash
 FROM dbo.SCL_ARTC WHERE COD_ARTIC='BR-8990D36BZ' AND ID_SCLAD=12
SELECT COUNT(*) AS rows_count,CHECKSUM_AGG(BINARY_CHECKSUM(*)) AS rows_hash
 FROM dbo.SCL_ARTC WHERE COD_ARTIC LIKE 'TST-33-%'
SELECT COUNT(*) AS rows_count,CHECKSUM_AGG(BINARY_CHECKSUM(*)) AS rows_hash
 FROM dbo.SCL_NAKL WHERE UNICUM_NUM IN (474818,486575)
SELECT COUNT(*) AS rows_count,CHECKSUM_AGG(BINARY_CHECKSUM(*)) AS rows_hash FROM dbo.TMP_MOVE
SELECT COUNT(*) AS rows_count,CHECKSUM_AGG(BINARY_CHECKSUM(*)) AS rows_hash
 FROM dbo.SCL_PRIC WHERE ID_SCLAD IN (12,22)
SELECT ID_SCLAD,N_2,N_4 FROM dbo.SCLAD_R WHERE ID_SCLAD IN (12,22) ORDER BY ID_SCLAD
SELECT COUNT(*) AS rows_count,CHECKSUM_AGG(BINARY_CHECKSUM(*)) AS rows_hash
 FROM dbo.SCL_ARTC WHERE ID_SCLAD IN (12,22)
SELECT TOP 1 ID FROM dbo.SCL_PRIC WHERE ID_SCLAD=12 ORDER BY ID
"""

CASES = {
    1: ('positive_receipt', 12, 0, 2, 18.72, 1, 18.77, 0, 0, 0, 0, 0, 3, 56.21, 56.21 / 3),
    2: ('negative_legacy_zero', 12, 0, 2, 18.72, -1, -18.77, 0, 0, 0, 0, 0, 1, 37.44, 37.44),
    3: ('negative_signed_amount', 12, 0, 2, 18.72, -1, -18.77, -18.77, 0, 0, 0, 0, 1, 18.67, 18.67),
    4: ('true_zero_skip', 12, 0, 1, 18.72, -1, -18.77, -18.77, 0, 0, 0, 20, 0, 0, 0),
    5: ('clean_after_zero_skip', 12, 0, 0, 0, 2, 40, 0, 0, 0, 0, 0, 2, 40, 20),
    6: ('negative_return', 12, 0, 2, 18.72, -1, -18.77, 0, 1, 0, 0, 0, 1, 18.72, 18.72),
    7: ('tax_excluded', 12, 0, 2, 18.72, 1, 24, 0, 0, 4, 0, 0, 3, 57.44, 57.44 / 3),
    8: ('tax_included', 22, 1, 2, 18.72, 1, 24, 0, 0, 4, 0, 0, 3, 61.44, 20.48),
    9: ('negative_then_full_sale', 12, 0, 2, 18.72, -1, -18.77, 0, 0, 0, 1, 0, 0, 0, 37.44),
    10: ('zero_return_skip', 12, 0, 1, 18.72, -1, -18.77, 0, 1, 0, 0, 20, 0, 0, 0),
    11: ('currency_tax_excluded', 12, 0, 2, 18.72, 1, 24, 0, 0, 4, 0, 0, 3, 57.44, 57.44 / 3),
    12: ('currency_tax_included', 22, 1, 2, 18.72, 1, 24, 0, 0, 4, 0, 0, 3, 61.44, 20.48),
    13: ('positive_return', 12, 0, 2, 18.72, 1, 18.77, 0, 1, 0, 0, 0, 3, 56.16, 18.72),
}

SQL = r"""
SET NOCOUNT ON
IF DB_NAME()<>'Paint_Rus' OR @@TRANCOUNT<1
BEGIN RAISERROR('Paint_Rus managed ROLLBACK required',16,1) RETURN END
DECLARE @warehouse int,@tax_included bit,@q0 float,@p0 float,@dq float,@amount float,
 @old_amount float,@is_return bit,@tax float,@tail_sale bit,@expected_rc int,
 @expected_q float,@expected_sum float,@expected_price float,@case_name varchar(50),
 @expected_currency_sum float,@expected_currency_price float
__PARAMETERS__
SELECT @expected_currency_sum=0,@expected_currency_price=0
IF @case_name LIKE 'currency_%'
BEGIN
 SET @expected_currency_sum=CASE WHEN @tax_included=1 THEN 16 ELSE 14 END
 SET @expected_currency_price=@expected_currency_sum/3
END
IF NOT EXISTS(SELECT 1 FROM dbo.SCLAD_R WHERE ID_SCLAD=@warehouse
 AND N_2=1000+100*CONVERT(int,@tax_included) AND N_4 IS NULL)
BEGIN RAISERROR('Unexpected accounting mode',16,1) RETURN END
IF EXISTS(SELECT 1 FROM dbo.SCL_ARTC WHERE COD_ARTIC LIKE 'TST-33-%')
BEGIN RAISERROR('Fixture namespace occupied',16,1) RETURN END
IF (SELECT COUNT(*) FROM dbo.SCL_MOVE WITH (UPDLOCK,HOLDLOCK)
 WHERE RECNO IN (4910380,5023205) AND ID_SCLAD=12 AND NAME_PREDM='BR-8990D36BZ'
 AND KOLC_PREDM=2 AND STND_UCHET=1)<>2
BEGIN RAISERROR('Movement fixture source changed',16,1) RETURN END
IF NOT EXISTS(SELECT 1 FROM dbo.SCL_PRIC WITH (UPDLOCK,HOLDLOCK) WHERE ID=18300708 AND ID_SCLAD=12)
BEGIN RAISERROR('Price fixture source changed',16,1) RETURN END
IF EXISTS(SELECT 1 FROM dbo.sysobjects WHERE type='TR' AND parent_obj IN
 (OBJECT_ID('dbo.SCL_ARTC'),OBJECT_ID('dbo.SCL_MOVE'),OBJECT_ID('dbo.SCL_PRIC')))
BEGIN RAISERROR('Unexpected write trigger',16,1) RETURN END

DECLARE @cards int,@card_hash int,@movements int,@movement_hash int,
 @prices int,@price_hash int,@docs int,@doc_hash int,@tmp int,@tmp_hash int,
 @input_hash int,@tran int,@expected_next varchar(20)
SELECT @cards=COUNT(*),@card_hash=CHECKSUM_AGG(BINARY_CHECKSUM(*))
 FROM dbo.SCL_ARTC WHERE ID_SCLAD IN (12,22)
SELECT @movements=COUNT(*),@movement_hash=CHECKSUM_AGG(BINARY_CHECKSUM(*))
 FROM dbo.SCL_MOVE WHERE ID_SCLAD IN (12,22) AND RECNO NOT IN (4910380,5023205)
SELECT @prices=COUNT(*),@price_hash=CHECKSUM_AGG(BINARY_CHECKSUM(*))
 FROM dbo.SCL_PRIC WHERE ID_SCLAD IN (12,22) AND ID<>18300708
SELECT @docs=COUNT(*),@doc_hash=CHECKSUM_AGG(BINARY_CHECKSUM(*))
 FROM dbo.SCL_NAKL WHERE UNICUM_NUM IN (474818,486575)
SELECT @tmp=COUNT(*),@tmp_hash=CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM dbo.TMP_MOVE
SET @tran=@@TRANCOUNT

-- SCL_ARTC has no identity; no movement/price/header INSERT is used.
INSERT dbo.SCL_ARTC (COD_ARTIC,ID_SCLAD,NAME_ARTIC,NACH_KOLCH,UCHET_0_C,UCHET_0_VL,
 KON_KOLCH,REZ_KOLCH,KOL_SUM,UCHET_SUM,UCHET_SMVL,UCHET_CENA,UCHET_VALT,
 CENA_ARTIC,CENA_VALT,CENA_BZNAL,CENA_V_BZN,PRIZN_VALT,FIX_NACEN,NDS_ARTIC,COEF_BZNAL)
 VALUES('TST-33-WRAPPER',@warehouse,'Rollback-only arithmetic fixture',@q0,@p0,0,
 @q0+@dq-CONVERT(int,@tail_sale),0,999,999,0,999,0,999,0,999,0,0,1,0,1)
UPDATE dbo.SCL_MOVE SET NAME_PREDM='TST-33-WRAPPER',ID_SCLAD=@warehouse,
 DATE_PREDM='20170406',TYPDOCM_PR='П',VOZVRAT_PR=@is_return,KOLC_PREDM=@dq,
 SUM_PREDM=@amount,SUM_VALUT=0,NALOGMONEY=@tax,NALOGVALUT=0,
 SUM_UCHET=@old_amount,SUM_UCVAL=0,ORG_PREDM='TST33',PARTIA=NULL,SROK=NULL
 WHERE RECNO=4910380
IF @case_name LIKE 'currency_%'
BEGIN
 UPDATE dbo.SCL_ARTC SET UCHET_0_VL=2 WHERE COD_ARTIC='TST-33-WRAPPER' AND ID_SCLAD=@warehouse
 UPDATE dbo.SCL_MOVE SET SUM_VALUT=12,NALOGVALUT=2 WHERE RECNO=4910380
END
IF @tail_sale=1
UPDATE dbo.SCL_MOVE SET NAME_PREDM='TST-33-WRAPPER',ID_SCLAD=@warehouse,
 DATE_PREDM='20170407',TYPDOCM_PR='Р',VOZVRAT_PR=0,KOLC_PREDM=1,
 SUM_PREDM=30,SUM_VALUT=0,NALOGMONEY=0,NALOGVALUT=0,SUM_UCHET=0,SUM_UCVAL=0,
 ORG_PREDM='TST33',PARTIA=NULL,SROK=NULL WHERE RECNO=5023205
UPDATE dbo.SCL_PRIC SET COD_ARTIC='TST-33-WRAPPER',ID_SCLAD=@warehouse,
 COEF_PRICE=1.5,RUB_PRICE=999,VALT_PRICE=0 WHERE ID=18300708
SELECT @input_hash=CHECKSUM_AGG(BINARY_CHECKSUM(RECNO,UNICUM_NUM,NUM_PREDMT,NAME_PREDM,
 ID_SCLAD,DATE_PREDM,TYPDOCM_PR,STND_UCHET,VOZVRAT_PR,KOLC_PREDM,SUM_PREDM,
 SUM_VALUT,NALOGMONEY,NALOGVALUT,ORG_PREDM,PARTIA,SROK))
 FROM dbo.SCL_MOVE WHERE RECNO IN (4910380,5023205)
SELECT @expected_next=MIN(COD_ARTIC) FROM dbo.SCL_ARTC
 WHERE ID_SCLAD=@warehouse AND COD_ARTIC>'TST-33-WRAPPER'

DECLARE @rc int,@art varchar(20),@cur int,@total int,@next varchar(20),@date char(10),
 @code varchar(40),@problem_art varchar(20),@recno int,@problem_date datetime,
 @formula varchar(40),@numerator float,@denominator float,@quantity_before float,@movement_qty float
SELECT @art='TST-33-WRAPPER',@cur=0,@total=0
EXECUTE @rc=dbo.LAVKA_I_UCHET_TOVAR_SAFE
 @n_group=NULL,@id_sclad=@warehouse,@usredn=0,@uchet_rsc=0,@period_rsc=0,@uch_nal=@tax_included,
 @art=@art OUT,@n_cur=@cur OUT,@n_tot=@total OUT,@new_art=@next OUT,@otr_date=@date OUT,
 @problem_code=@code OUT,@problem_art=@problem_art OUT,@problem_recno=@recno OUT,
 @problem_date=@problem_date OUT,@problem_formula=@formula OUT,
 @problem_numerator=@numerator OUT,@problem_denominator=@denominator OUT,
 @problem_quantity_before=@quantity_before OUT,@problem_movement_quantity=@movement_qty OUT

IF @rc IS NULL OR @rc<>@expected_rc OR @art IS NULL OR @art<>'TST-33-WRAPPER'
 OR ISNULL(@next,'')<>ISNULL(@expected_next,'') OR @@TRANCOUNT<>@tran
BEGIN RAISERROR('Unexpected wrapper return, cursor or transaction',16,1) RETURN END
IF @rc=20 AND (@code IS NULL OR @code<>'ZERO_ACCOUNTING_DENOMINATOR'
 OR @problem_art IS NULL OR @problem_art<>'TST-33-WRAPPER' OR @recno IS NULL OR @recno<>4910380
 OR @problem_date IS NULL OR @problem_date<>'20170406' OR @denominator IS NULL OR ABS(@denominator)>1e-11
 OR @formula IS NULL OR @formula<>CASE WHEN @is_return=1 THEN 'AVERAGE_RETURN' ELSE 'AVERAGE_RECEIPT' END
 OR @quantity_before IS NULL OR ABS(@quantity_before-@q0)>1e-8
 OR @movement_qty IS NULL OR ABS(@movement_qty-@dq)>1e-8 OR @numerator IS NULL)
BEGIN RAISERROR('Incomplete wrapper skip diagnostics',16,1) RETURN END
IF @rc=0 AND EXISTS(SELECT 1 FROM dbo.SCL_ARTC WHERE COD_ARTIC='TST-33-WRAPPER' AND ID_SCLAD=@warehouse
 AND (KOL_SUM IS NULL OR UCHET_SUM IS NULL OR UCHET_CENA IS NULL
 OR ABS(KOL_SUM-@expected_q)>1e-8 OR ABS(UCHET_SUM-@expected_sum)>1e-8
 OR ABS(UCHET_CENA-@expected_price)>1e-8 OR UCHET_SMVL IS NULL OR UCHET_VALT IS NULL
 OR ABS(UCHET_SMVL-@expected_currency_sum)>1e-8 OR ABS(UCHET_VALT-@expected_currency_price)>1e-8))
BEGIN RAISERROR('Wrong wrapper accounting result',16,1) RETURN END
IF @rc=0 AND EXISTS(SELECT 1 FROM dbo.SCL_PRIC WHERE ID=18300708
 AND (RUB_PRICE IS NULL OR ABS(RUB_PRICE-1.5*@expected_price)>1e-8))
BEGIN RAISERROR('Wrong derived price',16,1) RETURN END
IF (SELECT COUNT(*) FROM dbo.SCL_ARTC WHERE COD_ARTIC='TST-33-WRAPPER' AND ID_SCLAD=@warehouse)<>1
 OR EXISTS(SELECT 1 FROM dbo.SCL_ARTC WHERE COD_ARTIC='TST-33-WRAPPER' AND
 (KON_KOLCH<>@q0+@dq-CONVERT(int,@tail_sale) OR REZ_KOLCH<>0 OR NACH_KOLCH<>@q0))
 OR @cards<>(SELECT COUNT(*) FROM dbo.SCL_ARTC WHERE ID_SCLAD IN (12,22) AND COD_ARTIC<>'TST-33-WRAPPER')
 OR @card_hash<>(SELECT CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM dbo.SCL_ARTC WHERE ID_SCLAD IN (12,22) AND COD_ARTIC<>'TST-33-WRAPPER')
 OR @movements<>(SELECT COUNT(*) FROM dbo.SCL_MOVE WHERE ID_SCLAD IN (12,22) AND RECNO NOT IN (4910380,5023205))
 OR @movement_hash<>(SELECT CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM dbo.SCL_MOVE WHERE ID_SCLAD IN (12,22) AND RECNO NOT IN (4910380,5023205))
 OR @prices<>(SELECT COUNT(*) FROM dbo.SCL_PRIC WHERE ID_SCLAD IN (12,22) AND ID<>18300708)
 OR @price_hash<>(SELECT CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM dbo.SCL_PRIC WHERE ID_SCLAD IN (12,22) AND ID<>18300708)
 OR @docs<>(SELECT COUNT(*) FROM dbo.SCL_NAKL WHERE UNICUM_NUM IN (474818,486575))
 OR @doc_hash<>(SELECT CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM dbo.SCL_NAKL WHERE UNICUM_NUM IN (474818,486575))
 OR @tmp<>(SELECT COUNT(*) FROM dbo.TMP_MOVE)
 OR @tmp_hash<>(SELECT CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM dbo.TMP_MOVE)
 OR @input_hash<>(SELECT CHECKSUM_AGG(BINARY_CHECKSUM(RECNO,UNICUM_NUM,NUM_PREDMT,NAME_PREDM,
 ID_SCLAD,DATE_PREDM,TYPDOCM_PR,STND_UCHET,VOZVRAT_PR,KOLC_PREDM,SUM_PREDM,
 SUM_VALUT,NALOGMONEY,NALOGVALUT,ORG_PREDM,PARTIA,SROK)) FROM dbo.SCL_MOVE WHERE RECNO IN (4910380,5023205))
BEGIN RAISERROR('Protected state changed',16,1) RETURN END
SELECT @case_name AS case_name,@rc AS return_code,@art AS processed_art,@next AS next_art,
 @code AS problem_code,@recno AS problem_recno,@formula AS problem_formula,
 @numerator AS problem_numerator,@denominator AS problem_denominator,
 @quantity_before AS quantity_before,@movement_qty AS movement_quantity,
 @tran AS transaction_before,@@TRANCOUNT AS transaction_after,'PASS' AS assertions
SELECT KOL_SUM,UCHET_SUM,UCHET_CENA,KON_KOLCH,REZ_KOLCH,UCHET_SMVL,UCHET_VALT
 FROM dbo.SCL_ARTC WHERE COD_ARTIC='TST-33-WRAPPER' AND ID_SCLAD=@warehouse
"""


def render(case):
    values = CASES[case]
    names = ('case_name','warehouse','tax_included','q0','p0','dq','amount','old_amount',
             'is_return','tax','tail_sale','expected_rc','expected_q','expected_sum','expected_price')
    def literal(v):
        return "'" + v + "'" if isinstance(v, str) else repr(v)
    assignments = 'SELECT ' + ','.join('@' + n + '=' + literal(v) for n, v in zip(names, values))
    return SQL.replace('__PARAMETERS__', assignments)


if __name__ == '__main__':
    if sys.argv[1:] == ['--baseline']:
        print(BASELINE_SQL)
        raise SystemExit(0)
    if len(sys.argv) != 2 or int(sys.argv[1]) not in CASES:
        raise SystemExit('Specify case 1..13; execute only through managed ROLLBACK lab API')
    print(render(int(sys.argv[1])))
