SET NOCOUNT ON
IF DB_NAME()<>'Paint_Rus' OR @@TRANCOUNT<>1 BEGIN RAISERROR('Lab boundary required',16,1) RETURN END
IF EXISTS(SELECT 1 FROM dbo._PARTNER WHERE N_USER='TSTWFP17')
 OR EXISTS(SELECT 1 FROM dbo.SCL_PLAT WHERE DOCUMN_POR='TST-WFP-UNALLOCATED-20261016')
 OR EXISTS(SELECT 1 FROM dbo.TMP_MOVE WHERE UNICUM_NUM=-16102617)
 BEGIN RAISERROR('Fixture already exists',16,1) RETURN END
DECLARE @tx int,@ret int,@payment int,@number int,@name varchar(20),@qty float,@required float,@position smallint
SELECT @tx=@@TRANCOUNT,@number=NULL,@payment=NULL
INSERT dbo._PARTNER(N_USER,NAME_USER,NAMEP_USER,MY_ORGANIZ)
VALUES('TSTWFP17','TST WFP unallocated','TST WFP unallocated','К')
EXEC @ret=dbo.INSERT_PLAT
 @skip_test=0,@sclad=5,@date_p_por='16.10.2026',@sum_por=4,
 @nalog1_por=NULL,@prcnt_por=0,@stnd_uchet=1,@codcel_por='TST',
 @documn_por='TST-WFP-UNALLOCATED-20261016',@prilog_por=NULL,@osnovanie='WFP independent payment fixture',
 @n_sf_por=NULL,@buhschetpl=NULL,@naclad_por=0,@uchet_move=1,@contr_por=NULL,
 @not_nal=1,@valutroubl=1,@cod_valut=NULL,@sumval_por=0,@nalog_por=0,@nalogprcnt=0,
 @nalg2prcnt=0,@type_por=1,@l_name_por='TST WFP unallocated',@nalog2_por=NULL,
 @otmetka=0,@vid_doc='РЕАЛИЗАЦИЯ',@org_predm='TSTWFP17',@notovarost=4,@notovarval=0,
 @nalogvalut=0,@unicum_tmp=-16102617,@corr_unicum=NULL,
 @unicum_plt=@payment OUTPUT,@n_plat_por=@number OUTPUT,@name_predm=@name OUTPUT,
 @kolc_predm=@qty OUTPUT,@koltreb_pr=@required OUTPUT,@num_predmt=@position OUTPUT,
 @family='TST-WFP',@is_nalprod=NULL,@nds_torgn=NULL,@my_org=NULL,@ist_inf=NULL,@spec_act=1
IF @ret IS NULL OR @ret<>0 OR @payment IS NULL OR @number IS NULL OR @@TRANCOUNT<>@tx
 BEGIN RAISERROR('Native result or boundary failed',16,1) RETURN END
IF (SELECT COUNT(*) FROM dbo.SCL_PLAT WHERE UNICUM_PLT=@payment AND SUM_POR=4 AND NOTOVAROST=4
 AND ID_SCLAD=5 AND TYPE_POR=1 AND NOT_NAL=1 AND STND_UCHET=1 AND UCHET_MOVE=1 AND ORG_PREDM='TSTWFP17')<>1
 OR EXISTS(SELECT 1 FROM dbo.SCL_PMOV WHERE UNICUM_PLT=@payment)
 OR EXISTS(SELECT 1 FROM dbo.TMP_MOVE WHERE UNICUM_NUM=-16102617)
 BEGIN RAISERROR('Independent payment postcondition failed',16,1) RETURN END
SELECT @ret AS native_return,@tx AS transaction_before,@@TRANCOUNT AS transaction_after,
 4 AS amount,4 AS unallocated,5 AS payment_warehouse,
 (SELECT COUNT(*) FROM dbo.SCL_PMOV WHERE UNICUM_PLT=@payment) AS allocations,
 'POSTCONDITIONS_PASSED' AS outcome
-- Lab must roll back the whole fixture. No COMMIT.
