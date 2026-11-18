package org.example.proect.lavka.service.stock;

import org.example.proect.lavka.dao.stock.PriceDao;
import org.example.proect.lavka.dto.stock.PriceQueryRequest;
import org.example.proect.lavka.dto.stock.PriceRow;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriceServiceTest {
    @Test
    void matchesBothPricesByTrimmedKeyButReturnsOriginalWooSkus() {
        var dao = mock(PriceDao.class);
        var service = new PriceService(dao, new LavkaApiProperties());
        var keys = List.of("CL-96018C", "A B", "AB");
        when(dao.findRetailPrices(7, keys)).thenReturn(Map.of("CL-96018C", 2862.0));
        when(dao.findLatestPrices(7, keys, List.of("partner"))).thenReturn(List.of(
                new PriceRow("CL-96018C", "partner", 1949.31, null),
                new PriceRow("A B", "partner", 0.0, null)));
        var requested = List.of(" CL-96018C ", "CL-96018C", "A B ", "AB");
        var items = service.resolve(new PriceQueryRequest(requested, Map.of("p", "partner"))).items();
        assertEquals(requested, items.stream().map(i -> i.sku()).toList());
        for (var item : items.subList(0, 2)) {
            assertEquals(2862.0, item.price());
            assertEquals(1949.31, item.prices().get("p"));
        }
        assertEquals(0.0, items.get(2).prices().get("p"));
        assertTrue(items.get(3).prices().containsKey("p"));
        assertNull(items.get(3).prices().get("p"));
        assertNull(items.get(3).price());
    }
}
