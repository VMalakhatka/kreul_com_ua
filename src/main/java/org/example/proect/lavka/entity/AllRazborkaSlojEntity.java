//package org.example.proect_lavka;
//
//import jakarta.persistence.*;
//
//import java.util.Objects;
//
//@Entity
//@jakarta.persistence.Table(name = "ALL_RAZBORKA_SLOJ", schema = "dbo", catalog = "Paint_Ua")
//public class AllRazborkaSlojEntity {
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    @Id
//    @jakarta.persistence.Column(name = "Key", nullable = false)
//    private int key;
//
//    public int getKey() {
//        return key;
//    }
//
//    public void setKey(int key) {
//        this.key = key;
//    }
//
//    @Basic
//    @Column(name = "ARTIC_REB", nullable = true, length = 20)
//    private String articReb;
//
//    public String getArticReb() {
//        return articReb;
//    }
//
//    public void setArticReb(String articReb) {
//        this.articReb = articReb;
//    }
//
//    @Basic
//    @Column(name = "ARTIC_ROD", nullable = true, length = 20)
//    private String articRod;
//
//    public String getArticRod() {
//        return articRod;
//    }
//
//    public void setArticRod(String articRod) {
//        this.articRod = articRod;
//    }
//
//    @Basic
//    @Column(name = "KOL_R", nullable = true, precision = 0)
//    private Double kolR;
//
//    public Double getKolR() {
//        return kolR;
//    }
//
//    public void setKolR(Double kolR) {
//        this.kolR = kolR;
//    }
//
//    @Basic
//    @Column(name = "DOL_CEN_UA", nullable = true, precision = 0)
//    private Double dolCenUa;
//
//    public Double getDolCenUa() {
//        return dolCenUa;
//    }
//
//    public void setDolCenUa(Double dolCenUa) {
//        this.dolCenUa = dolCenUa;
//    }
//
//    @Basic
//    @Column(name = "DOL_CEN_RUB", nullable = true, precision = 0)
//    private Double dolCenRub;
//
//    public Double getDolCenRub() {
//        return dolCenRub;
//    }
//
//    public void setDolCenRub(Double dolCenRub) {
//        this.dolCenRub = dolCenRub;
//    }
//
//    @Basic
//    @Column(name = "PR_TRANSPORT", nullable = true)
//    private Integer prTransport;
//
//    public Integer getPrTransport() {
//        return prTransport;
//    }
//
//    public void setPrTransport(Integer prTransport) {
//        this.prTransport = prTransport;
//    }
//
//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        AllRazborkaSlojEntity that = (AllRazborkaSlojEntity) o;
//        return key == that.key && Objects.equals(articReb, that.articReb) && Objects.equals(articRod, that.articRod) && Objects.equals(kolR, that.kolR) && Objects.equals(dolCenUa, that.dolCenUa) && Objects.equals(dolCenRub, that.dolCenRub) && Objects.equals(prTransport, that.prTransport);
//    }
//
//    @Override
//    public int hashCode() {
//        return Objects.hash(key, articReb, articRod, kolR, dolCenUa, dolCenRub, prTransport);
//    }
//}
