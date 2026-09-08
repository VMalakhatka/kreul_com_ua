"""Source-bound SQL 2000 regression: --emit-sql | bin/folio-rus-lab execute.

Runs the candidate procedure BODY against disposable #tables, not dbo tables.
This is not an installed-procedure/production golden-master certification.
"""
from pathlib import Path
import re
import sys
import unittest
import installed_negative_correction_probe as installed

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / 'docs/folio-experiments'
RUS = DOCS / '33_upgrade_safe_average_operands_paint_rus.sql'
UA = DOCS / '34_upgrade_safe_average_operands_paint_ua.sql'
TABLES = ('SCL_ARTC', 'SCL_MOVE', 'SCL_SROK', 'SCL_PRIC', 'SCLAD_R', 'TMP_MOVE', 'TIP_TOVR')


def procedure_body(source):
    return source.split('\n AS\n', 1)[1].split('\nGO\n', 1)[0].strip()


def isolated_body():
    body = procedure_body(RUS.read_text())
    for table in TABLES:
        body = re.sub(r'\b' + table + r'\b', '#' + table, body)
    # TIP_TOVR is also the name of a column, not only a table.
    body = body.replace('SIGNIFIC=#TIP_TOVR', 'SIGNIFIC=TIP_TOVR')
    # Modes 1/2 are guarded out; no call to ANY installed procedure is permitted.
    body = re.sub(r'EXEC i_end_period[^\n]*',
                  "RAISERROR('Unexpected period branch',16,1)", body)
    body = re.sub(r'RETURN (31|32|20|@@error|@ret)',
                  r'BEGIN SET @fixture_rc=\1 GOTO FIXTURE_END END', body,
                  flags=re.I)
    code = re.sub(r'/\*.*?\*/|--[^\n]*', '', body, flags=re.S)
    assert not re.search(r'\bEXEC(?:UTE)?\b|\bRETURN\b|\bdbo\.', code, re.I)
    for table in TABLES:
        assert not re.search(r'\b(?:FROM|JOIN|UPDATE|INTO)\s+' + table + r'\b', code, re.I)
    return body


