/*
  Paint_Rus only. Validated against SQL Server 8.00 / compatibility 80.
  Creates separate LAVKA procedures; does not alter Folio I_UCHET_* objects.
  First release supports only N_4 IS NULL and average accounting mode (N_2=1000).
  One call processes exactly one SKU and returns new_art for Java continuation.
  Calls require an existing outer transaction. Return code 20 must be rolled back.
  After installation, DBA must grant EXECUTE only to the intended lab service user.
*/
IF DB_NAME() <> 'Paint_Rus'
BEGIN
    RAISERROR('STOP wrong database', 16, 1)
    RETURN
END
GO

/****** Object:  Stored Procedure dbo.I_UCHET_1_TOVAR    Script Date: 06.04.01 11:57:18 ******/
/* Изменена 4-aug-98: обработка отрицательных остатков */
/* 13-aug-98: добавлен партионный метод (5) */
/* 14-aug-98: исправлены возвраты по ФИФО (or kol0<=0) */
/* 24-sep-98: FIFO/Fetch Next->Close+Open */
/* 11-dec-98: партионный - нач.данные до 2-х знаков */
/* 15-jan-99: for vozvrat party */

/* 18-jan-99: fix_nacen=0 */
/* 07-apr-99: MS_SQL 7.0 Compatible */
/* 13-apr-99: Vozvrat & NACH_KOLCH=0 */
/* 09-sep-99: Update SCL_PRIC where FIX_NACEN and COEF>0 */
/* 01-nov-99: MS_SQL 7.0 Compatible */
/* 12-nov-99: IsNull(SROK,... */
/* 15-dec-99: новый период для партий */
/* 17-apr-2000: Insert #prihod -> Update */
/* 02-jun-2000: #prihod -> TMP_MOVE */
/* 03-jan-2001: не менять приход! */
/* 15-feb-2002: TIP_TOVR */
/* 16-jul-2002: учтено отсутствие руб.цены */
/* 13-nov-2003: нет цены партии - брать нач.уч.цену партии */
CREATE PROCEDURE LAVKA_I_UCHET_1_TOVAR_SAFE @n_group int, @id_sclad int, @usredn bit=0,
			      @uchet_rsc int, @period_rsc int, @uch_nal bit=1,
				@art varchar(20) OUT, @n_cur int OUT, @n_tot int OUT,
				@new_art varchar(20) OUT, @otr_date char(10)=null OUT,
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
DECLARE @ret int, @artic varchar(20), @time0 datetime, @lavka_denominator float

SELECT @problem_code=NULL, @problem_art=NULL, @problem_recno=NULL,
       @problem_date=NULL, @problem_formula=NULL, @problem_numerator=NULL,
       @problem_denominator=NULL, @problem_quantity_before=NULL,
       @problem_movement_quantity=NULL

IF @@TRANCOUNT<1
BEGIN
  SELECT @problem_code='TRANSACTION_REQUIRED'
  RETURN 31
END

IF @n_group IS NOT NULL OR @uchet_rsc<>0 OR @period_rsc<>0 OR @uch_nal<>0
BEGIN
  SELECT @problem_code='UNSUPPORTED_SCOPE_OR_MODE'
  RETURN 32
END

IF @uchet_rsc=3
   GOTO SAVE_

SELECT @n_cur = 0, @time0 = GetDate(), @otr_date = Null

ONE_MORE:

IF @art IS NULL
BEGIN

  SELECT @artic = MIN(COD_ARTIC), @n_tot = COUNT(*)*40
       FROM SCL_ARTC WHERE
			(ID_SCLAD=@id_sclad )
  SELECT @n_tot = @n_tot + COUNT(*) FROM SCL_MOVE
	WHERE	TYPDOCM_PR <> 'С' AND STND_UCHET=1 AND (ID_SCLAD=@id_sclad )
END
ELSE

  SELECT @artic = @art
SELECT @n_cur = @n_cur + 40 + COUNT(*) FROM SCL_MOVE
	WHERE  NAME_PREDM=@artic AND
		TYPDOCM_PR <> 'С' AND STND_UCHET=1 AND (ID_SCLAD=@id_sclad )

