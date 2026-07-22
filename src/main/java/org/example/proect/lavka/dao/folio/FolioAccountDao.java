package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.dto.folio.FolioAccountItemResponse;
import org.example.proect.lavka.dto.folio.FolioAccountResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
                    SELECT COD_ARTIC, ID_SCLAD, KON_KOLCH, REZ_KOLCH
                    FROM dbo.SCL_ARTC
                    WHERE COD_ARTIC = ?
                      AND ID_SCLAD = ?
                    """, (rs, i) -> new StockRow(
                    rs.getString("COD_ARTIC").stripTrailing(),
                    rs.getInt("ID_SCLAD"),
                    rs.getBigDecimal("KON_KOLCH"),
                    rs.getBigDecimal("REZ_KOLCH")
            ), sku, warehouseId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<StockRow> lockStock(String sku, int warehouseId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT COD_ARTIC, ID_SCLAD, KON_KOLCH, REZ_KOLCH
                    FROM dbo.SCL_ARTC WITH (UPDLOCK, HOLDLOCK)
                    WHERE COD_ARTIC = ?
                      AND ID_SCLAD = ?
                    """, (rs, i) -> new StockRow(
                    rs.getString("COD_ARTIC").stripTrailing(),
                    rs.getInt("ID_SCLAD"),
                    rs.getBigDecimal("KON_KOLCH"),
                    rs.getBigDecimal("REZ_KOLCH")
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

    public void insertHeader(long documentId,
                             BigDecimal documentNumber,
                             LocalDateTime documentDate,
                             BigDecimal totalAmount,
                             String comment,
                             String typeDoc) {
        jdbc.update("""
                INSERT INTO dbo.SCL_NAKL
                    (UNICUM_NUM, N_PLAT_POR, TYPE_DOC, DATE_P_POR, SUM_POR, DOPN_SCHET, STND_UCHET)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                """,
                documentId,
                documentNumber,
                typeDoc,
                Timestamp.valueOf(documentDate),
                totalAmount,
                comment
        );
    }

    public void insertLine(long recno,
                           long documentId,
                           int lineNumber,
                           String sku,
                           int warehouseId,
                           LocalDateTime documentDate,
                           String operationType,
                           BigDecimal quantity,
                           BigDecimal price) {
        BigDecimal amount = price.multiply(quantity);
        jdbc.update("""
                INSERT INTO dbo.SCL_MOVE
                    (RECNO, UNICUM_NUM, NUM_PREDMT, NAME_PREDM, ID_SCLAD,
                     DATE_PREDM, TYPDOCM_PR, VID_DOC, KOLTREB_PR, KOLC_PREDM,
                     CENA_PREDM, SUM_PREDM, STND_UCHET)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                recno,
                documentId,
                lineNumber,
                sku,
                warehouseId,
                Timestamp.valueOf(documentDate),
                operationType,
                operationType,
                quantity,
                quantity,
                price,
                amount
        );
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
                    SELECT UNICUM_NUM, N_PLAT_POR, DATE_P_POR, SUM_POR, STND_UCHET
                    FROM dbo.SCL_NAKL
                    WHERE UNICUM_NUM = ?
                    """, (rs, i) -> new HeaderRow(
                    rs.getLong("UNICUM_NUM"),
                    formatFolioFloat(rs.getDouble("N_PLAT_POR")),
                    rs.getTimestamp("DATE_P_POR").toLocalDateTime(),
                    rs.getBigDecimal("SUM_POR"),
                    rs.getInt("STND_UCHET") != 0
            ), documentId);

            if (header == null) {
                return Optional.empty();
            }

            List<FolioAccountItemResponse> items = jdbc.query("""
                    SELECT RECNO, NUM_PREDMT, NAME_PREDM, ID_SCLAD,
                           KOLC_PREDM, CENA_PREDM, SUM_PREDM
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
                    rs.getBigDecimal("SUM_PREDM")
            ), documentId);

            Integer warehouseId = items.isEmpty() ? null : items.get(0).warehouseId();
            return Optional.of(new FolioAccountResponse(
                    header.documentId(),
                    header.documentNumber(),
                    header.documentDate(),
                    operationType,
                    warehouseId,
                    header.totalAmount(),
                    header.active(),
                    items
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
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

    public record StockRow(String sku, int warehouseId, BigDecimal actualQuantity, BigDecimal freeQuantity) {
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
                             boolean active) {
    }

    private static String formatFolioFloat(double value) {
        if (Math.rint(value) == value) {
            return BigDecimal.valueOf(value).toBigInteger().toString();
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
