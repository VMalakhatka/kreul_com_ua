package org.example.proect.lavka.dao;


import org.example.proect.lavka.dto.CardTovExportDto;
import java.util.List;

public interface CardTovExportDao {
    List<CardTovExportDto> findPage(String after, int limit);
}