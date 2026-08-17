package org.example.folioruslab.web;

import org.example.folioruslab.sql.SqlPolicyViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PolicyFailureController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void policyResponseContainsOnlyStableViolationCodesAndNeverEchoesSql() throws Exception {
        String rawSql = "EXEC(N'SELECT * FROM private_table WHERE marker=raw-sql-must-not-echo')";

        mockMvc.perform(post("/policy-failure")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(rawSql))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("SQL_POLICY_REJECTED"))
                .andExpect(jsonPath("$.error.message")
                        .value("The SQL batch crosses the Paint_Rus laboratory boundary"))
                .andExpect(jsonPath("$.error.details[0]").value("DYNAMIC_SQL"))
                .andExpect(jsonPath("$.error.details[1]").value("CROSS_DATABASE_IDENTIFIER"))
                .andExpect(content().string(not(containsString("private_table"))))
                .andExpect(content().string(not(containsString("raw-sql-must-not-echo"))))
                .andExpect(content().string(not(containsString(rawSql))));
    }

    @RestController
    static class PolicyFailureController {

        @PostMapping(path = "/policy-failure", consumes = MediaType.TEXT_PLAIN_VALUE)
        void fail(@RequestBody String rawSql) {
            throw new RawSqlPolicyViolationException(rawSql);
        }
    }

    static final class RawSqlPolicyViolationException extends SqlPolicyViolationException {

        private final String rawSql;

        RawSqlPolicyViolationException(String rawSql) {
            super(List.of("DYNAMIC_SQL", "CROSS_DATABASE_IDENTIFIER"));
            this.rawSql = rawSql;
        }

        @Override
        public String getMessage() {
            return rawSql;
        }
    }
}
