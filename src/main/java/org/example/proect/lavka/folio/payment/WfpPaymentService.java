package org.example.proect.lavka.folio.payment;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WfpPaymentService {
    private final WfpPaymentProperties config;
    private final WfpPaymentDao dao;
    private final TransactionTemplate transaction;
    public WfpPaymentService(WfpPaymentProperties config, WfpPaymentDao dao,
            @Qualifier("mssqlTransactionManager") PlatformTransactionManager manager) {
        this.config = config; this.dao = dao;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        transaction.setTimeout(40);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private void configured() {
        if (!config.isApiEnabled() || config.getExpectedDatabase().isBlank()
                || !config.getSourceScope().matches("[a-zA-Z0-9._-]{1,64}")
                || !WfpPaymentCommand.HEX.matcher(config.getMerchantHash()).matches()
                || config.getBankTemplateId() < 1) throw new WfpPaymentException(503, "payment_config_required");
        try { Instant.parse(config.getStartAt()); }
        catch (RuntimeException e) { throw new WfpPaymentException(503, "payment_cutoff_required"); }
        dao.environment(config.getExpectedDatabase());
    }
    public Map<String,Object> preview(WfpPaymentCommand request) {
        configured(); request.validate(config, Instant.now());
        var existing = dao.lookup(request.operationKey(), config.getSourceScope(), false);
        if (existing != null) return sameRequest(existing, request);
        var source = dao.snapshot(request, config.getBankTemplateId(), false);
        return Map.of("status", "READY", "operationKey", request.operationKey(), "requestHash", request.requestHash(),
                "revision", source.revision(), "amountCents", request.amountCents(), "paymentWarehouse", 5);
    }
    public Map<String,Object> apply(WfpPaymentCommand request) {
        configured(); request.validate(config, Instant.now());
        if (!config.isApplyEnabled()) throw new WfpPaymentException(503, "payment_apply_disabled");
        if (request.expectedRevision() == null || !WfpPaymentCommand.HEX.matcher(request.expectedRevision()).matches())
            throw new WfpPaymentException(422, "payment_preview_required");
        return transaction.execute(tx -> {
            dao.environment(config.getExpectedDatabase());
            // Actual table access starts jTDS's implicit transaction BEFORE the native procedure RPC.
            var existing = dao.lookup(request.operationKey(), config.getSourceScope(), true);
            if (existing != null) return sameRequest(existing, request);
            dao.transactionGuard();
            dao.verifyProcedures();
            var source = dao.snapshot(request, config.getBankTemplateId(), true);
            WfpPaymentException.require(source.revision().equals(request.expectedRevision()), "payer_or_template_changed");
            var result = dao.insertNative(request, source);
            dao.remember(request, result);
            dao.transactionGuard();
            return result;
        });
    }
    public Map<String,Object> status(String key) {
        configured();
        if (key == null || !WfpPaymentCommand.HEX.matcher(key).matches()) throw new WfpPaymentException(422, "invalid_operation_key");
        var row = dao.lookup(key, config.getSourceScope(), false);
        if (row == null) throw new WfpPaymentException(404, "payment_not_found");
        return row;
    }
    private Map<String,Object> sameRequest(Map<String,Object> row, WfpPaymentCommand request) {
        WfpPaymentException.require(request.requestHash().equals(row.get("requestHash")), "payment_identity_conflict");
        return row;
    }
}
