package org.example.proect.lavka.exception;

import lombok.extern.slf4j.Slf4j;
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleAny(Exception e) {
        log.error("Unhandled exception:", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error: " + e.getMessage());
    }
}