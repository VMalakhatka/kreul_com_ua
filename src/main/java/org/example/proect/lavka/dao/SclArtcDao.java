package org.example.proect.lavka.dao;

import org.example.proect.lavka.dto.stock.StockRow;
import org.example.proect.lavka.entity.SclArtc;
import org.example.proect.lavka.dto.RestDtoOut;
import org.example.proect.lavka.dto.StockParamDtoOut;

import java.util.List;
import java.util.Set;

public interface SclArtcDao {
  List<SclArtc> getAllBySupplierAndStockId(String supplier, long idStock);

  List<RestDtoOut> getRestByGoodsListAndStockList(List<String> namePredmList, List<Long> idList);

  List<StockParamDtoOut> getStockParamByGoodsListAndStockList(List<String> namePredmList, List<Long> idList);

  List<SclArtc> getGoodsByNumDoc(long numDoc);

  List<StockRow> findFreeAll(Set<Integer> scladIds);

  List<StockRow> findFreeBySkus(Set<Integer> scladIds, List<String> skus);

  // SclArtcDao.java (интерфейс)
  List<String> findSkusWithMovement(Set<Integer> scladIds,
                                    java.time.Instant from,
                                    java.time.Instant to,
                                    int limit,
                                    int offset);
}
