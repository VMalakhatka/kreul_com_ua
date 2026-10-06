package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioPartnerDao;
import org.example.proect.lavka.dto.folio.FolioPartnersResponse;
import org.example.proect.lavka.dto.folio.FolioRegistrationCustomer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class FolioPartnerService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final List<String> DEFAULT_TYPES = List.of("П", "Д", "К");

    private final FolioPartnerDao dao;

    public FolioPartnerService(FolioPartnerDao dao) {
        this.dao = dao;
    }

    @Transactional(transactionManager = "mssqlTransactionManager", readOnly = true)
    public FolioPartnersResponse search(String q, String types, Integer limit, Integer offset) {
        int normalizedLimit = normalizeLimit(limit);
        int normalizedOffset = normalizeOffset(offset);
        List<String> normalizedTypes = normalizeTypes(types);

        long total = dao.count(q, normalizedTypes);
        var items = dao.find(q, normalizedTypes, normalizedLimit, normalizedOffset);

        return new FolioPartnersResponse(true, items, total, normalizedLimit, normalizedOffset);
    }

    @Transactional(transactionManager = "mssqlTransactionManager", readOnly = true)
    public FolioRegistrationCustomer registrationCustomer(String id) {
        return dao.registrationCustomer(id);
    }

    @Transactional(transactionManager = "mssqlTransactionManager", readOnly = true)
    public java.util.Map<String, String> registrationEmails(java.util.List<String> ids) {
        return dao.registrationEmails(ids);
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static int normalizeOffset(Integer offset) {
        if (offset == null || offset < 0) {
            return 0;
        }
        return offset;
    }

    private static List<String> normalizeTypes(String types) {
        if (types == null || types.trim().isEmpty()) {
            return DEFAULT_TYPES;
        }
        if ("all".equalsIgnoreCase(types.trim())) {
            return List.of();
        }
        return Arrays.stream(types.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }
}