IF Exists (SELECT 1 FROM SCL_ARTC a (NOLOCK), TIP_TOVR t (NOLOCK)
	   WHERE COD_ARTIC=@artic AND ID_SCLAD=@id_sclad
		    AND SIGNIFIC=TIP_TOVR AND CHECK_SAVE=0 AND SHOW_OSTATOK=0)
 GOTO SAVE_

DECLARE @sum0 float, @sum0v float, @name_predm varchar(20),
	@typdocm_pr char(1), @date_predm datetime, @org_predm varchar(8),
	@vozvrat_pr bit, @kolc_predm float, @sum_predm float, @sum_valut float,
	@nalogmoney float, @nalogvalut float, @sum_uchet float, @sum_ucval float,
	@kol0 float, @kol float, @sum float, @sumv float,
	@kol00 float, @sum00 float, @sum00v float, @end_period datetime,
	@fs int, @ncount int, @i int,
	@last_uc float, @last_val float,
	@partia varchar(20), @srok datetime,
	@uchet_0_c float, @uchet_0_vl float,
	@un_tmp int, @n_tmp int, @recno int,
	@old_uc float, @old_uv float
IF @uchet_rsc=4
   GOTO TRAN_
SELECT @end_period = CONVERT(datetime,'01.01.1753')
DECLARE move CURSOR FOR
	SELECT TYPDOCM_PR, DATE_PREDM, ORG_PREDM, VOZVRAT_PR, KOLC_PREDM, SUM_PREDM,
	       SUM_VALUT, NALOGMONEY, NALOGVALUT, NAME_PREDM,
		PARTIA, SROK,
		RECNO,
		SUM_UCHET, SUM_UCVAL
	 FROM SCL_MOVE
	 WHERE NAME_PREDM=@artic AND
		TYPDOCM_PR <> 'С' AND STND_UCHET=1 AND (ID_SCLAD=@id_sclad )
	 ORDER BY DATE_PREDM, TYPDOCM_PR, NUMDOCM_PR

IF @uchet_rsc<>5
 SELECT @un_tmp=IsNull(MAX(UNICUM_NUM),0)+1, @n_tmp=0
  FROM TMP_MOVE

TRAN_:

IF @uchet_rsc=3 OR @uchet_rsc=4
BEGIN
   IF @uchet_rsc=4
      UPDATE SCL_MOVE SET SUM_UCHET = KOLC_PREDM*ISNULL(UCHET_CENA,0),
			SUM_UCVAL = KOLC_PREDM*ISNULL(UCHET_VALT,0)
	FROM SCL_ARTC
	WHERE NAME_PREDM=@artic AND
		TYPDOCM_PR<>'С' AND STND_UCHET=1 AND
		(SCL_MOVE.ID_SCLAD=@id_sclad ) AND SCL_ARTC.COD_ARTIC=@artic AND
		SCL_ARTC.ID_SCLAD=SCL_MOVE.ID_SCLAD
   GOTO SAVE_
END

IF @uchet_rsc=5
BEGIN
    UPDATE SCL_SROK SET N5 = IsNull(N5,IsNull(N3,0)),
			 N6 = IsNull(N6,IsNull(N4,0)),
			N3 = IsNull(N5,IsNull(N3,0)),
			N4 = IsNull(N6,IsNull(N4,0))
	WHERE ID_SCLAD=@id_sclad AND
		ARTICUL=@artic
    SELECT  @sum0 = IsNull(SUM(ISNULL(NACH_KOLCH*UCHET_0_C,0)),0),
	    @sum0v = IsNull(SUM(ISNULL(NACH_KOLCH*UCHET_0_VL,0)),0),
	    @kol0 = IsNull(SUM(ISNULL(NACH_KOLCH,0)),0)
	  FROM SCL_ARTC
	  WHERE COD_ARTIC=@artic AND
		ID_SCLAD=@id_sclad
    SELECT  @sum00 = IsNull(SUM(ISNULL(OSTATOK_NEWPERIOD*N3,0)),0),
	    @sum00v = IsNull(SUM(ISNULL(OSTATOK_NEWPERIOD*N4,0)),0),
	    @kol00 = IsNull(SUM(ISNULL(OSTATOK_NEWPERIOD,0)),0)
	  FROM SCL_SROK
	  WHERE ARTICUL=@artic AND
		ID_SCLAD=@id_sclad
