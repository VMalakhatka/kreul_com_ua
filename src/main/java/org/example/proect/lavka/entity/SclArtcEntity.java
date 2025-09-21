//package org.example.proect_lavka;
//
//import jakarta.persistence.*;
//
//import java.util.Objects;
//
//@Entity
//@jakarta.persistence.Table(name = "SCL_ARTC", schema = "dbo", catalog = "Paint_Ua")
//@IdClass(org.example.proect_lavka.SclArtcEntityPK.class)
//public class SclArtcEntity {
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    @Id
//    @jakarta.persistence.Column(name = "COD_ARTIC", nullable = false, length = 20)
//    private String codArtic;
//
//    public String getCodArtic() {
//        return codArtic;
//    }
//
//    public void setCodArtic(String codArtic) {
//        this.codArtic = codArtic;
//    }
//
//    @Basic
//    @Column(name = "NGROUP_TVR", nullable = true, length = 30)
//    private String ngroupTvr;
//
//    public String getNgroupTvr() {
//        return ngroupTvr;
//    }
//
//    public void setNgroupTvr(String ngroupTvr) {
//        this.ngroupTvr = ngroupTvr;
//    }
//
//    @Basic
//    @Column(name = "NAME_ARTIC", nullable = true, length = 200)
//    private String nameArtic;
//
//    public String getNameArtic() {
//        return nameArtic;
//    }
//
//    public void setNameArtic(String nameArtic) {
//        this.nameArtic = nameArtic;
//    }
//
//    @Basic
//    @Column(name = "CENA_ARTIC", nullable = true, precision = 0)
//    private Double cenaArtic;
//
//    public Double getCenaArtic() {
//        return cenaArtic;
//    }
//
//    public void setCenaArtic(Double cenaArtic) {
//        this.cenaArtic = cenaArtic;
//    }
//
//    @Basic
//    @Column(name = "PRIZN_VALT", nullable = false)
//    private boolean priznValt;
//
//    public boolean isPriznValt() {
//        return priznValt;
//    }
//
//    public void setPriznValt(boolean priznValt) {
//        this.priznValt = priznValt;
//    }
//
//    @Basic
//    @Column(name = "CENA_VALT", nullable = true, precision = 0)
//    private Double cenaValt;
//
//    public Double getCenaValt() {
//        return cenaValt;
//    }
//
//    public void setCenaValt(Double cenaValt) {
//        this.cenaValt = cenaValt;
//    }
//
//    @Basic
//    @Column(name = "COD_VALT", nullable = true, length = 4)
//    private String codValt;
//
//    public String getCodValt() {
//        return codValt;
//    }
//
//    public void setCodValt(String codValt) {
//        this.codValt = codValt;
//    }
//
//    @Basic
//    @Column(name = "NDS_ARTIC", nullable = true, precision = 0)
//    private Double ndsArtic;
//
//    public Double getNdsArtic() {
//        return ndsArtic;
//    }
//
//    public void setNdsArtic(Double ndsArtic) {
//        this.ndsArtic = ndsArtic;
//    }
//
//    @Basic
//    @Column(name = "NDS_TORGN", nullable = false)
//    private boolean ndsTorgn;
//
//    public boolean isNdsTorgn() {
//        return ndsTorgn;
//    }
//
//    public void setNdsTorgn(boolean ndsTorgn) {
//        this.ndsTorgn = ndsTorgn;
//    }
//
//    @Basic
//    @Column(name = "NACH_KOLCH", nullable = false, precision = 0)
//    private double nachKolch;
//
//    public double getNachKolch() {
//        return nachKolch;
//    }
//
//    public void setNachKolch(double nachKolch) {
//        this.nachKolch = nachKolch;
//    }
//
//    @Basic
//    @Column(name = "KON_KOLCH", nullable = false, precision = 0)
//    private double konKolch;
//
//    public double getKonKolch() {
//        return konKolch;
//    }
//
//    public void setKonKolch(double konKolch) {
//        this.konKolch = konKolch;
//    }
//
//    @Basic
//    @Column(name = "REZ_KOLCH", nullable = false, precision = 0)
//    private double rezKolch;
//
//    public double getRezKolch() {
//        return rezKolch;
//    }
//
//    public void setRezKolch(double rezKolch) {
//        this.rezKolch = rezKolch;
//    }
//
//    @Basic
//    @Column(name = "EDIN_IZMER", nullable = true, length = 10)
//    private String edinIzmer;
//
//    public String getEdinIzmer() {
//        return edinIzmer;
//    }
//
//    public void setEdinIzmer(String edinIzmer) {
//        this.edinIzmer = edinIzmer;
//    }
//
//    @Basic
//    @Column(name = "VES_EDINIC", nullable = true, precision = 0)
//    private Double vesEdinic;
//
//    public Double getVesEdinic() {
//        return vesEdinic;
//    }
//
//    public void setVesEdinic(Double vesEdinic) {
//        this.vesEdinic = vesEdinic;
//    }
//
//    @Basic
//    @Column(name = "EDN_V_UPAK", nullable = true, precision = 0)
//    private Double ednVUpak;
//
//    public Double getEdnVUpak() {
//        return ednVUpak;
//    }
//
//    public void setEdnVUpak(Double ednVUpak) {
//        this.ednVUpak = ednVUpak;
//    }
//
//    @Basic
//    @Column(name = "MIN_PARTIA", nullable = true, precision = 0)
//    private Double minPartia;
//
//    public Double getMinPartia() {
//        return minPartia;
//    }
//
//    public void setMinPartia(Double minPartia) {
//        this.minPartia = minPartia;
//    }
//
//    @Basic
//    @Column(name = "DLINA_ART", nullable = true, precision = 0)
//    private Double dlinaArt;
//
//    public Double getDlinaArt() {
//        return dlinaArt;
//    }
//
//    public void setDlinaArt(Double dlinaArt) {
//        this.dlinaArt = dlinaArt;
//    }
//
//    @Basic
//    @Column(name = "SHIRIN_ART", nullable = true, precision = 0)
//    private Double shirinArt;
//
//    public Double getShirinArt() {
//        return shirinArt;
//    }
//
//    public void setShirinArt(Double shirinArt) {
//        this.shirinArt = shirinArt;
//    }
//
//    @Basic
//    @Column(name = "VYSOTA_ART", nullable = true, precision = 0)
//    private Double vysotaArt;
//
//    public Double getVysotaArt() {
//        return vysotaArt;
//    }
//
//    public void setVysotaArt(Double vysotaArt) {
//        this.vysotaArt = vysotaArt;
//    }
//
//    @Basic
//    @Column(name = "RAZM_IZMER", nullable = true, length = 5)
//    private String razmIzmer;
//
//    public String getRazmIzmer() {
//        return razmIzmer;
//    }
//
//    public void setRazmIzmer(String razmIzmer) {
//        this.razmIzmer = razmIzmer;
//    }
//
//    @Basic
//    @Column(name = "SCHET_ART", nullable = true, length = 6)
//    private String schetArt;
//
//    public String getSchetArt() {
//        return schetArt;
//    }
//
//    public void setSchetArt(String schetArt) {
//        this.schetArt = schetArt;
//    }
//
//    @Basic
//    @Column(name = "DOP1_ARTIC", nullable = true, length = 60)
//    private String dop1Artic;
//
//    public String getDop1Artic() {
//        return dop1Artic;
//    }
//
//    public void setDop1Artic(String dop1Artic) {
//        this.dop1Artic = dop1Artic;
//    }
//
//    @Basic
//    @Column(name = "DOP2_ARTIC", nullable = true, length = 50)
//    private String dop2Artic;
//
//    public String getDop2Artic() {
//        return dop2Artic;
//    }
//
//    public void setDop2Artic(String dop2Artic) {
//        this.dop2Artic = dop2Artic;
//    }
//
//    @Basic
//    @Column(name = "UCHET_CENA", nullable = true, precision = 0)
//    private Double uchetCena;
//
//    public Double getUchetCena() {
//        return uchetCena;
//    }
//
//    public void setUchetCena(Double uchetCena) {
//        this.uchetCena = uchetCena;
//    }
//
//    @Basic
//    @Column(name = "UCHET_VALT", nullable = true, precision = 0)
//    private Double uchetValt;
//
//    public Double getUchetValt() {
//        return uchetValt;
//    }
//
//    public void setUchetValt(Double uchetValt) {
//        this.uchetValt = uchetValt;
//    }
//
//    @Basic
//    @Column(name = "UCHET_SUM", nullable = true, precision = 0)
//    private Double uchetSum;
//
//    public Double getUchetSum() {
//        return uchetSum;
//    }
//
//    public void setUchetSum(Double uchetSum) {
//        this.uchetSum = uchetSum;
//    }
//
//    @Basic
//    @Column(name = "UCHET_SMVL", nullable = true, precision = 0)
//    private Double uchetSmvl;
//
//    public Double getUchetSmvl() {
//        return uchetSmvl;
//    }
//
//    public void setUchetSmvl(Double uchetSmvl) {
//        this.uchetSmvl = uchetSmvl;
//    }
//
//    @Basic
//    @Column(name = "KOL_SUM", nullable = true, precision = 0)
//    private Double kolSum;
//
//    public Double getKolSum() {
//        return kolSum;
//    }
//
//    public void setKolSum(Double kolSum) {
//        this.kolSum = kolSum;
//    }
//
//    @Basic
//    @Column(name = "NGROUP_TV2", nullable = true, length = 30)
//    private String ngroupTv2;
//
//    public String getNgroupTv2() {
//        return ngroupTv2;
//    }
//
//    public void setNgroupTv2(String ngroupTv2) {
//        this.ngroupTv2 = ngroupTv2;
//    }
//
//    @Basic
//    @Column(name = "UCHET_0_C", nullable = false, precision = 0)
//    private double uchet0C;
//
//    public double getUchet0C() {
//        return uchet0C;
//    }
//
//    public void setUchet0C(double uchet0C) {
//        this.uchet0C = uchet0C;
//    }
//
//    @Basic
//    @Column(name = "NAL1_ART", nullable = true, precision = 0)
//    private Double nal1Art;
//
//    public Double getNal1Art() {
//        return nal1Art;
//    }
//
//    public void setNal1Art(Double nal1Art) {
//        this.nal1Art = nal1Art;
//    }
//
//    @Basic
//    @Column(name = "NAL2_ART", nullable = true, precision = 0)
//    private Double nal2Art;
//
//    public Double getNal2Art() {
//        return nal2Art;
//    }
//
//    public void setNal2Art(Double nal2Art) {
//        this.nal2Art = nal2Art;
//    }
//
//    @Basic
//    @Column(name = "UCHET_0_VL", nullable = false, precision = 0)
//    private double uchet0Vl;
//
//    public double getUchet0Vl() {
//        return uchet0Vl;
//    }
//
//    public void setUchet0Vl(double uchet0Vl) {
//        this.uchet0Vl = uchet0Vl;
//    }
//
//    @Basic
//    @Column(name = "FIX_NACEN", nullable = false)
//    private boolean fixNacen;
//
//    public boolean isFixNacen() {
//        return fixNacen;
//    }
//
//    public void setFixNacen(boolean fixNacen) {
//        this.fixNacen = fixNacen;
//    }
//
//    @Basic
//    @Column(name = "CENA_BZNAL", nullable = true, precision = 0)
//    private Double cenaBznal;
//
//    public Double getCenaBznal() {
//        return cenaBznal;
//    }
//
//    public void setCenaBznal(Double cenaBznal) {
//        this.cenaBznal = cenaBznal;
//    }
//
//    @Basic
//    @Column(name = "CENA_V_BZN", nullable = true, precision = 0)
//    private Double cenaVBzn;
//
//    public Double getCenaVBzn() {
//        return cenaVBzn;
//    }
//
//    public void setCenaVBzn(Double cenaVBzn) {
//        this.cenaVBzn = cenaVBzn;
//    }
//
//    @Basic
//    @Column(name = "NGROUP_TV3", nullable = true, length = 30)
//    private String ngroupTv3;
//
//    public String getNgroupTv3() {
//        return ngroupTv3;
//    }
//
//    public void setNgroupTv3(String ngroupTv3) {
//        this.ngroupTv3 = ngroupTv3;
//    }
//
//    @Basic
//    @Column(name = "NGROUP_TV4", nullable = true, length = 30)
//    private String ngroupTv4;
//
//    public String getNgroupTv4() {
//        return ngroupTv4;
//    }
//
//    public void setNgroupTv4(String ngroupTv4) {
//        this.ngroupTv4 = ngroupTv4;
//    }
//
//    @Basic
//    @Column(name = "NGROUP_TV5", nullable = true, length = 30)
//    private String ngroupTv5;
//
//    public String getNgroupTv5() {
//        return ngroupTv5;
//    }
//
//    public void setNgroupTv5(String ngroupTv5) {
//        this.ngroupTv5 = ngroupTv5;
//    }
//
//    @Basic
//    @Column(name = "NGROUP_TV6", nullable = true, length = 30)
//    private String ngroupTv6;
//
//    public String getNgroupTv6() {
//        return ngroupTv6;
//    }
//
//    public void setNgroupTv6(String ngroupTv6) {
//        this.ngroupTv6 = ngroupTv6;
//    }
//
//    @Basic
//    @Column(name = "PRICE_LIST", nullable = false)
//    private boolean priceList;
//
//    public boolean isPriceList() {
//        return priceList;
//    }
//
//    public void setPriceList(boolean priceList) {
//        this.priceList = priceList;
//    }
//
//    @Basic
//    @Column(name = "DOP3_ARTIC", nullable = true, length = 255)
//    private String dop3Artic;
//
//    public String getDop3Artic() {
//        return dop3Artic;
//    }
//
//    public void setDop3Artic(String dop3Artic) {
//        this.dop3Artic = dop3Artic;
//    }
//
//    @Basic
//    @Column(name = "COEF_BZNAL", nullable = true, precision = 0)
//    private Double coefBznal;
//
//    public Double getCoefBznal() {
//        return coefBznal;
//    }
//
//    public void setCoefBznal(Double coefBznal) {
//        this.coefBznal = coefBznal;
//    }
//
//    @Basic
//    @Column(name = "OKDP_ARTIC", nullable = true, length = 20)
//    private String okdpArtic;
//
//    public String getOkdpArtic() {
//        return okdpArtic;
//    }
//
//    public void setOkdpArtic(String okdpArtic) {
//        this.okdpArtic = okdpArtic;
//    }
//
//    @Basic
//    @Column(name = "MIN_TVRZAP", nullable = true, precision = 0)
//    private Double minTvrzap;
//
//    public Double getMinTvrzap() {
//        return minTvrzap;
//    }
//
//    public void setMinTvrzap(Double minTvrzap) {
//        this.minTvrzap = minTvrzap;
//    }
//
//    @Basic
//    @Column(name = "MAX_TVRZAP", nullable = true, precision = 0)
//    private Double maxTvrzap;
//
//    public Double getMaxTvrzap() {
//        return maxTvrzap;
//    }
//
//    public void setMaxTvrzap(Double maxTvrzap) {
//        this.maxTvrzap = maxTvrzap;
//    }
//
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    @Id
//    @Column(name = "ID_SCLAD", nullable = false)
//    private int idSclad;
//
//    public int getIdSclad() {
//        return idSclad;
//    }
//
//    public void setIdSclad(int idSclad) {
//        this.idSclad = idSclad;
//    }
//
//    @Basic
//    @Column(name = "BALL1", nullable = true, precision = 0)
//    private Double ball1;
//
//    public Double getBall1() {
//        return ball1;
//    }
//
//    public void setBall1(Double ball1) {
//        this.ball1 = ball1;
//    }
//
//    @Basic
//    @Column(name = "BALL2", nullable = true, precision = 0)
//    private Double ball2;
//
//    public Double getBall2() {
//        return ball2;
//    }
//
//    public void setBall2(Double ball2) {
//        this.ball2 = ball2;
//    }
//
//    @Basic
//    @Column(name = "BALL3", nullable = true, precision = 0)
//    private Double ball3;
//
//    public Double getBall3() {
//        return ball3;
//    }
//
//    public void setBall3(Double ball3) {
//        this.ball3 = ball3;
//    }
//
//    @Basic
//    @Column(name = "BALL4", nullable = true, precision = 0)
//    private Double ball4;
//
//    public Double getBall4() {
//        return ball4;
//    }
//
//    public void setBall4(Double ball4) {
//        this.ball4 = ball4;
//    }
//
//    @Basic
//    @Column(name = "BALL5", nullable = true, precision = 0)
//    private Double ball5;
//
//    public Double getBall5() {
//        return ball5;
//    }
//
//    public void setBall5(Double ball5) {
//        this.ball5 = ball5;
//    }
//
//    @Basic
//    @Column(name = "DEPARTAM", nullable = true)
//    private Integer departam;
//
//    public Integer getDepartam() {
//        return departam;
//    }
//
//    public void setDepartam(Integer departam) {
//        this.departam = departam;
//    }
//
//    @Basic
//    @Column(name = "NAL_PROD", nullable = true, precision = 0)
//    private Double nalProd;
//
//    public Double getNalProd() {
//        return nalProd;
//    }
//
//    public void setNalProd(Double nalProd) {
//        this.nalProd = nalProd;
//    }
//
//    @Basic
//    @Column(name = "TIP_TOVR", nullable = true, length = 10)
//    private String tipTovr;
//
//    public String getTipTovr() {
//        return tipTovr;
//    }
//
//    public void setTipTovr(String tipTovr) {
//        this.tipTovr = tipTovr;
//    }
//
//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        SclArtcEntity that = (SclArtcEntity) o;
//        return priznValt == that.priznValt && ndsTorgn == that.ndsTorgn && Double.compare(nachKolch, that.nachKolch) == 0 && Double.compare(konKolch, that.konKolch) == 0 && Double.compare(rezKolch, that.rezKolch) == 0 && Double.compare(uchet0C, that.uchet0C) == 0 && Double.compare(uchet0Vl, that.uchet0Vl) == 0 && fixNacen == that.fixNacen && priceList == that.priceList && idSclad == that.idSclad && Objects.equals(codArtic, that.codArtic) && Objects.equals(ngroupTvr, that.ngroupTvr) && Objects.equals(nameArtic, that.nameArtic) && Objects.equals(cenaArtic, that.cenaArtic) && Objects.equals(cenaValt, that.cenaValt) && Objects.equals(codValt, that.codValt) && Objects.equals(ndsArtic, that.ndsArtic) && Objects.equals(edinIzmer, that.edinIzmer) && Objects.equals(vesEdinic, that.vesEdinic) && Objects.equals(ednVUpak, that.ednVUpak) && Objects.equals(minPartia, that.minPartia) && Objects.equals(dlinaArt, that.dlinaArt) && Objects.equals(shirinArt, that.shirinArt) && Objects.equals(vysotaArt, that.vysotaArt) && Objects.equals(razmIzmer, that.razmIzmer) && Objects.equals(schetArt, that.schetArt) && Objects.equals(dop1Artic, that.dop1Artic) && Objects.equals(dop2Artic, that.dop2Artic) && Objects.equals(uchetCena, that.uchetCena) && Objects.equals(uchetValt, that.uchetValt) && Objects.equals(uchetSum, that.uchetSum) && Objects.equals(uchetSmvl, that.uchetSmvl) && Objects.equals(kolSum, that.kolSum) && Objects.equals(ngroupTv2, that.ngroupTv2) && Objects.equals(nal1Art, that.nal1Art) && Objects.equals(nal2Art, that.nal2Art) && Objects.equals(cenaBznal, that.cenaBznal) && Objects.equals(cenaVBzn, that.cenaVBzn) && Objects.equals(ngroupTv3, that.ngroupTv3) && Objects.equals(ngroupTv4, that.ngroupTv4) && Objects.equals(ngroupTv5, that.ngroupTv5) && Objects.equals(ngroupTv6, that.ngroupTv6) && Objects.equals(dop3Artic, that.dop3Artic) && Objects.equals(coefBznal, that.coefBznal) && Objects.equals(okdpArtic, that.okdpArtic) && Objects.equals(minTvrzap, that.minTvrzap) && Objects.equals(maxTvrzap, that.maxTvrzap) && Objects.equals(ball1, that.ball1) && Objects.equals(ball2, that.ball2) && Objects.equals(ball3, that.ball3) && Objects.equals(ball4, that.ball4) && Objects.equals(ball5, that.ball5) && Objects.equals(departam, that.departam) && Objects.equals(nalProd, that.nalProd) && Objects.equals(tipTovr, that.tipTovr);
//    }
//
//    @Override
//    public int hashCode() {
//        return Objects.hash(codArtic, ngroupTvr, nameArtic, cenaArtic, priznValt, cenaValt, codValt, ndsArtic, ndsTorgn, nachKolch, konKolch, rezKolch, edinIzmer, vesEdinic, ednVUpak, minPartia, dlinaArt, shirinArt, vysotaArt, razmIzmer, schetArt, dop1Artic, dop2Artic, uchetCena, uchetValt, uchetSum, uchetSmvl, kolSum, ngroupTv2, uchet0C, nal1Art, nal2Art, uchet0Vl, fixNacen, cenaBznal, cenaVBzn, ngroupTv3, ngroupTv4, ngroupTv5, ngroupTv6, priceList, dop3Artic, coefBznal, okdpArtic, minTvrzap, maxTvrzap, idSclad, ball1, ball2, ball3, ball4, ball5, departam, nalProd, tipTovr);
//    }
//}