PREFIX = r"""
-- Generated from script 33; no business table or installed procedure is written.
SET NOCOUNT ON
IF DB_NAME()<>'Paint_Rus' OR @@TRANCOUNT<1
BEGIN
 RAISERROR('Paint_Rus managed transaction required',16,1)
 RETURN
END
CREATE TABLE #SCL_ARTC (
 COD_ARTIC varchar(20), ID_SCLAD int, SIGNIFIC int,
 NACH_KOLCH float, UCHET_0_C float, UCHET_0_VL float,
 KON_KOLCH float, REZ_KOLCH float, KOL_SUM float, UCHET_SUM float,
 UCHET_SMVL float, UCHET_CENA float, UCHET_VALT float,
 CENA_ARTIC float, CENA_VALT float, CENA_BZNAL float, CENA_V_BZN float,
 PRIZN_VALT bit, FIX_NACEN bit, NDS_ARTIC float, COEF_BZNAL float)
CREATE TABLE #SCL_MOVE (
 RECNO int, UNICUM_NUM float, ID_SCLAD int, NAME_PREDM varchar(20),
 TYPDOCM_PR char(1), DATE_PREDM datetime, ORG_PREDM varchar(8), VOZVRAT_PR bit,
 KOLC_PREDM float, SUM_PREDM float, SUM_VALUT float, NALOGMONEY float,
 NALOGVALUT float, PARTIA varchar(20), SROK datetime, SUM_UCHET float,
 SUM_UCVAL float, NUMDOCM_PR float, STND_UCHET bit)
CREATE TABLE #SCL_SROK (ID_SCLAD int, ARTICUL varchar(20), PARTIA varchar(20),
 SROK datetime,N3 float,N4 float,N5 float,N6 float,OSTATOK_NEWPERIOD float)
CREATE TABLE #SCL_PRIC (ID_SCLAD int,COD_ARTIC varchar(20),COEF_PRICE float,
 RUB_PRICE float,VALT_PRICE float)
CREATE TABLE #SCLAD_R (ID_SCLAD int,N_2 float,N_4 int)
CREATE TABLE #TIP_TOVR (TIP_TOVR int,CHECK_SAVE bit,SHOW_OSTATOK bit)
CREATE TABLE #TMP_MOVE (UNICUM_NUM int,NUM_PREDMT int,KOLC_PREDM float,
 SUM_PREDM float,SUM_VALUT float,ID_SCLAD int)
CREATE TABLE #cases (id int,name varchar(70),q0 float,p0 float,dq float,
 amount float,old_amount float,is_return bit,tax float,include_tax bit,
 tail_sale bit,expected_rc int,expected_q float,expected_sum float,expected_price float)
INSERT #cases VALUES(1,'negative_correction_legacy_zero',2,18.72,-1,-18.77,0,0,0,0,0,0,1,37.44,37.44)
INSERT #cases VALUES(2,'negative_correction_signed_amount',2,18.72,-1,-18.77,-18.77,0,0,0,0,0,1,18.67,18.67)
INSERT #cases VALUES(3,'positive_receipt_weighted_price',2,18.72,1,18.77,0,0,0,0,0,0,3,56.21,56.21e0/3)
INSERT #cases VALUES(4,'first_receipt',0,0,1,18.77,0,0,0,0,0,0,1,18.77,18.77)
INSERT #cases VALUES(5,'zero_denominator_skip',1,18.72,-1,-18.77,-18.77,0,0,0,0,20,NULL,NULL,NULL)
INSERT #cases VALUES(6,'clean_after_skipped',0,0,2,40,0,0,0,0,0,0,2,40,20)
INSERT #cases VALUES(7,'negative_return_fallback',2,18.72,-1,-18.77,0,1,0,0,0,0,1,18.72,18.72)
INSERT #cases VALUES(8,'positive_return_fallback',2,18.72,1,18.77,0,1,0,0,0,0,3,56.16,18.72)
INSERT #cases VALUES(9,'near_zero_denominator_skip',1,18.72,-1+5e-12,-18.77,-18.77,0,0,0,0,20,NULL,NULL,NULL)
INSERT #cases VALUES(10,'receipt_tax_excluded',2,18.72,1,24,0,0,4,0,0,0,3,57.44,57.44e0/3)
INSERT #cases VALUES(11,'receipt_tax_included',2,18.72,1,24,0,0,4,1,0,0,3,61.44,61.44e0/3)
INSERT #cases VALUES(12,'negative_then_full_sale_last_price',2,18.72,-1,-18.77,0,0,0,0,1,0,0,0,37.44)
INSERT #cases VALUES(13,'currency_tax_excluded',2,18.72,1,24,0,0,4,0,0,0,3,57.44,57.44e0/3)
INSERT #cases VALUES(14,'currency_tax_included',2,18.72,1,24,0,0,4,1,0,0,3,61.44,61.44e0/3)
INSERT #cases VALUES(15,'zero_return_denominator_skip',1,18.72,-1,-18.77,0,1,0,0,0,20,NULL,NULL,NULL)
CREATE TABLE #outcome (case_id int,case_name varchar(70),return_code int,
 quantity float,amount float,price float,problem_code varchar(40),
 problem_formula varchar(40),problem_recno int,denominator float)
DECLARE @case int,@case_name varchar(70),@q0 float,@p0 float,@dq float,
 @amount float,@old_amount float,@is_return bit,@tax float,@tail_sale bit,
 @expected_rc int,@expected_q float,@expected_sum float,@expected_price float,
 @fixture_rc int,@actual_q float,@actual_sum float,@actual_price float,
 @before_hash int,@tmp_hash int,@other_hash int,@start_tran int
DECLARE @n_group int,@id_sclad int,@usredn bit,@uchet_rsc int,@period_rsc int,
 @uch_nal bit,@art varchar(20),@n_cur int,@n_tot int,@new_art varchar(20),
 @otr_date char(10),@problem_code varchar(40),@problem_art varchar(20),
 @problem_recno int,@problem_date datetime,@problem_formula varchar(40),
 @problem_numerator float,@problem_denominator float,@problem_quantity_before float,
 @problem_movement_quantity float
SET @case=1
SET @start_tran=@@TRANCOUNT
WHILE @case<=15
BEGIN
 DELETE FROM #SCL_ARTC
 DELETE FROM #SCL_MOVE
 DELETE FROM #TMP_MOVE
 SELECT @case_name=name,@q0=q0,@p0=p0,@dq=dq,@amount=amount,@old_amount=old_amount,
 @is_return=is_return,@tax=tax,@uch_nal=include_tax,@tail_sale=tail_sale,
 @expected_rc=expected_rc,@expected_q=expected_q,@expected_sum=expected_sum,
 @expected_price=expected_price FROM #cases WHERE id=@case
 INSERT #SCL_ARTC VALUES('A-TEST',5,0,@q0,@p0,0,777,33,999,999,0,999,0,100,0,100,0,0,0,0,1)
 INSERT #SCL_ARTC VALUES('Z-OTHER',5,0,0,0,0,888,44,123,456,0,7,0,100,0,100,0,0,0,0,1)
 INSERT #TMP_MOVE VALUES(123,1,5,100,0,5)
 INSERT #SCL_MOVE VALUES(2210602,178222,5,'A-TEST','П','20170406','TEST',@is_return,
 @dq,@amount,0,@tax,0,NULL,NULL,@old_amount,0,316936366,1)
 IF @case IN (13,14)
 BEGIN
  UPDATE #SCL_ARTC SET UCHET_0_VL=2 WHERE COD_ARTIC='A-TEST'
  UPDATE #SCL_MOVE SET SUM_VALUT=12,NALOGVALUT=2
 END
 IF @tail_sale=1
 INSERT #SCL_MOVE VALUES(2210603,178223,5,'A-TEST','Р','20170407','TEST',0,
 1,30,0,0,0,NULL,NULL,0,0,316936367,1)
 SELECT @before_hash=CHECKSUM_AGG(BINARY_CHECKSUM(RECNO,UNICUM_NUM,ID_SCLAD,NAME_PREDM,
 TYPDOCM_PR,DATE_PREDM,ORG_PREDM,VOZVRAT_PR,KOLC_PREDM,SUM_PREDM,SUM_VALUT,
 NALOGMONEY,NALOGVALUT,PARTIA,SROK,NUMDOCM_PR,STND_UCHET)) FROM #SCL_MOVE
 SELECT @tmp_hash=CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM #TMP_MOVE
 SELECT @other_hash=CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM #SCL_ARTC WHERE COD_ARTIC='Z-OTHER'
 SELECT @n_group=NULL,@id_sclad=5,@usredn=0,@uchet_rsc=0,@period_rsc=0,
 @art='A-TEST',@n_cur=0,@n_tot=0,@new_art=NULL,@otr_date=NULL,@fixture_rc=NULL
-- BEGIN SOURCE-BOUND BODY
"""

