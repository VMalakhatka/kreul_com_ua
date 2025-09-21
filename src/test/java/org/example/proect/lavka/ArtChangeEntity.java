package org.example.proect.lavka;

import jakarta.persistence.Basic;
import jakarta.persistence.Entity;

import java.sql.Timestamp;
import java.util.Objects;

@Entity
@jakarta.persistence.Table(name = "ART_CHANGE", schema = "dbo", catalog = "Paint_Ua")
public class ArtChangeEntity {
    @Basic
    @jakarta.persistence.Column(name = "COD_ARTIC", nullable = false, length = 20)
    private String codArtic;

    public String getCodArtic() {
        return codArtic;
    }

    public void setCodArtic(String codArtic) {
        this.codArtic = codArtic;
    }

    @Basic
    @jakarta.persistence.Column(name = "ID_SCLAD", nullable = true)
    private Integer idSclad;

    public Integer getIdSclad() {
        return idSclad;
    }

    public void setIdSclad(Integer idSclad) {
        this.idSclad = idSclad;
    }

    @Basic
    @jakarta.persistence.Column(name = "NGROUP_TVR", nullable = true, length = 30)
    private String ngroupTvr;

    public String getNgroupTvr() {
        return ngroupTvr;
    }

    public void setNgroupTvr(String ngroupTvr) {
        this.ngroupTvr = ngroupTvr;
    }

    @Basic
    @jakarta.persistence.Column(name = "NAME_ARTIC", nullable = true, length = 200)
    private String nameArtic;

    public String getNameArtic() {
        return nameArtic;
    }

    public void setNameArtic(String nameArtic) {
        this.nameArtic = nameArtic;
    }

    @Basic
    @jakarta.persistence.Column(name = "CENA_ARTIC", nullable = true, precision = 0)
    private Double cenaArtic;

    public Double getCenaArtic() {
        return cenaArtic;
    }

    public void setCenaArtic(Double cenaArtic) {
        this.cenaArtic = cenaArtic;
    }

    @Basic
    @jakarta.persistence.Column(name = "PRIZN_VALT", nullable = false)
    private boolean priznValt;

    public boolean isPriznValt() {
        return priznValt;
    }

    public void setPriznValt(boolean priznValt) {
        this.priznValt = priznValt;
    }

    @Basic
    @jakarta.persistence.Column(name = "CENA_VALT", nullable = true, precision = 0)
    private Double cenaValt;

    public Double getCenaValt() {
        return cenaValt;
    }

    public void setCenaValt(Double cenaValt) {
        this.cenaValt = cenaValt;
    }

    @Basic
    @jakarta.persistence.Column(name = "COD_VALT", nullable = true, length = 4)
    private String codValt;

    public String getCodValt() {
        return codValt;
    }

    public void setCodValt(String codValt) {
        this.codValt = codValt;
    }

    @Basic
    @jakarta.persistence.Column(name = "NDS_ARTIC", nullable = true, precision = 0)
    private Double ndsArtic;

    public Double getNdsArtic() {
        return ndsArtic;
    }

    public void setNdsArtic(Double ndsArtic) {
        this.ndsArtic = ndsArtic;
    }

    @Basic
    @jakarta.persistence.Column(name = "NDS_TORGN", nullable = false)
    private boolean ndsTorgn;

    public boolean isNdsTorgn() {
        return ndsTorgn;
    }

    public void setNdsTorgn(boolean ndsTorgn) {
        this.ndsTorgn = ndsTorgn;
    }

    @Basic
    @jakarta.persistence.Column(name = "NACH_KOLCH", nullable = true, precision = 0)
    private Double nachKolch;

    public Double getNachKolch() {
        return nachKolch;
    }

    public void setNachKolch(Double nachKolch) {
        this.nachKolch = nachKolch;
    }

    @Basic
    @jakarta.persistence.Column(name = "EDIN_IZMER", nullable = true, length = 10)
    private String edinIzmer;

    public String getEdinIzmer() {
        return edinIzmer;
    }

    public void setEdinIzmer(String edinIzmer) {
        this.edinIzmer = edinIzmer;
    }

