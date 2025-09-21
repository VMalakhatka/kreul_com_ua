package org.example.proect.lavka.entity;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.sql.Timestamp;


public record SclMove(
        String NamePredm, //"NAME_PREDM"
        double UnicumNum,//"UNICUM_NUM"
        long nDocum, //"NUMDOCM_PR"
        String  numdcmDop, //"NUMDCM_DOP"
        String orgPredm, //"ORG_PREDM"
        Timestamp data,//"DATE_PREDM"
        double quantity, // KOLC_PREDM
        String typDocmPr, // TYPDOCM_PR
        String vidDoc, //VID_DOC,
        int idStock //ID_SCLAD
    ) {
}
