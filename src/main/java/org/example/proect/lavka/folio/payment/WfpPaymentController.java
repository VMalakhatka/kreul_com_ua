package org.example.proect.lavka.folio.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/folio/wfp-payments")
public class WfpPaymentController {
    private final WfpPaymentService service;
    private final WfpPaymentProperties config;
    public WfpPaymentController(WfpPaymentService service, WfpPaymentProperties config) { this.service = service; this.config = config; }
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestHeader(value="X-Auth-Token", required=false) String token, @RequestBody WfpPaymentCommand request) {
        return guarded(token, () -> service.preview(request));
    }
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestHeader(value="X-Auth-Token", required=false) String token, @RequestBody WfpPaymentCommand request) {
        return guarded(token, () -> service.apply(request));
    }
    @GetMapping("/{key}")
    public ResponseEntity<?> status(@RequestHeader(value="X-Auth-Token", required=false) String token, @PathVariable String key) {
        return guarded(token, () -> service.status(key));
    }
    private ResponseEntity<?> guarded(String token, Supplier<?> work) {
        int status = 200; Object body;
        if (!config.isApiEnabled() || config.getToken().length() < 32) {
            status = 503; body = Map.of("code", "payment_api_disabled");
        } else if (token == null || !MessageDigest.isEqual(config.getToken().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            status = 401; body = Map.of("code", "unauthorized");
        } else try { body = work.get(); }
        catch (WfpPaymentException e) { status = e.status; body = Map.of("code", e.getMessage()); }
        catch (RuntimeException e) {
            // SQL errors may contain customer/connection data; do not echo/log the exception.
            status = 503; body = Map.of("code", "payment_outcome_requires_lookup");
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
