package org.example.proect.lavka.rabbitMQ.publisher;


import org.example.proect.lavka.config.RabbitConfig;
import org.example.proect.lavka.dao.SclArtcDao;
import org.example.proect.lavka.dto.*;
import org.example.proect.lavka.entity.SclArtc;
import org.example.proect.lavka.dao.SclMoveDao;
import org.example.proect.lavka.mapper.GoodsMapper;
import org.example.proect.lavka.service.GoodsService;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@EnableRabbit
public class GoodsClient {

   SclArtcDao sclArtcDao;
   SclMoveDao sclMoveDao;
   GoodsMapper goodsMapper;
   GoodsService goodsService;
    @Autowired
    public GoodsClient(SclArtcDao sclArtcDao, SclMoveDao sclMoveDao, GoodsMapper goodsMapper, GoodsService goodsService) {
        this.sclArtcDao = sclArtcDao;
        this.sclMoveDao = sclMoveDao;
        this.goodsMapper = goodsMapper;
        this.goodsService = goodsService;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_NAME)
    public List<GoodsDtoOut> getGoods(@Payload GetGoodsDtoOut getGoodsDtoOut){
        List<GoodsDtoOut> goodsDtoOut=goodsService.getGoods(getGoodsDtoOut);
        return goodsDtoOut;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_FOR_REST)
    public List<RestDtoOut> getRestByGoodsAndStockList(@Payload GetDataByGoodsListAndStockListDtoIn goodsListAndStockListDtoInksList){
        return sclArtcDao.getRestByGoodsListAndStockList(goodsListAndStockListDtoInksList.namePredmList(),
                goodsListAndStockListDtoInksList.idList());
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_FOR_SOCK_PARAM)
    public List<StockParamDtoOut> getStockParamByGoodsAndStockList(@Payload GetDataByGoodsListAndStockListDtoIn goodsListAndStockListDtoInksList){
        return sclArtcDao.getStockParamByGoodsListAndStockList(goodsListAndStockListDtoInksList.namePredmList(),
                goodsListAndStockListDtoInksList.idList());
    }

    public List<SclArtc> getGoodsByNumDoc(long numDoc) {
        return sclArtcDao.getGoodsByNumDoc(numDoc);
    }
}