    @Basic
    @jakarta.persistence.Column(name = "VES_EDINIC", nullable = true, precision = 0)
    private Double vesEdinic;

    public Double getVesEdinic() {
        return vesEdinic;
    }

    public void setVesEdinic(Double vesEdinic) {
        this.vesEdinic = vesEdinic;
    }

    @Basic
    @jakarta.persistence.Column(name = "EDN_V_UPAK", nullable = true, precision = 0)
    private Double ednVUpak;

    public Double getEdnVUpak() {
        return ednVUpak;
    }

    public void setEdnVUpak(Double ednVUpak) {
        this.ednVUpak = ednVUpak;
    }

    @Basic
    @jakarta.persistence.Column(name = "MIN_PARTIA", nullable = true, precision = 0)
    private Double minPartia;

    public Double getMinPartia() {
        return minPartia;
    }

    public void setMinPartia(Double minPartia) {
        this.minPartia = minPartia;
    }

    @Basic
    @jakarta.persistence.Column(name = "DLINA_ART", nullable = true, precision = 0)
    private Double dlinaArt;

    public Double getDlinaArt() {
        return dlinaArt;
    }

    public void setDlinaArt(Double dlinaArt) {
        this.dlinaArt = dlinaArt;
    }

    @Basic
    @jakarta.persistence.Column(name = "SHIRIN_ART", nullable = true, precision = 0)
    private Double shirinArt;

    public Double getShirinArt() {
        return shirinArt;
    }

    public void setShirinArt(Double shirinArt) {
        this.shirinArt = shirinArt;
    }

    @Basic
    @jakarta.persistence.Column(name = "VYSOTA_ART", nullable = true, precision = 0)
    private Double vysotaArt;

    public Double getVysotaArt() {
        return vysotaArt;
    }

    public void setVysotaArt(Double vysotaArt) {
        this.vysotaArt = vysotaArt;
    }

    @Basic
    @jakarta.persistence.Column(name = "RAZM_IZMER", nullable = true, length = 5)
    private String razmIzmer;

    public String getRazmIzmer() {
        return razmIzmer;
    }

    public void setRazmIzmer(String razmIzmer) {
        this.razmIzmer = razmIzmer;
    }

    @Basic
    @jakarta.persistence.Column(name = "SCHET_ART", nullable = true, length = 6)
    private String schetArt;

    public String getSchetArt() {
        return schetArt;
    }

    public void setSchetArt(String schetArt) {
        this.schetArt = schetArt;
    }

    @Basic
    @jakarta.persistence.Column(name = "DOP1_ARTIC", nullable = true, length = 60)
    private String dop1Artic;

    public String getDop1Artic() {
        return dop1Artic;
    }

    public void setDop1Artic(String dop1Artic) {
        this.dop1Artic = dop1Artic;
    }

    @Basic
    @jakarta.persistence.Column(name = "DOP2_ARTIC", nullable = true, length = 50)
    private String dop2Artic;

    public String getDop2Artic() {
        return dop2Artic;
    }

    public void setDop2Artic(String dop2Artic) {
        this.dop2Artic = dop2Artic;
    }

    @Basic
    @jakarta.persistence.Column(name = "UCHET_CENA", nullable = true, precision = 0)
    private Double uchetCena;

    public Double getUchetCena() {
        return uchetCena;
    }

    public void setUchetCena(Double uchetCena) {
        this.uchetCena = uchetCena;
    }

    @Basic
    @jakarta.persistence.Column(name = "UCHET_VALT", nullable = true, precision = 0)
    private Double uchetValt;

    public Double getUchetValt() {
        return uchetValt;
    }

    public void setUchetValt(Double uchetValt) {
        this.uchetValt = uchetValt;
    }

    @Basic
    @jakarta.persistence.Column(name = "NGROUP_TV2", nullable = true, length = 30)
    private String ngroupTv2;

    public String getNgroupTv2() {
        return ngroupTv2;
    }

    public void setNgroupTv2(String ngroupTv2) {
        this.ngroupTv2 = ngroupTv2;
    }

