package org.example.proect.lavka.dao.folio;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioRegistrationDaoTest {
    @Test void usesExactParameterizedCustomerKeyAndVerifiedContactColumns() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("N_USER")).thenReturn(" ТЕСТ ");
        when(rs.getString("EMAIL_USER")).thenReturn(" test@example.invalid ");
        when(rs.getString("TEL1_USER")).thenReturn("+380000000000");
        when(rs.getString("DOST_ADRESS")).thenReturn("Delivery address");
        when(rs.getString("TOWNB_USER")).thenReturn(" Test city ");
        when(rs.getString("CP_2")).thenReturn(" TEST WHOLESALE ");
        when(rs.getString("PRIMECH")).thenReturn(" Reference note ");
        when(rs.getString("INFORM_PAR")).thenReturn(" Contact one\nContact two ");
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                assertTrue(sql.contains("WHERE N_USER = ?"));
                assertFalse(sql.contains("BANK_USER"));
                assertArrayEquals(new Object[]{"ТЕСТ", "П", "Д", "К", "H"}, args);
                try { return List.of(mapper.mapRow(rs, 0)); } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            }
        };
        var result = new FolioPartnerDao(jdbc).registrationCustomer("ТЕСТ");
        assertEquals("ТЕСТ", result.id());
        assertEquals("test@example.invalid", result.email());
        assertEquals("Delivery address", result.deliveryAddress());
        assertEquals("Test city", result.bankCity());
        assertEquals("TEST WHOLESALE", result.contactType());
        assertEquals("Reference note", result.note());
        assertEquals("Contact one\nContact two", result.additionalInfo());
    }
    @Test void emailBatchUsesOneBoundQueryAndPreservesMissingEmail() throws Exception {
        ResultSet rs=mock(ResultSet.class);
        when(rs.getString("N_USER")).thenReturn(" TEST ");
        JdbcTemplate jdbc=new JdbcTemplate() {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                assertTrue(sql.startsWith("SELECT N_USER, EMAIL_USER"));
                assertTrue(sql.contains("N_USER IN (?,?)"));
                assertArrayEquals(new Object[]{"TEST","SECOND","П","Д","К","H"},args);
                try{return List.of(mapper.mapRow(rs,0));}catch(java.sql.SQLException e){throw new RuntimeException(e);}
            }
        };
        var result=new FolioPartnerDao(jdbc).registrationEmails(List.of("TEST","SECOND"));
        assertTrue(result.containsKey("TEST"));assertNull(result.get("TEST"));
        assertFalse(result.containsKey("SECOND"));
    }
}
