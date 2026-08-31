/* Read-only fill-rate probe. SQL Server 8.00 compatible. */

SELECT ID_SCLAD AS warehouse_id,
       COUNT(*) AS product_count,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TVR),'')<>'' THEN 1 ELSE 0 END) AS g1,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TV2),'')<>'' THEN 1 ELSE 0 END) AS g2,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TV3),'')<>'' THEN 1 ELSE 0 END) AS g3,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TV4),'')<>'' THEN 1 ELSE 0 END) AS g4,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TV5),'')<>'' THEN 1 ELSE 0 END) AS g5,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TV6),'')<>'' THEN 1 ELSE 0 END) AS g6,
       SUM(CASE WHEN DEPARTAM IS NOT NULL THEN 1 ELSE 0 END) AS department_rows,
       SUM(CASE WHEN ISNULL(RTRIM(TIP_TOVR),'')<>'' THEN 1 ELSE 0 END) AS type_rows,
       SUM(CASE WHEN ISNULL(RTRIM(EDIN_IZMER),'')<>'' THEN 1 ELSE 0 END) AS unit_rows,
       SUM(CASE WHEN ISNULL(EDN_V_UPAK,0)<>0 THEN 1 ELSE 0 END) AS package_rows,
       SUM(CASE WHEN ISNULL(MIN_PARTIA,0)<>0 THEN 1 ELSE 0 END) AS moq_rows,
       SUM(CASE WHEN ISNULL(MIN_TVRZAP,0)<>0 THEN 1 ELSE 0 END) AS min_stock_rows,
       SUM(CASE WHEN ISNULL(MAX_TVRZAP,0)<>0 THEN 1 ELSE 0 END) AS max_stock_rows
  FROM dbo.SCL_ARTC
 GROUP BY ID_SCLAD
 ORDER BY ID_SCLAD

SELECT COUNT(*) AS barcode_rows,
       COUNT(DISTINCT CAST(ID_SCLAD AS varchar(12))+'|'+RTRIM(ARTIC)) AS barcode_products,
       SUM(CASE WHEN ISNULL(RTRIM(BARCODE),'')<>'' THEN 1 ELSE 0 END) AS filled_barcodes,
       SUM(CASE WHEN ISNULL(RTRIM(PARTIA),'')<>'' THEN 1 ELSE 0 END) AS batch_rows,
       SUM(CASE WHEN SROK IS NOT NULL THEN 1 ELSE 0 END) AS expiry_rows
  FROM dbo.SCL_CODE

SELECT SIGNIFIC AS product_type_code, TIP_TOVAR AS product_type_name,
       CHECK_SAVE AS check_save, SHOW_OSTATOK AS show_stock
  FROM dbo.TIP_TOVR
 ORDER BY SIGNIFIC

SELECT SIGNIFIC AS unit_code
  FROM dbo.EDIN_IZM
 ORDER BY SIGNIFIC

SELECT o.name AS candidate_table
  FROM dbo.sysobjects o
 WHERE o.xtype='U'
   AND (o.name LIKE '%GROUP%'
        OR o.name LIKE '%GRUP%'
        OR o.name LIKE '%BRAND%'
        OR o.name LIKE '%PROIZV%'
        OR o.name LIKE '%DEPART%'
        OR o.name LIKE '%OTDEL%'
        OR o.name LIKE 'scm%')
 ORDER BY o.name

SELECT COUNT(*) AS document_rows,
       SUM(CASE WHEN ISNULL(RTRIM(CONTR_POR),'')<>'' THEN 1 ELSE 0 END) AS contract_rows,
       SUM(CASE WHEN ISNULL(RTRIM(OSNOVANIE),'')<>'' THEN 1 ELSE 0 END) AS basis_rows,
       SUM(CASE WHEN CONTRLDATE IS NOT NULL THEN 1 ELSE 0 END) AS control_date_rows,
       SUM(CASE WHEN ISNULL(RTRIM(L_CP1_PLAT),'')<>'' THEN 1 ELSE 0 END) AS source_1_rows,
       SUM(CASE WHEN ISNULL(RTRIM(L_CP2_PLAT),'')<>'' THEN 1 ELSE 0 END) AS source_2_rows
  FROM dbo.SCL_NAKL