    @Basic
    @jakarta.persistence.Column(name = "UCHET_0_C", nullable = true, precision = 0)
    private Double uchet0C;

    public Double getUchet0C() {
        return uchet0C;
    }

    public void setUchet0C(Double uchet0C) {
        this.uchet0C = uchet0C;
    }

    @Basic
    @jakarta.persistence.Column(name = "NAL1_ART", nullable = true, precision = 0)
    private Double nal1Art;

    public Double getNal1Art() {
        return nal1Art;
    }

    public void setNal1Art(Double nal1Art) {
        this.nal1Art = nal1Art;
    }

    @Basic
    @jakarta.persistence.Column(name = "NAL2_ART", nullable = true, precision = 0)
    private Double nal2Art;

    public Double getNal2Art() {
        return nal2Art;
    }

    public void setNal2Art(Double nal2Art) {
        this.nal2Art = nal2Art;
    }

    @Basic
    @jakarta.persistence.Column(name = "UCHET_0_VL", nullable = true, precision = 0)
    private Double uchet0Vl;

    public Double getUchet0Vl() {
        return uchet0Vl;
    }

    public void setUchet0Vl(Double uchet0Vl) {
        this.uchet0Vl = uchet0Vl;
    }

    @Basic
    @jakarta.persistence.Column(name = "FIX_NACEN", nullable = false)
    private boolean fixNacen;

    public boolean isFixNacen() {
        return fixNacen;
    }

    public void setFixNacen(boolean fixNacen) {
        this.fixNacen = fixNacen;
    }

    @Basic
    @jakarta.persistence.Column(name = "CENA_BZNAL", nullable = true, precision = 0)
    private Double cenaBznal;

    public Double getCenaBznal() {
        return cenaBznal;
    }

    public void setCenaBznal(Double cenaBznal) {
        this.cenaBznal = cenaBznal;
    }

    @Basic
    @jakarta.persistence.Column(name = "CENA_V_BZN", nullable = true, precision = 0)
    private Double cenaVBzn;

    public Double getCenaVBzn() {
        return cenaVBzn;
    }

    public void setCenaVBzn(Double cenaVBzn) {
        this.cenaVBzn = cenaVBzn;
    }

    @Basic
    @jakarta.persistence.Column(name = "NGROUP_TV3", nullable = true, length = 30)
    private String ngroupTv3;

    public String getNgroupTv3() {
        return ngroupTv3;
    }

    public void setNgroupTv3(String ngroupTv3) {
        this.ngroupTv3 = ngroupTv3;
    }

    @Basic
    @jakarta.persistence.Column(name = "NGROUP_TV4", nullable = true, length = 30)
    private String ngroupTv4;

    public String getNgroupTv4() {
        return ngroupTv4;
    }

    public void setNgroupTv4(String ngroupTv4) {
        this.ngroupTv4 = ngroupTv4;
    }

    @Basic
    @jakarta.persistence.Column(name = "NGROUP_TV5", nullable = true, length = 30)
    private String ngroupTv5;

    public String getNgroupTv5() {
        return ngroupTv5;
    }

    public void setNgroupTv5(String ngroupTv5) {
        this.ngroupTv5 = ngroupTv5;
    }

    @Basic
    @jakarta.persistence.Column(name = "NGROUP_TV6", nullable = true, length = 30)
    private String ngroupTv6;

    public String getNgroupTv6() {
        return ngroupTv6;
    }

    public void setNgroupTv6(String ngroupTv6) {
        this.ngroupTv6 = ngroupTv6;
    }

    @Basic
    @jakarta.persistence.Column(name = "PRICE_LIST", nullable = false)
    private boolean priceList;

    public boolean isPriceList() {
        return priceList;
    }

    public void setPriceList(boolean priceList) {
        this.priceList = priceList;
    }

    @Basic
    @jakarta.persistence.Column(name = "DOP3_ARTIC", nullable = true, length = 255)
    private String dop3Artic;

    public String getDop3Artic() {
        return dop3Artic;
    }

