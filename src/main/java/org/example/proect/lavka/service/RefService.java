package org.example.proect.lavka.service;


import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.RefDao;
import org.example.proect.lavka.dto.ref.OpTypeDto;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RefService {
    private final RefDao refDao;

    public List<OpTypeDto> getOpTypes() {
        return refDao.findOpTypes();
    }
}