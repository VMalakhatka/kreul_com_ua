package org.example.proect.lavka.dao;

import org.example.proect.lavka.dto.ref.OpTypeDto;
import java.util.List;

public interface RefDao {
    List<OpTypeDto> findOpTypes();
}