    public void setDop3Artic(String dop3Artic) {
        this.dop3Artic = dop3Artic;
    }

    @Basic
    @jakarta.persistence.Column(name = "COEF_BZNAL", nullable = true, precision = 0)
    private Double coefBznal;

    public Double getCoefBznal() {
        return coefBznal;
    }

    public void setCoefBznal(Double coefBznal) {
        this.coefBznal = coefBznal;
    }

    @Basic
    @jakarta.persistence.Column(name = "OKDP_ARTIC", nullable = true, length = 20)
    private String okdpArtic;

    public String getOkdpArtic() {
        return okdpArtic;
    }

    public void setOkdpArtic(String okdpArtic) {
        this.okdpArtic = okdpArtic;
    }

    @Basic
    @jakarta.persistence.Column(name = "MIN_TVRZAP", nullable = true, precision = 0)
    private Double minTvrzap;

    public Double getMinTvrzap() {
        return minTvrzap;
    }

    public void setMinTvrzap(Double minTvrzap) {
        this.minTvrzap = minTvrzap;
    }

    @Basic
    @jakarta.persistence.Column(name = "MAX_TVRZAP", nullable = true, precision = 0)
    private Double maxTvrzap;

    public Double getMaxTvrzap() {
        return maxTvrzap;
    }

    public void setMaxTvrzap(Double maxTvrzap) {
        this.maxTvrzap = maxTvrzap;
    }

    @Basic
    @jakarta.persistence.Column(name = "test_art", nullable = false)
    private boolean testArt;

    public boolean isTestArt() {
        return testArt;
    }

    public void setTestArt(boolean testArt) {
        this.testArt = testArt;
    }

    @Basic
    @jakarta.persistence.Column(name = "skip_test", nullable = false)
    private boolean skipTest;

    public boolean isSkipTest() {
        return skipTest;
    }

    public void setSkipTest(boolean skipTest) {
        this.skipTest = skipTest;
    }

    @Basic
    @jakarta.persistence.Column(name = "old_artic", nullable = true, length = 20)
    private String oldArtic;

    public String getOldArtic() {
        return oldArtic;
    }

    public void setOldArtic(String oldArtic) {
        this.oldArtic = oldArtic;
    }

    @Basic
    @jakarta.persistence.Column(name = "old_sclad", nullable = true)
    private Integer oldSclad;

    public Integer getOldSclad() {
        return oldSclad;
    }

    public void setOldSclad(Integer oldSclad) {
        this.oldSclad = oldSclad;
    }

    @Basic
    @jakarta.persistence.Column(name = "recalc_uch", nullable = false)
    private boolean recalcUch;

    public boolean isRecalcUch() {
        return recalcUch;
    }

    public void setRecalcUch(boolean recalcUch) {
        this.recalcUch = recalcUch;
    }

    @Basic
    @jakarta.persistence.Column(name = "BALL1", nullable = true, precision = 0)
    private Double ball1;

    public Double getBall1() {
        return ball1;
    }

    public void setBall1(Double ball1) {
        this.ball1 = ball1;
    }

    @Basic
    @jakarta.persistence.Column(name = "BALL2", nullable = true, precision = 0)
    private Double ball2;

    public Double getBall2() {
        return ball2;
    }

    public void setBall2(Double ball2) {
        this.ball2 = ball2;
    }

    @Basic
    @jakarta.persistence.Column(name = "BALL3", nullable = true, precision = 0)
    private Double ball3;

    public Double getBall3() {
        return ball3;
    }

    public void setBall3(Double ball3) {
        this.ball3 = ball3;
    }

    @Basic
    @jakarta.persistence.Column(name = "BALL4", nullable = true, precision = 0)
    private Double ball4;

    public Double getBall4() {
        return ball4;
    }

    public void setBall4(Double ball4) {
        this.ball4 = ball4;
    }

    @Basic
    @jakarta.persistence.Column(name = "BALL5", nullable = true, precision = 0)
    private Double ball5;

    public Double getBall5() {
        return ball5;
    }

    public void setBall5(Double ball5) {
        this.ball5 = ball5;
    }

