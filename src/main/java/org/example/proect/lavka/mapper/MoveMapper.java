package org.example.proect.lavka.mapper;

import org.example.proect.lavka.entity.enums.TypDocmPrOut;
import org.example.proect.lavka.dto.MoveDtoOut;
import org.example.proect.lavka.entity.SclMove;
import org.springframework.stereotype.Component;

@Component
public class MoveMapper {
    public MoveDtoOut toMoveDtoOut(SclMove sclMove){
        return new MoveDtoOut(
                sclMove.NamePredm(),
                sclMove.UnicumNum(),
                sclMove.nDocum(),
                sclMove.numdcmDop(),
                sclMove.orgPredm(),
                sclMove.data().toLocalDateTime(),
                sclMove.quantity(),
                TypDocmPrOut.fromString(sclMove.typDocmPr()),
                sclMove.vidDoc(),
                sclMove.idStock()
        );
    }
}