END
ELSE
BEGIN
    UPDATE SCL_ARTC SET UCHET_CENA = UCHET_0_C, UCHET_VALT = UCHET_0_VL,
		    KOL_SUM = NACH_KOLCH, UCHET_SUM = NACH_KOLCH*UCHET_0_C,
		    UCHET_SMVL = NACH_KOLCH*UCHET_0_VL
	WHERE COD_ARTIC=@artic AND
		ID_SCLAD=@id_sclad
 IF @@error<>0
 BEGIN
  SELECT @ret=10
  GOTO EX_PROC
 END

 SELECT  @sum0 = IsNull(SUM(ISNULL(NACH_KOLCH*UCHET_0_C,0)),0),
	@sum0v = IsNull(SUM(ISNULL(NACH_KOLCH*UCHET_0_VL,0)),0),
	@kol0 = IsNull(SUM(ISNULL(NACH_KOLCH,0)),0),
	@uchet_0_c = IsNull(MAX(UCHET_0_C),0),
	@uchet_0_vl = IsNull(MAX(UCHET_0_VL),0)
	  FROM SCL_ARTC
	  WHERE COD_ARTIC=@artic AND
		ID_SCLAD=@id_sclad
 Select @sum00 = @sum0, @sum00v = @sum0v, @kol00 = @kol0

     IF @kol0>0
	Select @last_uc = @sum0/@kol0, @last_val = @sum0v/@kol0
     ELSE
	Select @last_uc = 0, @last_val = 0

     IF @uchet_rsc=1 OR @uchet_rsc=2
     BEGIN
       IF @kol0>0
	BEGIN
	 Select @n_tmp=@n_tmp+1
	 INSERT INTO TMP_MOVE (UNICUM_NUM,NUM_PREDMT,KOLC_PREDM,SUM_PREDM,SUM_VALUT,ID_SCLAD)
	   VALUES (@un_tmp,@n_tmp,@kol0,@sum0,@sum0v,0)	END
     END
END

OPEN move

