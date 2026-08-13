package org.example.proect.lavka.dao.folio;

import com.zaxxer.hikari.HikariDataSource;
import org.example.proect.lavka.service.folio.FolioPartnerNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Repository
public class FolioCustomerBalanceDao {

    private static final String PROCEDURE = "dbo.I_DOLG_DOC";
    private static final String CALL_SQL = "{call " + PROCEDURE + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)}";
    private static final DateTimeFormatter FOLIO_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int MONEY_SCALE = 2;

    private final DataSource dataSource;

    public FolioCustomerBalanceDao(@Qualifier("folioDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public ProcedureResult load(String partnerId,
                                LocalDate dateFrom,
                                LocalDate dateTo,
                                List<Integer> warehouseIds,
                                boolean includeServicePayments) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            String partnerName = findPartnerName(connection, partnerId);
            if (partnerName == null) {
                throw new FolioPartnerNotFoundException(partnerId);
            }

            List<RawRow> rows = new ArrayList<>();
            BigDecimal openingBalance;
            BigDecimal openingCurrencyBalance;
            String openingCurrencyCode;

            try (CallableStatement statement = connection.prepareCall(CALL_SQL)) {
                bind(statement, partnerId, dateFrom, dateTo, warehouseIds, includeServicePayments);

                boolean hasResult = statement.execute();
                while (true) {
                    if (hasResult) {
                        try (ResultSet resultSet = statement.getResultSet()) {
                            if (isDetailedBalanceResult(resultSet)) {
                                int sourceOrder = rows.size();
                                while (resultSet.next()) {
                                    rows.add(mapRow(resultSet, sourceOrder++));
                                }
                            }
                        }
                    } else if (statement.getUpdateCount() == -1) {
                        break;
                    }
                    hasResult = statement.getMoreResults();
                }

                openingBalance = outDecimal(statement, 7);
                openingCurrencyBalance = outDecimal(statement, 8);
                openingCurrencyCode = trimToNull(statement.getString(9));
            }

            return new ProcedureResult(
                    partnerId,
                    partnerName,
                    openingBalance,
                    openingCurrencyBalance,
                    openingCurrencyCode,
                    List.copyOf(rows)
            );
        } catch (FolioPartnerNotFoundException e) {
            throw e;
        } catch (SQLException e) {
            throw new UncategorizedSQLException("Read Folio customer balance", CALL_SQL, e);
        } finally {
            disposeConnection(connection);
        }
    }

    private String findPartnerName(Connection connection, String partnerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT NAME_USER FROM dbo._PARTNER WITH (NOLOCK) WHERE N_USER = ?")) {
            statement.setString(1, partnerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String name = trimToNull(resultSet.getString(1));
                return name == null ? partnerId : name;
            }
        }
    }

    private void bind(CallableStatement statement,
                      String partnerId,
                      LocalDate dateFrom,
                      LocalDate dateTo,
                      List<Integer> warehouseIds,
                      boolean includeServicePayments) throws SQLException {
        statement.setNull(1, Types.INTEGER);
        String warehouseMembership = warehouseMembership(warehouseIds);
        if (warehouseMembership == null) {
            statement.setNull(2, Types.VARCHAR);
        } else {
            statement.setString(2, warehouseMembership);
        }
        statement.setString(3, FOLIO_DATE.format(dateFrom));
        statement.setString(4, FOLIO_DATE.format(dateTo));
        statement.setString(5, partnerId);
        statement.setBoolean(6, false);

        registerFloatInOut(statement, 7);
        registerFloatInOut(statement, 8);
        statement.registerOutParameter(9, Types.VARCHAR);
        statement.setString(9, "");
        registerFloatInOut(statement, 10);
        registerFloatInOut(statement, 11);

        statement.setInt(12, 2);
        statement.setBoolean(13, includeServicePayments);
    }

    private static void registerFloatInOut(CallableStatement statement, int index) throws SQLException {
        statement.registerOutParameter(index, Types.DOUBLE);
        statement.setDouble(index, 0D);
    }

    private static boolean isDetailedBalanceResult(ResultSet resultSet) throws SQLException {
        if (resultSet == null) {
            return false;
        }
        ResultSetMetaData metadata = resultSet.getMetaData();
        if (metadata.getColumnCount() < 41) {
            return false;
        }
        return "DATE_P_POR".equalsIgnoreCase(metadata.getColumnLabel(1))
                && "TYPE_DOC".equalsIgnoreCase(metadata.getColumnLabel(2));
    }

    private static RawRow mapRow(ResultSet resultSet, int sourceOrder) throws SQLException {
        return new RawRow(
                sourceOrder,
                toLocalDateTime(resultSet, 1),
                trimToNull(resultSet.getString(2)),
                trimToNull(resultSet.getString(3)),
                trimToNull(resultSet.getString(4)),
                decimal(resultSet, 5),
                nullableLong(resultSet, 8),
                nullableInteger(resultSet, 9),
                trimToNull(resultSet.getString(10)),
                trimToNull(resultSet.getString(11)),
                decimal(resultSet, 14),
                decimal(resultSet, 15),
                trimToNull(resultSet.getString(16)),
                trimToNull(resultSet.getString(17)),
                toLocalDateTime(resultSet, 18),
                toLocalDateTime(resultSet, 19),
                trimToNull(resultSet.getString(27)),
                trimToNull(resultSet.getString(28))
        );
    }

    private static BigDecimal decimal(ResultSet resultSet, int column) throws SQLException {
        BigDecimal value = resultSet.getBigDecimal(column);
        return money(value);
    }

    private static BigDecimal outDecimal(CallableStatement statement, int index) throws SQLException {
        BigDecimal value = statement.getBigDecimal(index);
        return money(value);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static Long nullableLong(ResultSet resultSet, int column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() || value == 0L ? null : value;
    }

    private static Integer nullableInteger(ResultSet resultSet, int column) throws SQLException {
        String raw = trimToNull(resultSet.getString(column));
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static LocalDateTime toLocalDateTime(ResultSet resultSet, int column) throws SQLException {
        java.sql.Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String warehouseMembership(List<Integer> warehouseIds) {
        if (warehouseIds == null || warehouseIds.isEmpty()) {
            return null;
        }
        StringBuilder value = new StringBuilder(",");
        for (Integer warehouseId : warehouseIds) {
            value.append(warehouseId).append(',');
        }
        return value.toString();
    }

    private void disposeConnection(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            if (dataSource instanceof HikariDataSource hikariDataSource) {
                // I_DOLG_DOC executes SET DATEFORMAT dmy. Do not return that session state to the pool.
                hikariDataSource.evictConnection(connection);
            }
        } catch (RuntimeException ignored) {
            // close below remains the fallback
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // the original SQL exception, if any, is more useful
            }
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ProcedureResult(
            String partnerId,
            String partnerName,
            BigDecimal openingBalance,
            BigDecimal openingCurrencyBalance,
            String openingCurrencyCode,
            List<RawRow> rows
    ) {
    }

    public record RawRow(
            int sourceOrder,
            LocalDateTime documentDate,
            String documentType,
            String documentNumber,
            String basis,
            BigDecimal amount,
            Long documentId,
            Integer warehouseId,
            String warehouseName,
            String folioDocumentKind,
            BigDecimal rawCashPayment,
            BigDecimal rawBankPayment,
            String myOrganization,
            String invoiceNumber,
            LocalDateTime invoiceDate,
            LocalDateTime controlDate,
            String payerName,
            String note
    ) {
    }
}