    @Basic
    @jakarta.persistence.Column(name = "departam", nullable = true)
    private Integer departam;

    public Integer getDepartam() {
        return departam;
    }

    public void setDepartam(Integer departam) {
        this.departam = departam;
    }

    @Basic
    @jakarta.persistence.Column(name = "barcode", nullable = true, length = 20)
    private String barcode;

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    @Basic
    @jakarta.persistence.Column(name = "skip_bar", nullable = false)
    private boolean skipBar;

    public boolean isSkipBar() {
        return skipBar;
    }

    public void setSkipBar(boolean skipBar) {
        this.skipBar = skipBar;
    }

    @Basic
    @jakarta.persistence.Column(name = "nal_prod", nullable = true, precision = 0)
    private Double nalProd;

    public Double getNalProd() {
        return nalProd;
    }

    public void setNalProd(Double nalProd) {
        this.nalProd = nalProd;
    }

    @Basic
    @jakarta.persistence.Column(name = "all_scl", nullable = false)
    private boolean allScl;

    public boolean isAllScl() {
        return allScl;
    }

    public void setAllScl(boolean allScl) {
        this.allScl = allScl;
    }

    @Basic
    @jakarta.persistence.Column(name = "country", nullable = true, length = 50)
    private String country;

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    @Basic
    @jakarta.persistence.Column(name = "s25", nullable = true, length = 25)
    private String s25;

    public String getS25() {
        return s25;
    }

    public void setS25(String s25) {
        this.s25 = s25;
    }

    @Basic
    @jakarta.persistence.Column(name = "s50", nullable = true, length = 50)
    private String s50;

    public String getS50() {
        return s50;
    }

    public void setS50(String s50) {
        this.s50 = s50;
    }

    @Basic
    @jakarta.persistence.Column(name = "s100", nullable = true, length = 100)
    private String s100;

    public String getS100() {
        return s100;
    }

    public void setS100(String s100) {
        this.s100 = s100;
    }

    @Basic
    @jakarta.persistence.Column(name = "s200", nullable = true, length = 200)
    private String s200;

    public String getS200() {
        return s200;
    }

    public void setS200(String s200) {
        this.s200 = s200;
    }

    @Basic
    @jakarta.persistence.Column(name = "OPER", nullable = true)
    private Integer oper;

    public Integer getOper() {
        return oper;
    }

    public void setOper(Integer oper) {
        this.oper = oper;
    }

    @Basic
    @jakarta.persistence.Column(name = "S250", nullable = true, length = 250)
    private String s250;

    public String getS250() {
        return s250;
    }

    public void setS250(String s250) {
        this.s250 = s250;
    }

    @Basic
    @jakarta.persistence.Column(name = "S255", nullable = true, length = 255)
    private String s255;

    public String getS255() {
        return s255;
    }

    public void setS255(String s255) {
        this.s255 = s255;
    }

    @Basic
    @jakarta.persistence.Column(name = "DATE1", nullable = true)
    private Timestamp date1;

    public Timestamp getDate1() {
        return date1;
    }

    public void setDate1(Timestamp date1) {
        this.date1 = date1;
    }

    @Basic
    @jakarta.persistence.Column(name = "DATE2", nullable = true)
    private Timestamp date2;

    public Timestamp getDate2() {
        return date2;
    }

    public void setDate2(Timestamp date2) {
        this.date2 = date2;
    }

    @Basic
    @jakarta.persistence.Column(name = "TIP_TOVR", nullable = true, length = 10)
    private String tipTovr;

    public String getTipTovr() {
        return tipTovr;
    }

