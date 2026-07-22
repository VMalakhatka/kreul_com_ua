package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioAccountDao;
import org.springframework.stereotype.Component;

@Component
public class FolioNumberAllocator {

    private final FolioAccountDao dao;

    public FolioNumberAllocator(FolioAccountDao dao) {
        this.dao = dao;
    }

    public long nextDocumentId() {
        return dao.nextDocumentId();
    }

    public long nextMovementId() {
        return dao.nextMovementId();
    }
}
