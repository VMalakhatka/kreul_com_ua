package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.dto.folio.FolioAccountItemResponse;
import org.example.proect.lavka.dto.folio.FolioAccountResponse;
import org.example.proect.lavka.dto.folio.FolioAccountSummaryResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class FolioAccountDao {

    private final JdbcTemplate jdbc;

    public FolioAccountDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean warehouseExists(int warehouseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dbo.SCLAD_R WHERE ID_SCLAD = ?",
                Integer.class,
                warehouseId
        );
        return count != null && count > 0;
    }

    public Optional<StockRow> findStock(String sku, int warehouseId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT COD_ARTIC, ID_SCLAD, KON_KOLCH, REZ_KOLCH,
                           CENA_ARTIC, CENA_VALT, COD_VALT,
                           BALL1, BALL2, BALL3, BALL4, BALL5
                    FROM dbo.SCL_ARTC
                    WHERE COD_ARTIC = ?
                      AND ID_SCLAD = ?
                    """, (rs, i) -> new StockRow(
                    rs.getString("COD_ARTIC").stripTrailing(),
                    rs.getInt("ID_SCLAD"),
                    rs.getBigDecimal("KON_KOLCH"),
                    rs.getBigDecimal("REZ_KOLCH"),
                    rs.getBigDecimal("CENA_ARTIC"),
                    rs.getBigDecimal("CENA_VALT"),
                    trimToNull(rs.getString("COD_VALT")),
                    rs.getBigDecimal("BALL1"),
                    rs.getBigDecimal("BALL2"),
                    rs.getBigDecimal("BALL3"),
                    rs.getBigDecimal("BALL4"),
                    rs.getBigDecimal("BALL5")
            ), sku, warehouseId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<StockRow> lockStock(String sku, int warehouseId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT COD_ARTIC, ID_SCLAD, KON_KOLCH, REZ_KOLCH,
                           CENA_ARTIC, CENA_VALT, COD_VALT,
                           BALL1, BALL2, BALL3, BALL4, BALL5
                    FROM dbo.SCL_ARTC WITH (UPDLOCK, HOLDLOCK)
                    WHERE COD_ARTIC = ?
                      AND ID_SCLAD = ?
                    """, (rs, i) -> new StockRow(
                    rs.getString("COD_ARTIC").stripTrailing(),
                    rs.getInt("ID_SCLAD"),
                    rs.getBigDecimal("KON_KOLCH"),
                    rs.getBigDecimal("REZ_KOLCH"),
                    rs.getBigDecimal("CENA_ARTIC"),
                    rs.getBigDecimal("CENA_VALT"),
                    trimToNull(rs.getString("COD_VALT")),
                    rs.getBigDecimal("BALL1"),
                    rs.getBigDecimal("BALL2"),
                    rs.getBigDecimal("BALL3"),
                    rs.getBigDecimal("BALL4"),
                    rs.getBigDecimal("BALL5")
            ), sku, warehouseId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Long> findDocumentIdByExternalRequestId(String externalRequestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT UNICUM_NUM
                    FROM dbo.LAVKA_FOLIO_ACCOUNT_REQUESTS WITH (UPDLOCK, HOLDLOCK)
                    WHERE EXTERNAL_REQUEST_ID = ?
                    """, Long.class, externalRequestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void rememberExternalRequest(String externalRequestId, long documentId) {
        jdbc.update("""
                INSERT INTO dbo.LAVKA_FOLIO_ACCOUNT_REQUESTS
                    (EXTERNAL_REQUEST_ID, UNICUM_NUM, CREATED_AT)
                VALUES (?, ?, GETDATE())
                """, externalRequestId, documentId);
    }

    public long nextDocumentId() {
        Long next = jdbc.queryForObject("""
                SELECT ISNULL(MAX(UNICUM_NUM), 0) + 1
                FROM dbo.SCL_NAKL WITH (TABLOCKX, HOLDLOCK)
                """, Long.class);
        if (next == null) {
            throw new IllegalStateException("Cannot allocate SCL_NAKL.UNICUM_NUM");
        }
        return next;
    }

    public long peekNextDocumentId() {
        Long next = jdbc.queryForObject("""
                SELECT ISNULL(MAX(UNICUM_NUM), 0) + 1
                FROM dbo.SCL_NAKL
                """, Long.class);
        if (next == null) {
            throw new IllegalStateException("Cannot preview SCL_NAKL.UNICUM_NUM");
        }
        return next;
    }

    public BigDecimal nextVisibleDocumentNumber(String typeDoc) {
        BigDecimal next = jdbc.queryForObject("""
                SELECT ISNULL(MAX(N_PLAT_POR), 0) + 1
                FROM dbo.SCL_NAKL WITH (TABLOCKX, HOLDLOCK)
                WHERE TYPE_DOC = ?
                """, BigDecimal.class, typeDoc);
        if (next == null) {
            throw new IllegalStateException("Cannot allocate SCL_NAKL.N_PLAT_POR");
        }
        return next;
    }

    public BigDecimal peekNextVisibleDocumentNumber(String typeDoc) {
        BigDecimal next = jdbc.queryForObject("""
                SELECT ISNULL(MAX(N_PLAT_POR), 0) + 1
                FROM dbo.SCL_NAKL
                WHERE TYPE_DOC = ?
                """, BigDecimal.class, typeDoc);
        if (next == null) {
            throw new IllegalStateException("Cannot preview SCL_NAKL.N_PLAT_POR");
        }
        return next;
    }

    public long nextMovementId() {
        Long next = jdbc.queryForObject("""
                SELECT ISNULL(MAX(RECNO), 0) + 1
                FROM dbo.SCL_MOVE WITH (TABLOCKX, HOLDLOCK)
                """, Long.class);
        if (next == null) {
            throw new IllegalStateException("Cannot allocate SCL_MOVE.RECNO");
        }
        return next;
    }

    public int nextLineNumber(long documentId) {
        Integer next = jdbc.queryForObject("""
                SELECT ISNULL(MAX(NUM_PREDMT), 0) + 1
                FROM dbo.SCL_MOVE WITH (UPDLOCK, HOLDLOCK)
                WHERE UNICUM_NUM = ?
                """, Integer.class, documentId);
        return next == null ? 1 : next;
    }

    public void insertHeader(HeaderWrite header) {
        jdbc.update("""
                INSERT INTO dbo.SCL_NAKL
                    (UNICUM_NUM, N_PLAT_POR, TYPE_DOC, DATE_P_POR, SUM_POR, DOPN_SCHET, STND_UCHET,
                     NALOG_POR, PRCNT_POR, L_CP1_PLAT, L_CP2_PLAT, OPLATA_SCH, CONTR_POR,
                     NOT_NAL, VALUTROUBL, COD_VALUT, SUM_VALUT, PRCN2_POR, VOZVRAT_PR, CHAST_OPLT,
                     ORGANIZNKL, MY_ORGANIZ, CONTRLDATE, FAMILY, SUM_ROZN, OTMETKA, VID_DOC,
                     BRIEFORG, ID_SCLAD, CREATEDATE, WHO_CORR, IS_NALPROD, NDS_TORGN)
                VALUES (?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?)
                """,
                header.documentId(),
                header.documentNumber(),
                header.typeDoc(),
                Timestamp.valueOf(header.documentDate()),
                header.totalAmount(),
                header.comment(),
                bit(header.accountingEnabled()),
                header.taxName(),
                header.taxPercent(),
                header.sourceInfo(),
                header.additionalInfo(),
                header.paymentFlag(),
                header.priceContractType(),
                bit(header.notCash()),
                bit(header.valutaRouble()),
                header.currencyCode(),
                header.currencyAmount(),
                header.secondTaxPercent(),
                bit(header.returnFlag()),
                header.partialPaymentFlag(),
                header.payerName(),
                header.receiverName(),
                header.controlDate() == null ? null : Timestamp.valueOf(header.controlDate().atStartOfDay()),
                header.folioUser(),
                header.retailAmount(),
                header.markFlag(),
                header.vidDoc(),
                header.payerShortName(),
                header.warehouseId(),
                Timestamp.valueOf(header.createdDate()),
                header.whoCorr(),
                header.cashProductType(),
                header.tradeVatFlag()
        );
    }

    public void insertAddn(AddnWrite addn) {
        jdbc.update("""
                INSERT INTO dbo.SCL_ADDN
                    (UNICUM_NUM, L_TOWN_POR, DIRCT_POR, FINDIR_POR, L_TEL1_PLA,
                     L_CP1_PLAT, L_CP2_PLAT, G_POL_POR, D_PR_DOC, POLSC_DATE, NLG_REG)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                addn.documentId(),
                addn.payerCity(),
                addn.directorName(),
                addn.accountantName(),
                addn.payerPhone(),
                addn.sourceInfo(),
                addn.additionalInfo(),
                addn.deliveryInfo(),
                Timestamp.valueOf(LocalDateTime.of(1899, 12, 30, 0, 0)),
                Timestamp.valueOf(addn.documentDate().toLocalDate().atStartOfDay()),
                addn.taxRegistrationFlag()
        );
    }

    public long insertLine(LineWrite line) {
        BigDecimal amount = line.price().multiply(line.quantity());
        return jdbc.execute((ConnectionCallback<Long>) con -> {
            try (var ps = con.prepareStatement("""
                INSERT INTO dbo.SCL_MOVE
                    (UNICUM_NUM, NUM_PREDMT, NAME_PREDM, ID_SCLAD,
                     ORG_PREDM, DATE_PREDM, NUMDOCM_PR, NUMDCM_DOP, TYPDOCM_PR,
                     STND_UCHET, NOT_NAL, CONTRACT_N, VALUT_CENA, COD_VALUT, SUM_VALUT,
                     NACENKA, VALUTROUBL, OPLATA_SCH, NALOGMONEY, NALOGVALUT, VOZVRAT_PR,
                     SUM_UCHET, SUM_UCVAL, KOLC_OPL, SUM_OPL, SUMVAL_OPL, SUM_ROZN,
                     OTMETKA, VID_DOC, KOLTREB_PR, KOLC_PREDM,
                     CENA_PREDM, SUM_PREDM, BALL1, BALL2, BALL3, BALL4, BALL5)
                VALUES (?, ?, ?, ?,
                        ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        0, ?, ?, 0, 0, ?,
                        0, 0, 0, 0, 0, ?,
                        ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, line.documentId());
                ps.setInt(2, line.lineNumber());
                ps.setString(3, line.sku());
                ps.setInt(4, line.warehouseId());
                ps.setString(5, line.organizationShortName());
                ps.setTimestamp(6, Timestamp.valueOf(line.documentDate()));
                ps.setBigDecimal(7, line.documentNumber());
                ps.setString(8, line.documentNumberSuffix());
                ps.setString(9, line.typeDoc());
                ps.setInt(10, bit(line.accountingEnabled()));
                ps.setInt(11, bit(line.notCash()));
                ps.setString(12, line.priceContractType());
                ps.setBigDecimal(13, line.currencyPrice());
                ps.setString(14, line.currencyCode());
                ps.setBigDecimal(15, line.currencyAmount());
                ps.setInt(16, bit(line.valutaRouble()));
                ps.setInt(17, line.paymentFlag());
                ps.setInt(18, bit(line.returnFlag()));
                ps.setBigDecimal(19, line.retailAmount());
                ps.setInt(20, line.markFlag());
                ps.setString(21, line.movementVidDoc());
                ps.setBigDecimal(22, line.quantity());
                ps.setBigDecimal(23, line.quantity());
                ps.setBigDecimal(24, line.price());
                ps.setBigDecimal(25, amount);
                ps.setBigDecimal(26, multiply(line.productBall1(), line.quantity()));
                ps.setBigDecimal(27, multiply(line.productBall2(), line.quantity()));
                ps.setBigDecimal(28, multiply(line.productBall3(), line.quantity()));
                ps.setBigDecimal(29, multiply(line.productBall4(), line.quantity()));
                ps.setBigDecimal(30, multiply(line.productBall5(), line.quantity()));
                ps.executeUpdate();

                try (var keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                }
            }

            try (var st = con.createStatement();
                 var rs = st.executeQuery("SELECT CAST(@@IDENTITY AS numeric(18,0))")) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }

            throw new IllegalStateException("Cannot read generated SCL_MOVE.RECNO");
        });
    }

    public int reserveIfAvailable(String sku, int warehouseId, BigDecimal quantity) {
        return jdbc.update("""
                UPDATE dbo.SCL_ARTC
                SET REZ_KOLCH = REZ_KOLCH - ?
                WHERE COD_ARTIC = ?
                  AND ID_SCLAD = ?
                  AND REZ_KOLCH >= ?
                """, quantity, sku, warehouseId, quantity);
    }

    public void release(String sku, int warehouseId, BigDecimal quantity) {
        jdbc.update("""
                UPDATE dbo.SCL_ARTC
                SET REZ_KOLCH = REZ_KOLCH + ?
                WHERE COD_ARTIC = ?
                  AND ID_SCLAD = ?
                """, quantity, sku, warehouseId);
    }

    public Optional<MoveRow> lockLine(long documentId, long recno) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT RECNO, UNICUM_NUM, NUM_PREDMT, NAME_PREDM, ID_SCLAD,
                           DATE_PREDM, KOLC_PREDM, CENA_PREDM, SUM_PREDM, STND_UCHET
                    FROM dbo.SCL_MOVE WITH (UPDLOCK, HOLDLOCK)
                    WHERE UNICUM_NUM = ?
                      AND RECNO = ?
                    """, (rs, i) -> new MoveRow(
                    rs.getLong("RECNO"),
                    rs.getLong("UNICUM_NUM"),
                    rs.getInt("NUM_PREDMT"),
                    rs.getString("NAME_PREDM").stripTrailing(),
                    rs.getInt("ID_SCLAD"),
                    rs.getTimestamp("DATE_PREDM").toLocalDateTime(),
                    rs.getBigDecimal("KOLC_PREDM"),
                    rs.getBigDecimal("CENA_PREDM"),
                    rs.getBigDecimal("SUM_PREDM"),
                    rs.getInt("STND_UCHET") != 0
            ), documentId, recno));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<MoveRow> lockLines(long documentId) {
        return jdbc.query("""
                SELECT RECNO, UNICUM_NUM, NUM_PREDMT, NAME_PREDM, ID_SCLAD,
                       DATE_PREDM, KOLC_PREDM, CENA_PREDM, SUM_PREDM, STND_UCHET
                FROM dbo.SCL_MOVE WITH (UPDLOCK, HOLDLOCK)
                WHERE UNICUM_NUM = ?
                  AND ISNULL(STND_UCHET, 1) <> 0
                ORDER BY NUM_PREDMT, RECNO
                """, (rs, i) -> new MoveRow(
                rs.getLong("RECNO"),
                rs.getLong("UNICUM_NUM"),
                rs.getInt("NUM_PREDMT"),
                rs.getString("NAME_PREDM").stripTrailing(),
                rs.getInt("ID_SCLAD"),
                rs.getTimestamp("DATE_PREDM").toLocalDateTime(),
                rs.getBigDecimal("KOLC_PREDM"),
                rs.getBigDecimal("CENA_PREDM"),
                rs.getBigDecimal("SUM_PREDM"),
                rs.getInt("STND_UCHET") != 0
        ), documentId);
    }

    public void updateLineQuantity(long documentId, long recno, BigDecimal quantity) {
        jdbc.update("""
                UPDATE dbo.SCL_MOVE
                SET KOLTREB_PR = ?,
                    KOLC_PREDM = ?,
                    SUM_PREDM = CENA_PREDM * ?
                WHERE UNICUM_NUM = ?
                  AND RECNO = ?
                """, quantity, quantity, quantity, documentId, recno);
    }

    public void deleteLine(long documentId, long recno) {
        jdbc.update("""
                DELETE FROM dbo.SCL_MOVE
                WHERE UNICUM_NUM = ?
                  AND RECNO = ?
                """, documentId, recno);
    }

    public void cancelHeader(long documentId) {
        jdbc.update("""
                UPDATE dbo.SCL_NAKL
                SET STND_UCHET = 0
                WHERE UNICUM_NUM = ?
                """, documentId);
    }

    public void cancelLines(long documentId) {
        jdbc.update("""
                UPDATE dbo.SCL_MOVE
                SET STND_UCHET = 0
                WHERE UNICUM_NUM = ?
                """, documentId);
    }

    public Optional<FolioAccountResponse> findAccount(long documentId, String operationType) {
        try {
            HeaderRow header = jdbc.queryForObject("""
                    SELECT n.UNICUM_NUM, n.N_PLAT_POR, n.DATE_P_POR, n.SUM_POR, n.STND_UCHET,
                           n.DOPN_SCHET, n.CONTRLDATE, n.VID_DOC, n.ORGANIZNKL, n.MY_ORGANIZ,
                           n.BRIEFORG, n.FAMILY, n.L_CP1_PLAT, n.L_CP2_PLAT, n.CONTR_POR,
                           n.NOT_NAL, n.VALUTROUBL, n.VOZVRAT_PR, n.SUM_VALUT, n.SUM_ROZN,
                           n.CREATEDATE, n.CORRDATE, n.WHO_CORR, n.ID_SCLAD,
                           a.L_TOWN_POR, a.DIRCT_POR, a.FINDIR_POR, a.L_TEL1_PLA, a.G_POL_POR
                    FROM dbo.SCL_NAKL n
                    LEFT JOIN dbo.SCL_ADDN a ON a.UNICUM_NUM = n.UNICUM_NUM
                    WHERE n.UNICUM_NUM = ?
                    """, (rs, i) -> new HeaderRow(
                    rs.getLong("UNICUM_NUM"),
                    formatFolioFloat(rs.getDouble("N_PLAT_POR")),
                    rs.getTimestamp("DATE_P_POR").toLocalDateTime(),
                    rs.getBigDecimal("SUM_POR"),
                    trimToNull(rs.getString("DOPN_SCHET")),
                    toLocalDate(rs.getTimestamp("CONTRLDATE")),
                    trimToNull(rs.getString("VID_DOC")),
                    trimToNull(rs.getString("ORGANIZNKL")),
                    trimToNull(rs.getString("MY_ORGANIZ")),
                    trimToNull(rs.getString("BRIEFORG")),
                    trimToNull(rs.getString("FAMILY")),
                    trimToNull(rs.getString("L_CP1_PLAT")),
                    trimToNull(rs.getString("L_CP2_PLAT")),
                    trimToNull(rs.getString("CONTR_POR")),
                    bitToBoolean(rs.getObject("NOT_NAL")),
                    bitToBoolean(rs.getObject("STND_UCHET")),
                    bitToBoolean(rs.getObject("VOZVRAT_PR")),
                    bitToBoolean(rs.getObject("VALUTROUBL")),
                    rs.getBigDecimal("SUM_VALUT"),
                    rs.getBigDecimal("SUM_ROZN"),
                    trimToNull(rs.getString("L_TOWN_POR")),
                    trimToNull(rs.getString("DIRCT_POR")),
                    trimToNull(rs.getString("FINDIR_POR")),
                    trimToNull(rs.getString("L_TEL1_PLA")),
                    trimToNull(rs.getString("G_POL_POR")),
                    toLocalDateTime(rs.getTimestamp("CREATEDATE")),
                    toLocalDateTime(rs.getTimestamp("CORRDATE")),
                    trimToNull(rs.getString("WHO_CORR")),
                    rs.getObject("ID_SCLAD") == null ? null : rs.getInt("ID_SCLAD"),
                    rs.getInt("STND_UCHET") != 0
            ), documentId);

            if (header == null) {
                return Optional.empty();
            }

            List<FolioAccountItemResponse> items = jdbc.query("""
                    SELECT RECNO, NUM_PREDMT, NAME_PREDM, ID_SCLAD,
                           KOLC_PREDM, CENA_PREDM, SUM_PREDM,
                           ORG_PREDM, NUMDOCM_PR, NUMDCM_DOP, TYPDOCM_PR, NOT_NAL,
                           CONTRACT_N, VALUT_CENA, COD_VALUT, SUM_VALUT, VALUTROUBL,
                           SUM_ROZN, VID_DOC, BALL1, BALL2, BALL3, BALL4, BALL5
                    FROM dbo.SCL_MOVE
                    WHERE UNICUM_NUM = ?
                    ORDER BY NUM_PREDMT, RECNO
                    """, (rs, i) -> new FolioAccountItemResponse(
                    rs.getLong("RECNO"),
                    rs.getInt("NUM_PREDMT"),
                    rs.getString("NAME_PREDM").stripTrailing(),
                    rs.getInt("ID_SCLAD"),
                    rs.getBigDecimal("KOLC_PREDM"),
                    rs.getBigDecimal("CENA_PREDM"),
                    rs.getBigDecimal("SUM_PREDM"),
                    trimToNull(rs.getString("ORG_PREDM")),
                    formatNullableFolioFloat(rs.getObject("NUMDOCM_PR") == null ? null : rs.getDouble("NUMDOCM_PR")),
                    trimToNull(rs.getString("NUMDCM_DOP")),
                    trimToNull(rs.getString("TYPDOCM_PR")),
                    bitToBoolean(rs.getObject("NOT_NAL")),
                    trimToNull(rs.getString("CONTRACT_N")),
                    rs.getBigDecimal("VALUT_CENA"),
                    trimToNull(rs.getString("COD_VALUT")),
                    rs.getBigDecimal("SUM_VALUT"),
                    bitToBoolean(rs.getObject("VALUTROUBL")),
                    rs.getBigDecimal("SUM_ROZN"),
                    trimToNull(rs.getString("VID_DOC")),
                    rs.getBigDecimal("BALL1"),
                    rs.getBigDecimal("BALL2"),
                    rs.getBigDecimal("BALL3"),
                    rs.getBigDecimal("BALL4"),
                    rs.getBigDecimal("BALL5")
            ), documentId);

            Integer warehouseId = header.warehouseId() == null
                    ? (items.isEmpty() ? null : items.get(0).warehouseId())
                    : header.warehouseId();
            return Optional.of(new FolioAccountResponse(
                    header.documentId(),
                    header.documentNumber(),
                    header.documentDate(),
                    operationType,
                    warehouseId,
                    header.totalAmount(),
                    header.comment(),
                    header.controlDate(),
                    header.folioOperationKind(),
                    header.payerName(),
                    header.receiverName(),
                    header.payerShortName(),
                    header.folioUser(),
                    header.sourceInfo(),
                    header.additionalInfo(),
                    header.priceContractType(),
                    header.notCash(),
                    header.accountingEnabled(),
                    header.returnFlag(),
                    header.currencyAmount(),
                    header.retailAmount(),
                    header.payerCity(),
                    header.directorName(),
                    header.accountantName(),
                    header.payerPhone(),
                    header.deliveryInfo(),
                    header.createdDate(),
                    header.correctionDate(),
                    header.correctedBy(),
                    header.active(),
                    items
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<FolioAccountSummaryResponse> findAccounts(LocalDate dateFrom,
                                                          LocalDate dateTo,
                                                          String payerName,
                                                          List<Integer> warehouseIds,
                                                          String typeDoc,
                                                          String operationType) {
        StringBuilder sql = new StringBuilder("""
                SELECT n.UNICUM_NUM, n.N_PLAT_POR, n.DATE_P_POR, n.SUM_POR, n.STND_UCHET,
                       n.ORGANIZNKL, n.MY_ORGANIZ, n.BRIEFORG, n.L_CP1_PLAT, n.L_CP2_PLAT,
                       n.VID_DOC, n.CONTRLDATE, n.ID_SCLAD, n.CREATEDATE
                FROM dbo.SCL_NAKL n
                WHERE n.TYPE_DOC = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(typeDoc);

        if (dateFrom != null) {
            sql.append(" AND n.DATE_P_POR >= ?");
            params.add(Timestamp.valueOf(dateFrom.atStartOfDay()));
        }
        if (dateTo != null) {
            sql.append(" AND n.DATE_P_POR < ?");
            params.add(Timestamp.valueOf(dateTo.plusDays(1).atStartOfDay()));
        }
        String normalizedPayerName = trimToNull(payerName);
        if (normalizedPayerName != null) {
            sql.append(" AND (n.ORGANIZNKL LIKE ? OR n.BRIEFORG LIKE ?)");
            String pattern = "%" + normalizedPayerName + "%";
            params.add(pattern);
            params.add(pattern);
        }
        if (warehouseIds != null && !warehouseIds.isEmpty()) {
            sql.append(" AND (n.ID_SCLAD IN (");
            for (int i = 0; i < warehouseIds.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                params.add(warehouseIds.get(i));
            }
            sql.append(") OR EXISTS (SELECT 1 FROM dbo.SCL_MOVE m WHERE m.UNICUM_NUM = n.UNICUM_NUM AND m.ID_SCLAD IN (");
            for (int i = 0; i < warehouseIds.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                params.add(warehouseIds.get(i));
            }
            sql.append(")))");
        }
        sql.append(" ORDER BY n.DATE_P_POR DESC, n.UNICUM_NUM DESC");

        return jdbc.query(sql.toString(), (rs, i) -> new FolioAccountSummaryResponse(
                rs.getLong("UNICUM_NUM"),
                formatFolioFloat(rs.getDouble("N_PLAT_POR")),
                rs.getTimestamp("DATE_P_POR").toLocalDateTime(),
                operationType,
                rs.getObject("ID_SCLAD") == null ? null : rs.getInt("ID_SCLAD"),
                rs.getBigDecimal("SUM_POR"),
                trimToNull(rs.getString("ORGANIZNKL")),
                trimToNull(rs.getString("MY_ORGANIZ")),
                trimToNull(rs.getString("BRIEFORG")),
                trimToNull(rs.getString("L_CP1_PLAT")),
                trimToNull(rs.getString("L_CP2_PLAT")),
                trimToNull(rs.getString("VID_DOC")),
                toLocalDate(rs.getTimestamp("CONTRLDATE")),
                rs.getInt("STND_UCHET") != 0,
                toLocalDateTime(rs.getTimestamp("CREATEDATE"))
        ), params.toArray());
    }

    public void refreshHeaderTotal(long documentId) {
        jdbc.update("""
                UPDATE dbo.SCL_NAKL
                SET SUM_POR = (
                    SELECT ISNULL(SUM(SUM_PREDM), 0)
                    FROM dbo.SCL_MOVE
                    WHERE UNICUM_NUM = ?
                      AND ISNULL(STND_UCHET, 1) <> 0
                )
                WHERE UNICUM_NUM = ?
                """, documentId, documentId);
    }

    public record StockRow(String sku,
                           int warehouseId,
                           BigDecimal actualQuantity,
                           BigDecimal freeQuantity,
                           BigDecimal retailPrice,
                           BigDecimal currencyPrice,
                           String currencyCode,
                           BigDecimal ball1,
                           BigDecimal ball2,
                           BigDecimal ball3,
                           BigDecimal ball4,
                           BigDecimal ball5) {
    }

    public record MoveRow(long recno,
                          long documentId,
                          int lineNumber,
                          String sku,
                          int warehouseId,
                          LocalDateTime documentDate,
                          BigDecimal quantity,
                          BigDecimal price,
                          BigDecimal amount,
                          boolean active) {
    }

    private record HeaderRow(long documentId,
                             String documentNumber,
                             LocalDateTime documentDate,
                             BigDecimal totalAmount,
                             String comment,
                             LocalDate controlDate,
                             String folioOperationKind,
                             String payerName,
                             String receiverName,
                             String payerShortName,
                             String folioUser,
                             String sourceInfo,
                             String additionalInfo,
                             String priceContractType,
                             Boolean notCash,
                             Boolean accountingEnabled,
                             Boolean returnFlag,
                             Boolean valutaRouble,
                             BigDecimal currencyAmount,
                             BigDecimal retailAmount,
                             String payerCity,
                             String directorName,
                             String accountantName,
                             String payerPhone,
                             String deliveryInfo,
                             LocalDateTime createdDate,
                             LocalDateTime correctionDate,
                             String correctedBy,
                             Integer warehouseId,
                             boolean active) {
    }

    public record HeaderWrite(long documentId,
                              BigDecimal documentNumber,
                              LocalDateTime documentDate,
                              BigDecimal totalAmount,
                              String comment,
                              String typeDoc,
                              boolean accountingEnabled,
                              String taxName,
                              BigDecimal taxPercent,
                              String sourceInfo,
                              String additionalInfo,
                              int paymentFlag,
                              String priceContractType,
                              boolean notCash,
                              boolean valutaRouble,
                              Integer currencyCode,
                              BigDecimal currencyAmount,
                              BigDecimal secondTaxPercent,
                              boolean returnFlag,
                              int partialPaymentFlag,
                              String payerName,
                              String receiverName,
                              LocalDate controlDate,
                              String folioUser,
                              BigDecimal retailAmount,
                              int markFlag,
                              String vidDoc,
                              String payerShortName,
                              int warehouseId,
                              LocalDateTime createdDate,
                              String whoCorr,
                              Integer cashProductType,
                              int tradeVatFlag) {
    }

    public record AddnWrite(long documentId,
                            LocalDateTime documentDate,
                            String payerCity,
                            String directorName,
                            String accountantName,
                            String payerPhone,
                            String sourceInfo,
                            String additionalInfo,
                            String deliveryInfo,
                            int taxRegistrationFlag) {
    }

    public record LineWrite(long documentId,
                            int lineNumber,
                            String sku,
                            int warehouseId,
                            String organizationShortName,
                            LocalDateTime documentDate,
                            BigDecimal documentNumber,
                            String documentNumberSuffix,
                            String typeDoc,
                            boolean accountingEnabled,
                            boolean notCash,
                            String priceContractType,
                            BigDecimal currencyPrice,
                            String currencyCode,
                            BigDecimal currencyAmount,
                            boolean valutaRouble,
                            int paymentFlag,
                            boolean returnFlag,
                            BigDecimal retailAmount,
                            int markFlag,
                            String movementVidDoc,
                            BigDecimal quantity,
                            BigDecimal price,
                            BigDecimal productBall1,
                            BigDecimal productBall2,
                            BigDecimal productBall3,
                            BigDecimal productBall4,
                            BigDecimal productBall5) {
    }

    private static String formatFolioFloat(double value) {
        if (Math.rint(value) == value) {
            return BigDecimal.valueOf(value).toBigInteger().toString();
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String formatNullableFolioFloat(Double value) {
        return value == null ? null : formatFolioFloat(value);
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }

    private static BigDecimal multiply(BigDecimal value, BigDecimal quantity) {
        if (value == null) {
            return null;
        }
        return value.multiply(quantity);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Boolean bitToBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static LocalDate toLocalDate(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toLocalDate();
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
