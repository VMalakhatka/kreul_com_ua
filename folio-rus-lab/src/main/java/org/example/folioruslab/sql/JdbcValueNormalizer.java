package org.example.folioruslab.sql;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.ByteArrayOutputStream;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Base64;

@Component
final class JdbcValueNormalizer {

    private final SensitiveValueRedactor redactor;

    JdbcValueNormalizer(SensitiveValueRedactor redactor) {
        this.redactor = redactor;
    }

    Object normalize(Object value, boolean sensitive, OutputBudget budget) throws SQLException {
        if (value == null) {
            return null;
        }
        if (sensitive) {
            budget.addText(SensitiveValueRedactor.REDACTED);
            return SensitiveValueRedactor.REDACTED;
        }

        if (value instanceof byte[] bytes) {
            return encodeBytes(bytes, budget);
        }
        if (value instanceof Blob blob) {
            return readBlob(blob, budget);
        }
        if (value instanceof Clob clob) {
            return readClob(clob, budget);
        }
        if (value instanceof Timestamp timestamp) {
            return text(timestamp.toLocalDateTime().toString(), budget);
        }
        if (value instanceof Date date) {
            return text(date.toLocalDate().toString(), budget);
        }
        if (value instanceof Time time) {
            return text(time.toLocalTime().toString(), budget);
        }
        if (value instanceof Number) {
            return text(value.toString(), budget);
        }
        if (value instanceof Boolean bool) {
            budget.addBytes(5);
            return bool;
        }
        if (value instanceof Character character) {
            return text(redactor.sanitizeValue(character.toString()), budget);
        }
        return text(redactor.sanitizeValue(value.toString()), budget);
    }

    private String encodeBytes(byte[] value, OutputBudget budget) {
        long encodedLength = 4L * ((value.length + 2L) / 3L);
        budget.addBytes(encodedLength);
        return Base64.getEncoder().encodeToString(value);
    }

    private String readBlob(Blob blob, OutputBudget budget) throws SQLException {
        long maximumRawBytes = (budget.remainingBytes() / 4L) * 3L;
        if (maximumRawBytes < 1 || blob.length() > maximumRawBytes) {
            throw new OutputLimitExceededException("A binary value is too large to return safely");
        }
        try (InputStream input = blob.getBinaryStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     (int) Math.min(maximumRawBytes, 65_536L))) {
            byte[] buffer = new byte[8_192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumRawBytes) {
                    throw new OutputLimitExceededException("A binary value is too large to return safely");
                }
                output.write(buffer, 0, read);
            }
            return encodeBytes(output.toByteArray(), budget);
        } catch (IOException exception) {
            throw new SQLException("Could not read a binary large object", exception);
        }
    }

    private String readClob(Clob clob, OutputBudget budget) throws SQLException {
        StringBuilder value = new StringBuilder();
        try (Reader reader = clob.getCharacterStream()) {
            char[] buffer = new char[4_096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                value.append(buffer, 0, read);
                if (value.length() > budget.remainingBytes()) {
                    throw new OutputLimitExceededException("A text value is too large to return safely");
                }
            }
        } catch (IOException exception) {
            throw new SQLException("Could not read a character large object", exception);
        }
        return text(redactor.sanitizeValue(value.toString()), budget);
    }

    private static String text(String value, OutputBudget budget) {
        budget.addText(value);
        return value;
    }
}
