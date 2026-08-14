package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.dto.folio.FolioCustomerBalanceResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class FolioCustomerBalanceSnapshotDao {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public FolioCustomerBalanceSnapshotDao(
            @Qualifier("wpJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("wpNamedJdbc") NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    public long createGeneration(LocalDate asOfDate, String triggerSource, LocalDateTime startedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO folio_balance_snapshot_generation
                        (status, trigger_source, as_of_date, started_at)
                    VALUES ('BUILDING', ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, triggerSource);
            statement.setObject(2, asOfDate);
            statement.setTimestamp(3, Timestamp.valueOf(startedAt));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Cannot read generated balance snapshot generation id");
        }
        return key.longValue();
    }

    public void saveClients(long generationId, List<SnapshotClient> clients) {
        if (clients.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                        INSERT INTO folio_balance_snapshot_client (
                            generation_id, partner_short_name, partner_name, partner_type,
                            city, phone, common_debt, deferred_amount,
                            overdue_deferred_amount, prepayment_amount, payable_now, calculated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                clients,
                200,
                (statement, client) -> {
                    statement.setLong(1, generationId);
                    statement.setString(2, client.partnerShortName());
                    statement.setString(3, client.partnerName());
                    statement.setString(4, client.partnerType());
                    statement.setString(5, client.city());
                    statement.setString(6, client.phone());
                    statement.setBigDecimal(7, client.commonDebt());
                    statement.setBigDecimal(8, client.deferredAmount());
                    statement.setBigDecimal(9, client.overdueDeferredAmount());
                    statement.setBigDecimal(10, client.prepaymentAmount());
                    statement.setBigDecimal(11, client.payableNow());
                    statement.setTimestamp(12, Timestamp.valueOf(client.calculatedAt()));
                });
    }

    @Transactional(transactionManager = "wpTransactionManager")
    public void publishGeneration(long generationId, int totalClients, LocalDateTime completedAt) {
        Long previousGeneration = jdbc.queryForObject("""
                SELECT active_generation_id
                FROM folio_balance_snapshot_state
                WHERE id = 1
                FOR UPDATE
                """, Long.class);

        // A single-client live report may finish while this generation is being built.
        // Apply those newer canonical totals before the active pointer is switched.
        jdbc.update("""
                UPDATE folio_balance_snapshot_client c
                JOIN folio_balance_snapshot_generation g
                  ON g.id = c.generation_id
                JOIN folio_balance_snapshot_live_client l
                 ON l.partner_short_name = c.partner_short_name
                 AND l.as_of_date = g.as_of_date
                 AND l.calculated_at >= g.started_at
                 AND l.calculated_at >= c.calculated_at
                SET c.partner_name = l.partner_name,
                    c.common_debt = l.common_debt,
                    c.deferred_amount = l.deferred_amount,
                    c.overdue_deferred_amount = l.overdue_deferred_amount,
                    c.prepayment_amount = l.prepayment_amount,
                    c.payable_now = l.payable_now,
                    c.calculated_at = l.calculated_at
                WHERE c.generation_id = ?
                """, generationId);

        int activated = jdbc.update("""
                UPDATE folio_balance_snapshot_generation
                SET status = 'ACTIVE', completed_at = ?, total_clients = ?, error_message = NULL
                WHERE id = ? AND status = 'BUILDING'
                """, Timestamp.valueOf(completedAt), totalClients, generationId);
        if (activated != 1) {
            throw new IllegalStateException("Balance snapshot generation is not publishable: " + generationId);
        }

        jdbc.update("""
                UPDATE folio_balance_snapshot_state
                SET active_generation_id = ?, updated_at = ?
                WHERE id = 1
                """, generationId, Timestamp.valueOf(completedAt));

        if (previousGeneration != null && previousGeneration != generationId) {
            jdbc.update("""
                    UPDATE folio_balance_snapshot_generation
                    SET status = 'SUPERSEDED'
                    WHERE id = ? AND status = 'ACTIVE'
                    """, previousGeneration);
        }
    }

    public void failGeneration(long generationId, String message, LocalDateTime completedAt) {
        jdbc.update("""
                UPDATE folio_balance_snapshot_generation
                SET status = 'FAILED', completed_at = ?, error_message = ?
                WHERE id = ? AND status = 'BUILDING'
                """, Timestamp.valueOf(completedAt), truncate(message, 1000), generationId);
    }

    public Optional<ActiveSnapshot> findActiveSnapshot() {
        List<ActiveSnapshot> result = jdbc.query("""
                SELECT g.id, g.status, g.as_of_date, g.started_at, g.completed_at, g.total_clients
                FROM folio_balance_snapshot_state s
                JOIN folio_balance_snapshot_generation g ON g.id = s.active_generation_id
                WHERE s.id = 1
                """, (rs, rowNum) -> new ActiveSnapshot(
                rs.getLong("id"),
                rs.getString("status"),
                rs.getObject("as_of_date", LocalDate.class),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("completed_at").toLocalDateTime(),
                rs.getInt("total_clients")
        ));
        return result.stream().findFirst();
    }

    public Optional<GenerationStatus> findLatestGeneration() {
        List<GenerationStatus> result = jdbc.query("""
                SELECT g.id, g.status, g.trigger_source, g.as_of_date, g.started_at,
                       g.completed_at, g.total_clients, g.error_message,
                       CASE WHEN s.active_generation_id = g.id THEN 1 ELSE 0 END AS is_active
                FROM folio_balance_snapshot_generation g
                LEFT JOIN folio_balance_snapshot_state s ON s.id = 1
                ORDER BY g.id DESC
                LIMIT 1
                """, (rs, rowNum) -> new GenerationStatus(
                rs.getLong("id"),
                rs.getString("status"),
                rs.getString("trigger_source"),
                rs.getObject("as_of_date", LocalDate.class),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("completed_at") == null
                        ? null : rs.getTimestamp("completed_at").toLocalDateTime(),
                rs.getInt("total_clients"),
                rs.getString("error_message"),
                rs.getBoolean("is_active")
        ));
        return result.stream().findFirst();
    }

    @Transactional(transactionManager = "wpTransactionManager", readOnly = true)
    public SnapshotPage findDebtors(long generationId,
                                    BigDecimal minPayable,
                                    String q,
                                    List<String> types,
                                    int limit,
                                    int offset) {
        StringBuilder where = new StringBuilder("""
                WHERE generation_id = :generationId
                  AND payable_now > :minPayable
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("generationId", generationId)
                .addValue("minPayable", minPayable)
                .addValue("limit", limit)
                .addValue("offset", offset);

        if (q != null && !q.isBlank()) {
            where.append(" AND (partner_short_name LIKE :query OR partner_name LIKE :query)");
            params.addValue("query", "%" + q + "%");
        }
        if (types != null && !types.isEmpty()) {
            where.append(" AND partner_type IN (:types)");
            params.addValue("types", types);
        }

        SnapshotSummary summary = namedJdbc.queryForObject("""
                        SELECT COUNT(*) AS matched_clients,
                               COALESCE(SUM(common_debt), 0) AS common_debt_total,
                               COALESCE(SUM(deferred_amount), 0) AS deferred_amount_total,
                               COALESCE(SUM(overdue_deferred_amount), 0) AS overdue_deferred_amount_total,
                               COALESCE(SUM(prepayment_amount), 0) AS prepayment_amount_total,
                               COALESCE(SUM(payable_now), 0) AS payable_now_total
                        FROM folio_balance_snapshot_client
                        """ + where,
                params,
                (rs, rowNum) -> new SnapshotSummary(
                        rs.getLong("matched_clients"),
                        rs.getBigDecimal("common_debt_total"),
                        rs.getBigDecimal("deferred_amount_total"),
                        rs.getBigDecimal("overdue_deferred_amount_total"),
                        rs.getBigDecimal("prepayment_amount_total"),
                        rs.getBigDecimal("payable_now_total")
                ));

        List<SnapshotClient> clients = namedJdbc.query("""
                        SELECT partner_short_name, partner_name, partner_type, city, phone,
                               common_debt, deferred_amount, overdue_deferred_amount,
                               prepayment_amount, payable_now, calculated_at
                        FROM folio_balance_snapshot_client
                        """ + where + """
                        ORDER BY payable_now DESC, partner_short_name ASC
                        LIMIT :limit OFFSET :offset
                        """,
                params,
                (rs, rowNum) -> new SnapshotClient(
                        rs.getString("partner_short_name"),
                        rs.getString("partner_name"),
                        rs.getString("partner_type"),
                        rs.getString("city"),
                        rs.getString("phone"),
                        rs.getBigDecimal("common_debt"),
                        rs.getBigDecimal("deferred_amount"),
                        rs.getBigDecimal("overdue_deferred_amount"),
                        rs.getBigDecimal("prepayment_amount"),
                        rs.getBigDecimal("payable_now"),
                        rs.getTimestamp("calculated_at").toLocalDateTime()
                ));

        return new SnapshotPage(summary, List.copyOf(clients));
    }

    @Transactional(transactionManager = "wpTransactionManager")
    public int updateActiveClient(LocalDate asOfDate,
                                  String partnerShortName,
                                  String partnerName,
                                  FolioCustomerBalanceResponse.Summary summary,
                                  LocalDateTime calculatedAt) {
        jdbc.update("""
                INSERT INTO folio_balance_snapshot_live_client (
                    partner_short_name, as_of_date, partner_name,
                    common_debt, deferred_amount, overdue_deferred_amount,
                    prepayment_amount, payable_now, calculated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    as_of_date = IF(VALUES(calculated_at) >= calculated_at, VALUES(as_of_date), as_of_date),
                    partner_name = IF(VALUES(calculated_at) >= calculated_at, VALUES(partner_name), partner_name),
                    common_debt = IF(VALUES(calculated_at) >= calculated_at, VALUES(common_debt), common_debt),
                    deferred_amount = IF(VALUES(calculated_at) >= calculated_at, VALUES(deferred_amount), deferred_amount),
                    overdue_deferred_amount = IF(VALUES(calculated_at) >= calculated_at, VALUES(overdue_deferred_amount), overdue_deferred_amount),
                    prepayment_amount = IF(VALUES(calculated_at) >= calculated_at, VALUES(prepayment_amount), prepayment_amount),
                    payable_now = IF(VALUES(calculated_at) >= calculated_at, VALUES(payable_now), payable_now),
                    calculated_at = GREATEST(calculated_at, VALUES(calculated_at))
                """,
                partnerShortName,
                asOfDate,
                partnerName,
                summary.commonDebt(),
                summary.deferredAmount(),
                summary.overdueDeferredAmount(),
                summary.prepaymentAmount(),
                summary.payableNow(),
                Timestamp.valueOf(calculatedAt));

        return jdbc.update("""
                UPDATE folio_balance_snapshot_client c
                JOIN folio_balance_snapshot_generation g
                  ON g.id = c.generation_id
                 AND g.as_of_date = ?
                 AND g.status IN ('ACTIVE', 'BUILDING')
                SET c.partner_name = ?,
                    c.common_debt = ?,
                    c.deferred_amount = ?,
                    c.overdue_deferred_amount = ?,
                    c.prepayment_amount = ?,
                    c.payable_now = ?,
                    c.calculated_at = ?
                WHERE c.partner_short_name = ?
                  AND c.calculated_at <= ?
                """,
                asOfDate,
                partnerName,
                summary.commonDebt(),
                summary.deferredAmount(),
                summary.overdueDeferredAmount(),
                summary.prepaymentAmount(),
                summary.payableNow(),
                Timestamp.valueOf(calculatedAt),
                partnerShortName,
                Timestamp.valueOf(calculatedAt));
    }

    public boolean tryAcquireLease(String ownerId, int leaseSeconds) {
        return jdbc.update("""
                UPDATE folio_balance_snapshot_lock
                SET owner_id = ?, locked_until = TIMESTAMPADD(SECOND, ?, NOW(3)), updated_at = NOW(3)
                WHERE id = 1
                  AND (locked_until IS NULL OR locked_until < NOW(3) OR owner_id = ?)
                """, ownerId, leaseSeconds, ownerId) == 1;
    }

    public boolean renewLease(String ownerId, int leaseSeconds) {
        return jdbc.update("""
                UPDATE folio_balance_snapshot_lock
                SET locked_until = TIMESTAMPADD(SECOND, ?, NOW(3)), updated_at = NOW(3)
                WHERE id = 1
                  AND owner_id = ?
                  AND locked_until >= NOW(3)
                """, leaseSeconds, ownerId) == 1;
    }

    public void releaseLease(String ownerId) {
        jdbc.update("""
                UPDATE folio_balance_snapshot_lock
                SET owner_id = NULL, locked_until = NULL, updated_at = NOW(3)
                WHERE id = 1 AND owner_id = ?
                """, ownerId);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record SnapshotClient(
            String partnerShortName,
            String partnerName,
            String partnerType,
            String city,
            String phone,
            BigDecimal commonDebt,
            BigDecimal deferredAmount,
            BigDecimal overdueDeferredAmount,
            BigDecimal prepaymentAmount,
            BigDecimal payableNow,
            LocalDateTime calculatedAt
    ) {
    }

    public record SnapshotSummary(
            long matchedClients,
            BigDecimal commonDebtTotal,
            BigDecimal deferredAmountTotal,
            BigDecimal overdueDeferredAmountTotal,
            BigDecimal prepaymentAmountTotal,
            BigDecimal payableNowTotal
    ) {
    }

    public record SnapshotPage(SnapshotSummary summary, List<SnapshotClient> clients) {
    }

    public record ActiveSnapshot(
            long generationId,
            String status,
            LocalDate asOfDate,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            int totalClients
    ) {
    }

    public record GenerationStatus(
            long generationId,
            String status,
            String triggerSource,
            LocalDate asOfDate,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            int totalClients,
            String errorMessage,
            boolean active
    ) {
    }
}
