package org.example.proect.lavka.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.InvocationTargetException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvocationTargetException.class)
    public ResponseEntity<String> handleInvocation(InvocationTargetException e) {
        Throwable root = e.getTargetException() != null ? e.getTargetException() : e.getCause();
        log.error("InvocationTargetException root cause:", root);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error: " + (root != null ? root.getMessage() : e.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<String> handleDatabase(DataAccessException e) {
        log.error("Database error:", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("MSSQL unavailable or query failed: " + e.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleAny(Exception e) {
        log.error("Unhandled exception:", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error: " + e.getMessage());
    }
}