//package org.example.proect_lavka;
//
//import jakarta.persistence.*;
//
//import java.util.Objects;
//
//@Entity
//@jakarta.persistence.Table(name = "ALL_RAZBORKA", schema = "dbo", catalog = "Paint_Ua")
//public class AllRazborkaEntity {
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
//    @Column(name = "ARTIC", nullable = true)
//    private Long artic;
//
//    public Long getArtic() {
//        return artic;
//    }
//
//    public void setArtic(Long artic) {
//        this.artic = artic;
//    }
//
//    @Basic
//    @Column(name = "ARTIC_R", nullable = true)
//    private Long articR;
//
//    public Long getArticR() {
//        return articR;
//    }
//
//    public void setArticR(Long articR) {
//        this.articR = articR;
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
//    @Column(name = "LEVEL", nullable = true)
//    private Integer level;
//
//    public Integer getLevel() {
//        return level;
//    }
//
//    public void setLevel(Integer level) {
//        this.level = level;
//    }
//
//    @Basic
//    @Column(name = "ART", nullable = true, length = 20)
//    private String art;
//
//    public String getArt() {
//        return art;
//    }
//
//    public void setArt(String art) {
//        this.art = art;
//    }
//
//    @Basic
//    @Column(name = "ART_R", nullable = true, length = 20)
//    private String artR;
//
//    public String getArtR() {
//        return artR;
//    }
//
//    public void setArtR(String artR) {
//        this.artR = artR;
//    }
//
//    @Basic
//    @Column(name = "COD_VALT_UA", nullable = true)
//    private Integer codValtUa;
//
//    public Integer getCodValtUa() {
//        return codValtUa;
//    }
//
//    public void setCodValtUa(Integer codValtUa) {
//        this.codValtUa = codValtUa;
//    }
//
//    @Basic
//    @Column(name = "CENA_UA", nullable = true, precision = 0)
//    private Double cenaUa;
//
//    public Double getCenaUa() {
//        return cenaUa;
//    }
//
//    public void setCenaUa(Double cenaUa) {
//        this.cenaUa = cenaUa;
//    }
//
//    @Basic
//    @Column(name = "TRANSPORT_UA", nullable = true, precision = 0)
//    private Double transportUa;
//
//    public Double getTransportUa() {
//        return transportUa;
//    }
//
//    public void setTransportUa(Double transportUa) {
//        this.transportUa = transportUa;
//    }
//
//    @Basic
//    @Column(name = "COD_VALT_RUS", nullable = true)
//    private Integer codValtRus;
//
//    public Integer getCodValtRus() {
//        return codValtRus;
//    }
//
//    public void setCodValtRus(Integer codValtRus) {
//        this.codValtRus = codValtRus;
//    }
//
//    @Basic
//    @Column(name = "CENA_RUS", nullable = true, precision = 0)
//    private Double cenaRus;
//
//    public Double getCenaRus() {
//        return cenaRus;
//    }
//
//    public void setCenaRus(Double cenaRus) {
//        this.cenaRus = cenaRus;
//    }
//
//    @Basic
//    @Column(name = "TRANSPORT_RUS", nullable = true, precision = 0)
//    private Double transportRus;
//
//    public Double getTransportRus() {
//        return transportRus;
//    }
//
//    public void setTransportRus(Double transportRus) {
//        this.transportRus = transportRus;
//    }
//
//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        AllRazborkaEntity that = (AllRazborkaEntity) o;
//        return key == that.key && Objects.equals(artic, that.artic) && Objects.equals(articR, that.articR) && Objects.equals(kolR, that.kolR) && Objects.equals(level, that.level) && Objects.equals(art, that.art) && Objects.equals(artR, that.artR) && Objects.equals(codValtUa, that.codValtUa) && Objects.equals(cenaUa, that.cenaUa) && Objects.equals(transportUa, that.transportUa) && Objects.equals(codValtRus, that.codValtRus) && Objects.equals(cenaRus, that.cenaRus) && Objects.equals(transportRus, that.transportRus);
//    }
//
//    @Override
//    public int hashCode() {
//        return Objects.hash(key, artic, articR, kolR, level, art, artR, codValtUa, cenaUa, transportUa, codValtRus, cenaRus, transportRus);
//    }
//}
