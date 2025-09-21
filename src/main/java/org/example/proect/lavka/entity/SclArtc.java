package org.example.proect.lavka.entity;

public record SclArtc(
        String codArtic, // "COD_ARTIC"
        // String ngroupTvr, //"NGROUP_TVR"
        String nameArtic, // "NAME_ARTIC"
        //double cenaArtic, // "CENA_ARTIC"
        //boolean priznValt, //"PRIZN_VALT"
        double cenaValt, //"CENA_VALT"
        String codValt, //"COD_VALT"
        //double ndsArtic,
        //boolean ndsTorgn,
        //double nachKolch,
        double konKolch, //"KON_KOLCH"
        double rezKolch, //"REZ_KOLCH"
        String edinIzmer, //"EDIN_IZMER"
        //double vesEdinic,
        double ednVUpak, //"EDN_V_UPAK"
        // double minPartia,
        //double dlinaArt,
        //double shirinArt,в
        //double vysotaArt,
        //String razmIzmer,
        //String schetArt, //"SCHET_ART"
        //String dop1Artic,
        String dop2Artic, // "DOP2_ARTIC" -supplier
        //double uchetCena, //"UCHET_CENA"
        //double uchetValt,
        //double uchetSum,
        //double uchetSmvl,
        //double kolSum,
        //String ngroupTv2,
        //double uchet0C,
        //double nal1Art,
        //double nal2Art,
        //double uchet0Vl,
        //boolean fixNacen,
        //double cenaBznal,
        //double cenaVBzn,
        //String ngroupTv3, String ngroupTv4, String ngroupTv5, String ngroupTv6,
        //boolean priceList,
        String dop3Artic,//"DOP3_ARTIC" Shtrichcod
        //double coefBznal,
        //String okdpArtic,
        double minTvrzap,//"MIN_TVRZAP"
        double maxTvrzap,//"MAX_TVRZAP"
        int idSclad,//"ID_SCLAD"
        double ball1,//"BALL1" Таможенный код
        double ball2,// "BALL2"  Признак мерзнет не мерзнет 2 – не мерзнет, 1- мерзнет, 3- неизвестно
        //double ball3, // "BALL3" - Вес товара
        double ball4, //"BALL4" Признак Карточки Разборки
        double ball5, // "BALL5"    1-КИО  11-Укр

      //  int departam, //1-на сайт выводить
        //double nalProd,
        String tipTovr //"TIP_TOVR" Тип товара 0 – оптом не продаем1 – продаем оптом только с Кальмиуса2 – продаем оптом со всех магазинов


) {}