SUFFIX = r"""
-- END SOURCE-BOUND BODY
FIXTURE_END:
 SELECT @actual_q=KOL_SUM,@actual_sum=UCHET_SUM,@actual_price=UCHET_CENA
 FROM #SCL_ARTC WHERE COD_ARTIC='A-TEST'
 IF @fixture_rc IS NULL OR @fixture_rc<>@expected_rc OR @new_art IS NULL OR @new_art<>'Z-OTHER'
 BEGIN RAISERROR('Unexpected return or cursor',16,1) RETURN END
 IF @fixture_rc=0 AND (@actual_q IS NULL OR @actual_sum IS NULL OR @actual_price IS NULL
 OR ABS(@actual_q-@expected_q)>1e-8 OR ABS(@actual_sum-@expected_sum)>1e-8
 OR ABS(@actual_price-@expected_price)>1e-8)
 BEGIN RAISERROR('Wrong calculated balances or price',16,1) RETURN END
 IF @fixture_rc=20 AND (@problem_code IS NULL OR @problem_code<>'ZERO_ACCOUNTING_DENOMINATOR'
 OR @problem_recno IS NULL OR @problem_recno<>2210602 OR @problem_art IS NULL
 OR @problem_art<>'A-TEST' OR @problem_date IS NULL OR @problem_date<>'20170406'
 OR @problem_denominator IS NULL OR ABS(@problem_denominator)>1e-11
 OR @problem_formula IS NULL OR @problem_formula<>CASE WHEN @is_return=1 THEN 'AVERAGE_RETURN' ELSE 'AVERAGE_RECEIPT' END
 OR @problem_quantity_before IS NULL OR ABS(@problem_quantity_before-@q0)>1e-12
 OR @problem_movement_quantity IS NULL OR ABS(@problem_movement_quantity-@dq)>1e-12
 OR @problem_numerator IS NULL OR ABS(@problem_numerator-(@q0*@p0+CASE WHEN @is_return=1 THEN @p0*@dq ELSE @amount END))>1e-8)
 BEGIN RAISERROR('Incomplete zero denominator diagnostics',16,1) RETURN END
 IF @case IN (13,14) AND EXISTS(SELECT 1 FROM #SCL_ARTC WHERE COD_ARTIC='A-TEST'
 AND (UCHET_SMVL IS NULL OR UCHET_VALT IS NULL
 OR ABS(UCHET_SMVL-CASE WHEN @uch_nal=1 THEN 16 ELSE 14 END)>1e-8
 OR ABS(UCHET_VALT-CASE WHEN @uch_nal=1 THEN 16e0/3 ELSE 14e0/3 END)>1e-8))
 BEGIN RAISERROR('Wrong currency amount or price',16,1) RETURN END
 IF @@TRANCOUNT<>@start_tran
 BEGIN RAISERROR('Transaction boundary changed',16,1) RETURN END
 IF EXISTS(SELECT 1 FROM #SCL_ARTC WHERE COD_ARTIC='A-TEST' AND (KON_KOLCH<>777 OR REZ_KOLCH<>33 OR NACH_KOLCH<>@q0))
 OR (SELECT COUNT(*) FROM #SCL_MOVE)<>1+CONVERT(int,@tail_sale)
 OR @before_hash<>(SELECT CHECKSUM_AGG(BINARY_CHECKSUM(RECNO,UNICUM_NUM,ID_SCLAD,NAME_PREDM,
 TYPDOCM_PR,DATE_PREDM,ORG_PREDM,VOZVRAT_PR,KOLC_PREDM,SUM_PREDM,SUM_VALUT,
 NALOGMONEY,NALOGVALUT,PARTIA,SROK,NUMDOCM_PR,STND_UCHET)) FROM #SCL_MOVE)
 OR (SELECT COUNT(*) FROM #TMP_MOVE)<>1
 OR @tmp_hash<>(SELECT CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM #TMP_MOVE)
 OR (SELECT COUNT(*) FROM #SCL_ARTC)<>2
 OR @other_hash<>(SELECT CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM #SCL_ARTC WHERE COD_ARTIC='Z-OTHER')
 BEGIN RAISERROR('Protected fixture state changed',16,1) RETURN END
 INSERT #outcome VALUES(@case,@case_name,@fixture_rc,@actual_q,@actual_sum,@actual_price,
 @problem_code,@problem_formula,@problem_recno,@problem_denominator)
 SET @case=@case+1
END
SELECT * FROM #outcome ORDER BY case_id
SELECT COUNT(*) AS passed_cases,@@TRANCOUNT AS transaction_count FROM #outcome
"""