    public void setTipTovr(String tipTovr) {
        this.tipTovr = tipTovr;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArtChangeEntity that = (ArtChangeEntity) o;
        return priznValt == that.priznValt && ndsTorgn == that.ndsTorgn && fixNacen == that.fixNacen && priceList == that.priceList && testArt == that.testArt && skipTest == that.skipTest && recalcUch == that.recalcUch && skipBar == that.skipBar && allScl == that.allScl && Objects.equals(codArtic, that.codArtic) && Objects.equals(idSclad, that.idSclad) && Objects.equals(ngroupTvr, that.ngroupTvr) && Objects.equals(nameArtic, that.nameArtic) && Objects.equals(cenaArtic, that.cenaArtic) && Objects.equals(cenaValt, that.cenaValt) && Objects.equals(codValt, that.codValt) && Objects.equals(ndsArtic, that.ndsArtic) && Objects.equals(nachKolch, that.nachKolch) && Objects.equals(edinIzmer, that.edinIzmer) && Objects.equals(vesEdinic, that.vesEdinic) && Objects.equals(ednVUpak, that.ednVUpak) && Objects.equals(minPartia, that.minPartia) && Objects.equals(dlinaArt, that.dlinaArt) && Objects.equals(shirinArt, that.shirinArt) && Objects.equals(vysotaArt, that.vysotaArt) && Objects.equals(razmIzmer, that.razmIzmer) && Objects.equals(schetArt, that.schetArt) && Objects.equals(dop1Artic, that.dop1Artic) && Objects.equals(dop2Artic, that.dop2Artic) && Objects.equals(uchetCena, that.uchetCena) && Objects.equals(uchetValt, that.uchetValt) && Objects.equals(ngroupTv2, that.ngroupTv2) && Objects.equals(uchet0C, that.uchet0C) && Objects.equals(nal1Art, that.nal1Art) && Objects.equals(nal2Art, that.nal2Art) && Objects.equals(uchet0Vl, that.uchet0Vl) && Objects.equals(cenaBznal, that.cenaBznal) && Objects.equals(cenaVBzn, that.cenaVBzn) && Objects.equals(ngroupTv3, that.ngroupTv3) && Objects.equals(ngroupTv4, that.ngroupTv4) && Objects.equals(ngroupTv5, that.ngroupTv5) && Objects.equals(ngroupTv6, that.ngroupTv6) && Objects.equals(dop3Artic, that.dop3Artic) && Objects.equals(coefBznal, that.coefBznal) && Objects.equals(okdpArtic, that.okdpArtic) && Objects.equals(minTvrzap, that.minTvrzap) && Objects.equals(maxTvrzap, that.maxTvrzap) && Objects.equals(oldArtic, that.oldArtic) && Objects.equals(oldSclad, that.oldSclad) && Objects.equals(ball1, that.ball1) && Objects.equals(ball2, that.ball2) && Objects.equals(ball3, that.ball3) && Objects.equals(ball4, that.ball4) && Objects.equals(ball5, that.ball5) && Objects.equals(departam, that.departam) && Objects.equals(barcode, that.barcode) && Objects.equals(nalProd, that.nalProd) && Objects.equals(country, that.country) && Objects.equals(s25, that.s25) && Objects.equals(s50, that.s50) && Objects.equals(s100, that.s100) && Objects.equals(s200, that.s200) && Objects.equals(oper, that.oper) && Objects.equals(s250, that.s250) && Objects.equals(s255, that.s255) && Objects.equals(date1, that.date1) && Objects.equals(date2, that.date2) && Objects.equals(tipTovr, that.tipTovr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(codArtic, idSclad, ngroupTvr, nameArtic, cenaArtic, priznValt, cenaValt, codValt, ndsArtic, ndsTorgn, nachKolch, edinIzmer, vesEdinic, ednVUpak, minPartia, dlinaArt, shirinArt, vysotaArt, razmIzmer, schetArt, dop1Artic, dop2Artic, uchetCena, uchetValt, ngroupTv2, uchet0C, nal1Art, nal2Art, uchet0Vl, fixNacen, cenaBznal, cenaVBzn, ngroupTv3, ngroupTv4, ngroupTv5, ngroupTv6, priceList, dop3Artic, coefBznal, okdpArtic, minTvrzap, maxTvrzap, testArt, skipTest, oldArtic, oldSclad, recalcUch, ball1, ball2, ball3, ball4, ball5, departam, barcode, skipBar, nalProd, allScl, country, s25, s50, s100, s200, oper, s250, s255, date1, date2, tipTovr);
    }
}
