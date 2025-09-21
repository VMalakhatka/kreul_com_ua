package org.example.proect.lavka.dao;

import org.example.proect.lavka.entity.SclArtc;
import org.example.proect.lavka.dto.RestDtoOut;
import org.example.proect.lavka.dto.StockParamDtoOut;

import java.util.List;

public interface SclArtcDao {
  List<SclArtc> getAllBySupplierAndStockId(String supplier, long idStock);

  List<RestDtoOut> getRestByGoodsListAndStockList(List<String> namePredmList, List<Long> idList);

  List<StockParamDtoOut> getStockParamByGoodsListAndStockList(List<String> namePredmList, List<Long> idList);

    List<SclArtc> getGoodsByNumDoc(long numDoc);
}