WHILE 1>0
BEGIN
  FETCH NEXT FROM move INTO @typdocm_pr, @date_predm, @org_predm, @vozvrat_pr,
			  @kolc_predm, @sum_predm, @sum_valut, @nalogmoney,
			  @nalogvalut, @name_predm, @partia, @srok, @recno,
			  @old_uc, @old_uv
  IF @@FETCH_STATUS<>0
  BEGIN
     IF @uchet_rsc=5
	BREAK
     IF IsNull(@kol0,0)<0.000000001 OR @uchet_rsc=1 OR @uchet_rsc=2
     BEGIN
	Select @kol0 = 0
	IF @uchet_rsc=1 OR @uchet_rsc=2

	BEGIN
	  IF @uchet_rsc=2
	    SET ROWCOUNT 1

	  SELECT @kol0 = Kolc_Predm, @sum0 = Sum_Predm, @sum0v = Sum_Valut
	    FROM /*#prihod*/ TMP_MOVE
	    WHERE IsNull(Kolc_Predm,0)>0.0000000001
		AND UNICUM_NUM=@un_tmp

	  SET ROWCOUNT 0
	END
     END

     UPDATE SCL_ARTC SET KOL_SUM = ISNULL(@kol0,0), UCHET_SUM = @sum0, UCHET_SMVL = @sum0v,
	    UCHET_CENA = CASE WHEN @kol0>0 THEN @sum0/@kol0 ELSE IsNull(@last_uc,0) END,
	    UCHET_VALT = CASE WHEN @kol0>0 THEN @sum0v/@kol0 ELSE IsNull(@last_val,0) END
	WHERE COD_ARTIC=@artic AND (ID_SCLAD=@id_sclad )
     BREAK
  END
     IF (@uchet_rsc=1 OR @uchet_rsc=2) AND @date_predm>=@end_period
     BEGIN
       EXEC i_end_period @period_rsc, @date_predm, @end_period OUT
       SELECT @kol0 = ISNULL(SUM(Kolc_Predm),0), @sum0 = ISNULL(SUM(Sum_Predm),0), @sum0v = ISNULL(SUM(Sum_Valut),0)
	 FROM TMP_MOVE WHERE UNICUM_NUM=@un_tmp
              AND KOLC_PREDM<>0
       DELETE /*FROM #prihod*/
	 FROM TMP_MOVE WHERE UNICUM_NUM=@un_tmp
       IF @kol0>0
	BEGIN
	 Select @n_tmp = @n_tmp+1
	 INSERT INTO TMP_MOVE (UNICUM_NUM,NUM_PREDMT,KOLC_PREDM,SUM_PREDM,SUM_VALUT,ID_SCLAD)
		 VALUES (@un_tmp,@n_tmp,@kol0,@sum0,@sum0v,0)

	  Select @last_uc = @sum0/@kol0, @last_val = @sum0v/@kol0
	END
     END

  IF @typdocm_pr='П'
  BEGIN
    IF @vozvrat_pr=0
    BEGIN
     IF @old_uc>=@sum_predm+1e-10 OR @old_uv>=@sum_valut+1e-10
      Select @sum_predm = @old_uc, @sum_valut = @old_uv
     ELSE
     BEGIN
      IF @uchet_rsc=5 AND IsNull(@partia,'')='' AND @srok IS NULL
	SELECT @sum_predm = UCHET_0_C * @kolc_predm, @sum_valut = UCHET_0_VL * @kolc_predm
	 FROM SCL_ARTC
	  WHERE COD_ARTIC=@artic AND ID_SCLAD=@id_sclad
      ELSE
      BEGIN
       IF @uch_nal=0
	SELECT @sum_predm = @sum_predm - Abs(@nalogmoney), @sum_valut = @sum_valut - Abs(@nalogvalut)
      END

      UPDATE SCL_MOVE SET SUM_UCHET=ISNULL(@sum_predm,0), SUM_UCVAL=ISNULL(@sum_valut,0)
	WHERE /*CURRENT OF move*/ RECNO=@recno
		AND (SUM_UCHET<>ISNULL(@sum_predm,0) OR SUM_UCVAL<>ISNULL(@sum_valut,0))
     END

      IF @uchet_rsc=0
      BEGIN
         SELECT @lavka_denominator=ISNULL(@kol0,0)+ISNULL(@kolc_predm,0)
         IF ABS(@lavka_denominator)<=0.00000000001
         BEGIN
           SELECT @problem_code='ZERO_ACCOUNTING_DENOMINATOR',
                  @problem_art=@artic, @problem_recno=@recno,
                  @problem_date=@date_predm, @problem_formula='AVERAGE_RECEIPT',
                  @problem_numerator=ISNULL(@sum0,0)+ISNULL(@sum_predm,0),
                  @problem_denominator=@lavka_denominator,
                  @problem_quantity_before=@kol0,
                  @problem_movement_quantity=@kolc_predm
           GOTO LAVKA_MOVE_DONE
         END
         SELECT @sum0 = @sum0 + @sum_predm, @sum0v = @sum0v + @sum_valut,
	        @kol0 = @kol0 + @kolc_predm,
		@last_uc = (@sum0+@sum_predm)/(@kol0+@kolc_predm),
		@last_val = (@sum0v+@sum_valut)/(@kol0+@kolc_predm)
      END
      ELSE
      BEGIN
	IF @uchet_rsc=5
	BEGIN
	  IF IsNull(@partia,'')='' AND @srok IS NULL
	  BEGIN
	    IF @otr_date IS NULL
		Select @otr_date=Convert(char(10),@date_predm,104), @art=@artic
	  END
	  ELSE
	    UPDATE SCL_SROK SET
		N3 = @sum_predm/@kolc_predm,
		N4 = @sum_valut/@kolc_predm
		WHERE ID_SCLAD=@id_sclad AND ARTICUL=@artic
		  AND IsNull(PARTIA,'')=IsNull(@partia,'') AND IsNull(SROK,'01.01.1753')=IsNull(@srok,'01.01.1753')
	END

	ELSE
	BEGIN
	 Select @n_tmp = @n_tmp+1
	 INSERT INTO TMP_MOVE (UNICUM_NUM,NUM_PREDMT,KOLC_PREDM,SUM_PREDM,SUM_VALUT,ID_SCLAD)
		 VALUES (@un_tmp,@n_tmp,@kolc_predm,@sum_predm,@sum_valut,0)

	 Select @last_uc = @sum_predm/@kolc_predm, @last_val = @sum_valut/@kolc_predm
         IF @uchet_rsc=1 OR @kol0<=0
           SELECT @sum0 = @sum_predm, @sum0v = @sum_valut, @kol0 = @kolc_predm
	END

      END
    END
    ELSE
    BEGIN
     IF @uchet_rsc<>5

     BEGIN
      DECLARE org CURSOR FOR
       SELECT KOLC_PREDM, SUM_UCHET, SUM_UCVAL
	FROM SCL_MOVE
	 WHERE ORG_PREDM=@org_predm AND DATE_PREDM<@date_predm AND
	       TYPDOCM_PR='Р' AND NAME_PREDM=@artic AND
	       (ID_SCLAD=@id_sclad )
	 ORDER BY DATE_PREDM DESC

      OPEN org

      FETCH NEXT FROM org INTO @kol,@sum,@sumv
      IF @@FETCH_STATUS<>0
      BEGIN
	IF @kol00<>0
	 SELECT @kol = @kol00, @sum = @sum00, @sumv = @sum00v
	ELSE
	 SELECT @kol = 1, @sum = @uchet_0_c, @sumv = @uchet_0_vl
      END
      CLOSE org
      DEALLOCATE org

      IF @kol=0 SELECT @kol=1
      SELECT @sum = @sum * @kolc_predm / @kol, @sumv = @sumv * @kolc_predm/@kol
     END
     ELSE
     BEGIN
	Select @sum = Null, @sumv = Null
	SELECT @sum = (CASE WHEN IsNull(N3,0)=0 and IsNull(N4,0)=0 THEN N5 ELSE N3 END) * @kolc_predm,
		 @sumv = (CASE WHEN IsNull(N3,0)=0 and IsNull(N4,0)=0 THEN N6 ELSE N4 END) * @kolc_predm
	  FROM SCL_SROK
	  WHERE ID_SCLAD=@id_sclad AND ARTICUL=@artic
		AND IsNull(PARTIA,'')=IsNull(@partia,'') AND IsNull(SROK,'01.01.1753')=IsNull(@srok,'01.01.1753')

	IF IsNull(@sum,0)=0 AND IsNull(@sumv,0)=0
	BEGIN
	 SELECT @sum = UCHET_0_C * @kolc_predm, @sumv = UCHET_0_VL * @kolc_predm
	   FROM SCL_ARTC	   WHERE COD_ARTIC=@artic AND ID_SCLAD=@id_sclad
	 IF IsNull(@sum,0)=0 AND IsNull(@sumv,0)=0
	 BEGIN
	  Select @sum = @sum_predm, @sumv = @sum_valut
	  IF @uch_nal=0
	   SELECT @sum = @sum_predm - Abs(@nalogmoney), @sumv = @sum_valut - Abs(@nalogvalut)
	 END
	  IF IsNull(@partia,'')='' AND @srok IS NULL
	  BEGIN
	    IF @otr_date IS NULL
		Select @otr_date=Convert(char(10),@date_predm,104), @art=@artic
	  END
	  ELSE
	    UPDATE SCL_SROK SET
		N3 = @sum/@kolc_predm,
		N4 = @sumv/@kolc_predm
		WHERE ID_SCLAD=@id_sclad AND ARTICUL=@artic
		  AND IsNull(PARTIA,'')=IsNull(@partia,'') AND IsNull(SROK,'01.01.1753')=IsNull(@srok,'01.01.1753')
	END
     END

      UPDATE SCL_MOVE SET SUM_UCHET=ISNULL(@sum,0), SUM_UCVAL=ISNULL(@sumv,0)
	WHERE /*CURRENT OF move*/ RECNO=@recno

		AND (SUM_UCHET<>ISNULL(@sum,0) OR SUM_UCVAL<>ISNULL(@sumv,0))
      IF @uchet_rsc=0
      BEGIN
         SELECT @lavka_denominator=ISNULL(@kol0,0)+ISNULL(@kolc_predm,0)
         IF ABS(@lavka_denominator)<=0.00000000001
         BEGIN
           SELECT @problem_code='ZERO_ACCOUNTING_DENOMINATOR',
                  @problem_art=@artic, @problem_recno=@recno,
                  @problem_date=@date_predm, @problem_formula='AVERAGE_RETURN',
                  @problem_numerator=ISNULL(@sum0,0)+ISNULL(@sum,0),
                  @problem_denominator=@lavka_denominator,
                  @problem_quantity_before=@kol0,
                  @problem_movement_quantity=@kolc_predm
           GOTO LAVKA_MOVE_DONE
         END
         SELECT @sum0 = @sum0 + @sum, @sum0v = @sum0v + @sumv,
	        @kol0 = @kol0 + @kolc_predm,

		@last_uc = (@sum0+@sum)/(@kol0 + @kolc_predm),
		@last_val = (@sum0v+@sumv)/(@kol0 + @kolc_predm)
      END
      ELSE IF @uchet_rsc<>5
      BEGIN
	 Select @n_tmp = @n_tmp+1
	 INSERT INTO TMP_MOVE (UNICUM_NUM,NUM_PREDMT,KOLC_PREDM,SUM_PREDM,SUM_VALUT,ID_SCLAD)
		 VALUES (@un_tmp,@n_tmp,@kolc_predm,@sum,@sumv,0)
	 Select @last_uc = @sum/@kolc_predm, @last_val = @sumv/@kolc_predm
         IF @uchet_rsc=1 OR @kol0<=0
           SELECT @sum0 = @sum, @sum0v = @sumv, @kol0 = @kolc_predm
      END
    END
  END
  ELSE  /* расход */
  BEGIN
    IF @uchet_rsc=5
    BEGIN
        Select @sum = Null, @sumv = Null
	SELECT @sum = N3*@kolc_predm, @sumv = N4*@kolc_predm
	  FROM SCL_SROK
	  WHERE ID_SCLAD=@id_sclad AND ARTICUL=@artic
		AND IsNull(PARTIA,'')=IsNull(@partia,'') AND IsNull(SROK,'01.01.1753')=IsNull(@srok,'01.01.1753')
	IF @sum IS Null AND @sumv IS Null
	 SELECT @sum = UCHET_0_C*@kolc_predm, @sumv = UCHET_0_VL*@kolc_predm
	   FROM SCL_ARTC
	   WHERE ID_SCLAD=@id_sclad AND COD_ARTIC=@artic
      UPDATE SCL_MOVE SET SUM_UCHET=ISNULL(@sum,0), SUM_UCVAL=ISNULL(@sumv,0)
	WHERE /*CURRENT OF move*/ RECNO=@recno
		AND (SUM_UCHET<>ISNULL(@sum,0) OR SUM_UCVAL<>ISNULL(@sumv,0))
    END
    ELSE
    IF @kol0>0
    BEGIN
      IF @uchet_rsc=1 OR @uchet_rsc=2
      BEGIN
	DECLARE prih CURSOR FOR
	   SELECT KOLC_PREDM,SUM_PREDM,SUM_VALUT FROM /*#prihod*/ TMP_MOVE		WHERE Kolc_Predm>0.000000001
			AND UNICUM_NUM=@un_tmp
	   ORDER BY NUM_PREDMT
	SELECT @sum_predm=0, @sum_valut=0

	OPEN prih
        FETCH NEXT FROM prih INTO @kol0, @sum0, @sum0v
	SELECT @fs = @@FETCH_STATUS

	IF @uchet_rsc=1

	BEGIN
	   SELECT @ncount = 0
	   WHILE @@FETCH_STATUS=0
	   BEGIN
	     SELECT @ncount = @ncount + 1
	     FETCH NEXT FROM prih INTO @kol, @sum, @sumv

	   END

	   CLOSE prih
	   OPEN prih
	   SELECT @i = 0

	   WHILE @i<@ncount
	   BEGIN
		FETCH NEXT FROM prih INTO @kol0,@sum0,@sum0v
	        SELECT @i = @i + 1
	   END
	END /* IF @uchet_rsc=1 */
	WHILE @fs=0 AND @kolc_predm>0.00000001
	BEGIN
	   IF @kol0<=@kolc_predm+0.000000001
	   BEGIN
	      SELECT @sum_predm = @sum_predm+@sum0, @sum_valut = @sum_valut+@sum0v,
			@kolc_predm = @kolc_predm-@kol0

	      UPDATE /*#prihod*/ TMP_MOVE SET KOLC_PREDM = 0
		 WHERE CURRENT OF prih

	      IF @uchet_rsc=1
	      BEGIN
		SELECT @ncount = @ncount - 1, @i = 0, @fs = 0
		IF @ncount>0
		BEGIN
		  CLOSE prih
	          OPEN prih
		  WHILE @i<@ncount
		  BEGIN
		    FETCH NEXT FROM prih INTO @kol0, @sum0, @sum0v
		    SELECT @i = @i + 1
		  END
		END
		ELSE
		  BREAK

	      END
	      ELSE

	      BEGIN
		CLOSE prih
		OPEN prih
		FETCH NEXT FROM prih INTO @kol0, @sum0, @sum0v
		SELECT @fs = @@FETCH_STATUS
		IF @fs<>0
		   SELECT @kol0 = 0, @sum0 = 0, @sum0v = 0
	      END
	   END

	   ELSE

	   BEGIN
	      SELECT @sum_predm = @sum_predm+@sum0*@kolc_predm/@kol0,
		     @sum_valut = @sum_valut+@sum0v*@kolc_predm/@kol0

	      SELECT @sum0 = @sum0*(1-@kolc_predm/@kol0),
		     @sum0v = @sum0v*(1-@kolc_predm/@kol0),
		     @kol0 = @kol0-@kolc_predm,
		     @kolc_predm = 0
	      UPDATE /*#prihod*/ TMP_MOVE SET Kolc_Predm = @kol0, Sum_Predm = @sum0, Sum_Valut = @sum0v
		WHERE CURRENT OF prih
	      BREAK
	   END
	END
	CLOSE prih
	DEALLOCATE prih

	IF @kolc_predm>0.0000000001
	   GOTO OTR_OST
      END

      ELSE  /* по средней */
      BEGIN
	IF @kolc_predm>@kol0+0.00000000001
	   GOTO OTR_OST
        SELECT @sum_predm = @sum0 * @kolc_predm / @kol0,
	       @sum_valut = @sum0v * @kolc_predm / @kol0

      END
      UPDATE SCL_MOVE SET SUM_UCHET = ISNULL(@sum_predm,0),
			  SUM_UCVAL=ISNULL(@sum_valut,0)
	WHERE /*CURRENT OF move*/ RECNO=@recno
		AND (SUM_UCHET <> ISNULL(@sum_predm,0) OR

			  SUM_UCVAL<>ISNULL(@sum_valut,0))
      IF @uchet_rsc=0
         SELECT @sum0 = @sum0 - @sum_predm, @sum0v = @sum0v - @sum_valut,
	        @kol0 = @kol0 - @kolc_predm
    END

    ELSE

    BEGIN

