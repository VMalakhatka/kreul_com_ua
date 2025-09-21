package org.example.proect.lavka.dao;

import org.example.proect.lavka.dto.AssembleDtoOut;

import java.util.List;

public interface AssembleDao {
    List<AssembleDtoOut> getAssembleByGoodsList(List<String> namePredmList);
}
