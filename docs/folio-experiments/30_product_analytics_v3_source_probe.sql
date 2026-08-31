/*
  Read-only source probe for product analytics schema v3.
  SQL Server 8.00 / compatibility level 80.
  Run first on Paint_Rus, then unchanged on Paint_Ua.
*/

SELECT DB_NAME() AS database_name,
       DATABASEPROPERTYEX(DB_NAME(), 'Collation') AS database_collation

SELECT o.name AS table_name, c.colid AS ordinal_position,
       c.name AS column_name, t.name AS data_type,
       c.length AS max_length, c.isnullable AS is_nullable
  FROM dbo.sysobjects o
  JOIN dbo.syscolumns c ON c.id=o.id
  JOIN dbo.systypes t ON t.xusertype=c.xusertype
 WHERE o.xtype='U'
   AND (o.name IN ('SCL_ARTC','TIP_TOVR','EDIN_IZM','SCL_CODE',
                   'SCL_NAKL','SCL_MOVE','scm_artcmap','scm_partner')
        OR o.name LIKE '%DEPART%'
        OR o.name LIKE '%OTDEL%')
 ORDER BY o.name, c.colid

SELECT o.name AS candidate_dictionary
  FROM dbo.sysobjects o
 WHERE o.xtype='U'
   AND (o.name LIKE '%GROUP%'
        OR o.name LIKE '%GRUP%'
        OR o.name LIKE '%EDIN%'
        OR o.name LIKE '%TIP%'
        OR o.name LIKE '%BRAND%'
        OR o.name LIKE '%PROIZV%'
        OR o.name LIKE '%DEPART%'
        OR o.name LIKE '%OTDEL%')
 ORDER BY o.name

SELECT ID_SCLAD AS warehouse_id,
       COUNT(*) AS product_count,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TVR),'')<>'' THEN 1 ELSE 0 END) AS group_1_filled,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TV2),'')<>'' THEN 1 ELSE 0 END) AS group_2_filled,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TV3),'')<>'' THEN 1 ELSE 0 END) AS group_3_filled,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TV4),'')<>'' THEN 1 ELSE 0 END) AS group_4_filled,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TV5),'')<>'' THEN 1 ELSE 0 END) AS group_5_filled,
       SUM(CASE WHEN ISNULL(RTRIM(NGROUP_TV6),'')<>'' THEN 1 ELSE 0 END) AS group_6_filled,
       SUM(CASE WHEN DEPARTAM IS NOT NULL THEN 1 ELSE 0 END) AS department_filled,
       SUM(CASE WHEN ISNULL(RTRIM(TIP_TOVR),'')<>'' THEN 1 ELSE 0 END) AS product_type_filled,
       SUM(CASE WHEN ISNULL(RTRIM(EDIN_IZMER),'')<>'' THEN 1 ELSE 0 END) AS unit_filled,
       SUM(CASE WHEN ISNULL(EDN_V_UPAK,0)<>0 THEN 1 ELSE 0 END) AS package_filled,
       SUM(CASE WHEN ISNULL(MIN_PARTIA,0)<>0 THEN 1 ELSE 0 END) AS moq_filled,
       SUM(CASE WHEN ISNULL(MIN_TVRZAP,0)<>0 THEN 1 ELSE 0 END) AS min_stock_filled,
       SUM(CASE WHEN ISNULL(MAX_TVRZAP,0)<>0 THEN 1 ELSE 0 END) AS max_stock_filled
  FROM dbo.SCL_ARTC
 GROUP BY ID_SCLAD
 ORDER BY ID_SCLAD

SELECT TOP 100 ID_SCLAD AS warehouse_id, COD_ARTIC AS sku,
       NGROUP_TVR, NGROUP_TV2, NGROUP_TV3,
       NGROUP_TV4, NGROUP_TV5, NGROUP_TV6,
       DEPARTAM, TIP_TOVR, EDIN_IZMER,
       EDN_V_UPAK, MIN_PARTIA, MIN_TVRZAP, MAX_TVRZAP
  FROM dbo.SCL_ARTC
 WHERE ISNULL(RTRIM(NGROUP_TVR),'')<>''
    OR ISNULL(RTRIM(NGROUP_TV2),'')<>''
    OR ISNULL(RTRIM(TIP_TOVR),'')<>''
    OR ISNULL(RTRIM(EDIN_IZMER),'')<>''
    OR ISNULL(EDN_V_UPAK,0)<>0
    OR ISNULL(MIN_PARTIA,0)<>0
    OR ISNULL(MIN_TVRZAP,0)<>0
    OR ISNULL(MAX_TVRZAP,0)<>0
 ORDER BY ID_SCLAD, COD_ARTIC

SELECT * FROM dbo.TIP_TOVR ORDER BY SIGNIFIC

SELECT * FROM dbo.EDIN_IZM ORDER BY SIGNIFIC

SELECT TOP 100 * FROM dbo.SCL_CODE ORDER BY ID_SCLAD, ARTIC, CODE
