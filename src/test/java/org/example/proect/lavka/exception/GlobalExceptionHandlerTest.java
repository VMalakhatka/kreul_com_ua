package org.example.proect.lavka.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void unknownRouteReturnsQuietNotFoundInsteadOfInternalError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/wp-login.php");
        NoHandlerFoundException error = new NoHandlerFoundException(
                "GET", "/wp-login.php", HttpHeaders.EMPTY);

        var response = handler.handleRouteNotFound(error, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "ROUTE_NOT_FOUND");
        assertThat(response.getBody()).containsEntry("status", 404);
    }
}