OTR_OST:
      IF @otr_date IS NULL
      BEGIN
	Select @otr_date=Convert(char(10),@date_predm,104), @art=@artic
      END
    END

  END
END

LAVKA_MOVE_DONE:
CLOSE move
DEALLOCATE move

IF @problem_code IS NOT NULL
BEGIN
  SELECT @art=@artic, @new_art=MIN(COD_ARTIC)
    FROM SCL_ARTC
   WHERE ID_SCLAD=@id_sclad AND COD_ARTIC>@artic
  RETURN 20
END

IF @uchet_rsc<>5
BEGIN
 UPDATE SCL_ARTC SET CENA_ARTIC = CASE WHEN PRIZN_VALT=1 OR FIX_NACEN=0 THEN CENA_ARTIC
					ELSE UCHET_CENA*(NDS_ARTIC/100+1) END,
		    CENA_VALT = CASE WHEN PRIZN_VALT=0 OR FIX_NACEN=0 THEN CENA_VALT
					ELSE UCHET_VALT*(NDS_ARTIC/100+1) END,
                    CENA_BZNAL = CASE WHEN PRIZN_VALT=1 OR FIX_NACEN=0 OR COEF_BZNAL<=0 THEN CENA_BZNAL

					ELSE UCHET_CENA*(NDS_ARTIC/100+1)*COEF_BZNAL END,
                    CENA_V_BZN = CASE WHEN PRIZN_VALT=0 OR FIX_NACEN=0 OR COEF_BZNAL<=0 THEN CENA_V_BZN
					ELSE UCHET_VALT*(NDS_ARTIC/100+1)*COEF_BZNAL END,
		    NDS_ARTIC = CASE WHEN FIX_NACEN=0 AND PRIZN_VALT=0 AND UCHET_CENA<>0
					THEN (CENA_ARTIC/UCHET_CENA-1)*100
				     WHEN FIX_NACEN=0 AND PRIZN_VALT=1 AND UCHET_VALT<>0
					THEN (CENA_VALT/UCHET_VALT-1)*100
				     ELSE NDS_ARTIC END
	WHERE /*FIX_NACEN=1 AND*/ (ID_SCLAD=@id_sclad ) AND
		COD_ARTIC=@artic
 UPDATE SCL_PRIC SET RUB_PRICE =  CENA_ARTIC*(COEF_PRICE),
		    VALT_PRICE =  CENA_VALT*(COEF_PRICE)
             FROM SCL_ARTC a
	WHERE SCL_PRIC.COD_ARTIC=@artic AND (SCL_PRIC.ID_SCLAD=@id_sclad)
		AND COEF_PRICE>0
		AND a.COD_ARTIC=@artic AND a.ID_SCLAD=SCL_PRIC.ID_SCLAD
		AND FIX_NACEN=1
