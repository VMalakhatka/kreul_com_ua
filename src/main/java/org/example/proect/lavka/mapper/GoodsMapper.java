package org.example.proect.lavka.mapper;

import org.example.proect.lavka.entity.SclArtc;
import org.example.proect.lavka.dto.GoodsDtoOut;
import org.springframework.stereotype.Component;

@Component
public class GoodsMapper {
    public GoodsDtoOut toGoodsDtoOut(SclArtc goods){
        return new GoodsDtoOut(
                goods.codArtic(),
                goods.nameArtic(),
                0.0,
                goods.cenaValt(),
                goods.codValt(),
                goods.ednVUpak(),
                goods.dop2Artic(),
                goods.ball2() == 1.0,
                (int) goods.ball4());
    }
}