def fixture_sql():
    return PREFIX + isolated_body() + '\n' + SUFFIX


class NegativeCorrectionSqlTest(unittest.TestCase):
    def test_installed_probe_is_guarded_and_uses_real_wrapper(self):
        self.assertEqual(13, len(installed.CASES))
        for case in installed.CASES:
            sql = installed.render(case)
            self.assertIn("IF DB_NAME()<>'Paint_Rus' OR @@TRANCOUNT<1", sql)
            self.assertEqual(1, sql.count('EXECUTE @rc=dbo.LAVKA_I_UCHET_TOVAR_SAFE'))
            self.assertNotIn('__PARAMETERS__', sql)
            self.assertNotRegex(sql, r'(?i)INSERT\s+(?:INTO\s+)?dbo\.(?:SCL_MOVE|SCL_PRIC|SCL_NAKL)\b')
            code = re.sub(r"'(?:''|[^'])*'|--[^\n]*", '', sql)
            self.assertNotRegex(code, r'(?i)\b(?:COMMIT|ROLLBACK|ALTER|CREATE)\b')

    def test_installed_baseline_is_read_only(self):
        self.assertNotRegex(installed.BASELINE_SQL, r'(?i)\b(?:INSERT|UPDATE|DELETE|EXECUTE|COMMIT)\b')
        self.assertIn("COD_ARTIC LIKE 'TST-33-%'", installed.BASELINE_SQL)

    def test_candidate_bodies_match(self):
        self.assertEqual(procedure_body(RUS.read_text()), procedure_body(UA.read_text()))

    def test_both_branches_freeze_operands(self):
        body = procedure_body(RUS.read_text())
        self.assertEqual(2, body.count('SET @last_uc = @lavka_numerator / @lavka_denominator'))
        self.assertEqual(2, body.count('SET @last_val = @lavka_currency_numerator / @lavka_denominator'))
        self.assertNotRegex(body, r'@last_uc\s*=\s*\(@sum0\+')
        self.assertIn('@old_uc>=@sum_predm+1e-10', body)

    def test_fixture_only_targets_local_tables(self):
        sql = fixture_sql()
        self.assertNotIn('dbo.', sql)
        self.assertNotRegex(sql, r'(?i)\b(?:ALTER|CREATE)\s+PROCEDURE')
        self.assertEqual(15, len(re.findall(r'INSERT #cases VALUES', sql)))
        self.assertIn('Wrong calculated balances or price', sql)

    def test_database_guards(self):
        for path, database in ((RUS, 'Paint_Rus'), (UA, 'Paint_Ua')):
            source = path.read_text()
            self.assertIn("IF DB_NAME() <> '" + database + "'", source)
            self.assertIn('IF @@TRANCOUNT <> 0', source)
            self.assertIn('ALTER PROCEDURE dbo.LAVKA_I_UCHET_1_TOVAR_SAFE', source)
            self.assertNotIn('ALTER PROCEDURE dbo.I_UCHET', source)


if __name__ == '__main__':
    if sys.argv[1:] == ['--emit-sql']:
        print(fixture_sql())
    else:
        unittest.main()