END
SAVE_:

IF @uchet_rsc=3 OR NOT EXISTS (SELECT COD_ARTIC FROM SCL_ARTC
	WHERE (ID_SCLAD=@id_sclad) AND
		COD_ARTIC>@artic)
BEGIN
 DECLARE @num int
 SELECT @num = CASE @uch_nal WHEN 0 THEN 0 ELSE 100 END
 UPDATE SCLAD_R SET N_4 = @n_group, N_2 = 1000 + @num + @period_rsc*10 + @uchet_rsc
	WHERE ID_SCLAD=@id_sclad
 SELECT @new_art=NULL

END
ELSE

BEGIN
  SELECT @new_art = MIN(COD_ARTIC) FROM SCL_ARTC
	WHERE (ID_SCLAD=@id_sclad ) AND
		COD_ARTIC>@artic

  IF 1=0 AND ABS(DateDiff(ss,@time0,GetDate()))<10 AND DateDiff(mi,@time0,GetDate())<1 AND
     DateDiff(hh,@time0,GetDate())<1 AND DateDiff(dy,@time0,GetDate())<1 AND
     @otr_date IS Null
  BEGIN
    IF @uchet_rsc<3
    BEGIN
     DELETE FROM TMP_MOVE
	WHERE UNICUM_NUM=@un_tmp
    END

     SELECT @art = @new_art

     GOTO ONE_MORE
  END

END

SELECT @art=@artic
RETURN @@error

EX_PROC:
 RETURN @ret

END
GO

CREATE PROCEDURE LAVKA_I_UCHET_TOVAR_SAFE
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
