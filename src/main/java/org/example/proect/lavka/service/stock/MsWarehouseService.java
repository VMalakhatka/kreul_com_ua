package org.example.proect.lavka.service.stock;

import org.example.proect.lavka.dao.stock.MsWarehouseDao;
import org.example.proect.lavka.dto.stock.MsWarehouse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MsWarehouseService {
    private final MsWarehouseDao dao;
    public MsWarehouseService(MsWarehouseDao dao) { this.dao = dao; }

    @Cacheable(value = "ms_warehouses", unless = "#result == null || #result.isEmpty()")
    public List<MsWarehouse> fetchVisible() {
        // тут можно будет включить кэш: @Cacheable("ref_warehouses")
        return dao.findAllVisible();
    }
}