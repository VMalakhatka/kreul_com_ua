package org.example.proect.lavka.dao;

import org.example.proect.lavka.entity.SclMove;

import java.util.List;

public interface SclMoveDao {
    List<SclMove> getMoveByGoodsAndData(String NamePredm, int id,String start, String end);
    List<SclMove> getMoveByListOfGoodsAndData(List<String> NamePredmList, List<Long> idList,String start, String end);


}
