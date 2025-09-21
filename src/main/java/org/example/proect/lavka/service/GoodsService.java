package org.example.proect.lavka.service;

import org.example.proect.lavka.dao.SclArtcDao;
import org.example.proect.lavka.dao.SclMoveDao;
import org.example.proect.lavka.dto.GetGoodsDtoOut;
import org.example.proect.lavka.dto.GoodsDtoOut;
import org.example.proect.lavka.dto.enums.TypeOfForecast;
import org.example.proect.lavka.entity.SclArtc;
import org.example.proect.lavka.entity.SclMove;
import org.example.proect.lavka.mapper.GoodsMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class GoodsService {
    SclArtcDao sclArtcDao;
    SclMoveDao sclMoveDao;
    GoodsMapper goodsMapper;

    public GoodsService(SclArtcDao sclArtcDao, SclMoveDao sclMoveDao, GoodsMapper goodsMapper) {
        this.sclArtcDao = sclArtcDao;
        this.sclMoveDao = sclMoveDao;
        this.goodsMapper = goodsMapper;
    }

    public List<SclArtc> getGoodsBySupplierAndStockId(String supp, long id){
        return sclArtcDao.getAllBySupplierAndStockId(supp,id);
    }

    public List<SclMove> getMoveByNameGoodsAndStockId(String nameArtc, int id, String strStart, String strEnd){
        return sclMoveDao.getMoveByGoodsAndData(nameArtc,id ,strStart,strEnd);
    }

    public List<GoodsDtoOut> getGoods(GetGoodsDtoOut getGoodsDtoOut){
        switch (TypeOfForecast.valueOf(getGoodsDtoOut.type())){
            case IMPORT:
                break;
            case OPT:
                break;
            case SUPPLIER:
                return sclArtcDao.getAllBySupplierAndStockId(getGoodsDtoOut.supplier(),getGoodsDtoOut.idStock()).stream().map(g->
                        goodsMapper.toGoodsDtoOut(g)).collect(Collectors.toList());
            case INTERNAL_TRANSFER:
                break;
            case DOCUMENT:
               // TODO Throws:NumberFormatException – if the string does not contain a parsable long.
                return sclArtcDao.getGoodsByNumDoc(Long.parseLong(getGoodsDtoOut.supplier())).stream().map(g->g==null? null:
                        goodsMapper.toGoodsDtoOut(g)).collect(Collectors.toList());
        }
        return null;
    }

    public List<SclArtc> getGoodsByNumDoc(long numDoc) {
        return sclArtcDao.getGoodsByNumDoc(numDoc);
    }
}
