package org.example.proect.lavka.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String HDR_REQUEST_ID = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String incoming = Optional.ofNullable(req.getHeader(HDR_REQUEST_ID))
                .filter(s -> !s.isBlank())
                .orElse(null);
        String reqId = incoming != null ? incoming : UUID.randomUUID().toString();

        // отдадим клиенту, даже если он не прислал
        res.setHeader(HDR_REQUEST_ID, reqId);

        long t0 = System.nanoTime();
        MDC.put("reqId", reqId);
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            // Лёгкая сводка входящего запроса
            org.slf4j.LoggerFactory.getLogger(RequestIdFilter.class)
                    .info("[http.in] {} {} -> {} {}ms", req.getMethod(), req.getRequestURI(), res.getStatus(), ms);
            MDC.clear();
        }
    }
}