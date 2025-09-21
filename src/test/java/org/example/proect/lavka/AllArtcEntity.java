package org.example.proect.lavka;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.Objects;

@Entity
@jakarta.persistence.Table(name = "ALL_ARTC", schema = "dbo", catalog = "Paint_Ua")
public class AllArtcEntity {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @jakarta.persistence.Column(name = "COD_ARTIC", nullable = false, length = 20)
    private String codArtic;

    public String getCodArtic() {
        return codArtic;
    }

    public void setCodArtic(String codArtic) {
        this.codArtic = codArtic;
    }

    @Basic
    @Column(name = "COUNTRY", nullable = true, length = 50)
    private String country;

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    @Basic
    @Column(name = "S25", nullable = true, length = 25)
    private String s25;

    public String getS25() {
        return s25;
    }

    public void setS25(String s25) {
        this.s25 = s25;
    }

    @Basic
    @Column(name = "S50", nullable = true, length = 50)
    private String s50;

    public String getS50() {
        return s50;
    }

    public void setS50(String s50) {
        this.s50 = s50;
    }

    @Basic
    @Column(name = "S100", nullable = true, length = 100)
    private String s100;

    public String getS100() {
        return s100;
    }

    public void setS100(String s100) {
        this.s100 = s100;
    }

    @Basic
    @Column(name = "S200", nullable = true, length = 200)
    private String s200;

    public String getS200() {
        return s200;
    }

    public void setS200(String s200) {
        this.s200 = s200;
    }

    @Basic
    @Column(name = "S250", nullable = true, length = 250)
    private String s250;

    public String getS250() {
        return s250;
    }

    public void setS250(String s250) {
        this.s250 = s250;
    }

    @Basic
    @Column(name = "S255", nullable = true, length = 255)
    private String s255;

    public String getS255() {
        return s255;
    }

    public void setS255(String s255) {
        this.s255 = s255;
    }

    @Basic
    @Column(name = "DATE1", nullable = true)
    private Timestamp date1;

    public Timestamp getDate1() {
        return date1;
    }

    public void setDate1(Timestamp date1) {
        this.date1 = date1;
    }

    @Basic
    @Column(name = "DATE2", nullable = true)
    private Timestamp date2;

    public Timestamp getDate2() {
        return date2;
    }

    public void setDate2(Timestamp date2) {
        this.date2 = date2;
    }

    @Basic
    @Column(name = "DESCRIPTION", nullable = true, length = 5000)
    private String description;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Basic
    @Column(name = "PLUS_ARTIC", nullable = false)
    private long plusArtic;

    public long getPlusArtic() {
        return plusArtic;
    }

    public void setPlusArtic(long plusArtic) {
        this.plusArtic = plusArtic;
    }

    @Basic
    @Column(name = "FASOVKA_KALM", nullable = false)
    private int fasovkaKalm;

    public int getFasovkaKalm() {
        return fasovkaKalm;
    }

    public void setFasovkaKalm(int fasovkaKalm) {
        this.fasovkaKalm = fasovkaKalm;
    }

    @Basic
    @Column(name = "PR_CENA", nullable = true)
    private Integer prCena;

    public Integer getPrCena() {
        return prCena;
    }

    public void setPrCena(Integer prCena) {
        this.prCena = prCena;
    }

    @Basic
    @Column(name = "idgr", nullable = true)
    private Integer idgr;

    public Integer getIdgr() {
        return idgr;
    }

    public void setIdgr(Integer idgr) {
        this.idgr = idgr;
    }

    @Basic
    @Column(name = "idgr_add", nullable = true, length = 50)
    private String idgrAdd;

    public String getIdgrAdd() {
        return idgrAdd;
    }

    public void setIdgrAdd(String idgrAdd) {
        this.idgrAdd = idgrAdd;
    }

    @Basic
    @Column(name = "idgr_add1", nullable = true)
    private Integer idgrAdd1;

    public Integer getIdgrAdd1() {
        return idgrAdd1;
    }

    public void setIdgrAdd1(Integer idgrAdd1) {
        this.idgrAdd1 = idgrAdd1;
    }

    @Basic
    @Column(name = "sort_order", nullable = true)
    private Integer sortOrder;

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    @Basic
    @Column(name = "related_ids", nullable = true, length = 100)
    private String relatedIds;

    public String getRelatedIds() {
        return relatedIds;
    }

    public void setRelatedIds(String relatedIds) {
        this.relatedIds = relatedIds;
    }

    @Basic
    @Column(name = "tr_transf", nullable = true, precision = 0)
    private Double trTransf;

    public Double getTrTransf() {
        return trTransf;
    }

    public void setTrTransf(Double trTransf) {
        this.trTransf = trTransf;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        org.example.proect_lavka.entity.AllArtcEntity that = (org.example.proect_lavka.entity.AllArtcEntity) o;
        return plusArtic == that.plusArtic && fasovkaKalm == that.fasovkaKalm && Objects.equals(codArtic, that.codArtic) && Objects.equals(country, that.country) && Objects.equals(s25, that.s25) && Objects.equals(s50, that.s50) && Objects.equals(s100, that.s100) && Objects.equals(s200, that.s200) && Objects.equals(s250, that.s250) && Objects.equals(s255, that.s255) && Objects.equals(date1, that.date1) && Objects.equals(date2, that.date2) && Objects.equals(description, that.description) && Objects.equals(prCena, that.prCena) && Objects.equals(idgr, that.idgr) && Objects.equals(idgrAdd, that.idgrAdd) && Objects.equals(idgrAdd1, that.idgrAdd1) && Objects.equals(sortOrder, that.sortOrder) && Objects.equals(relatedIds, that.relatedIds) && Objects.equals(trTransf, that.trTransf);
    }

    @Override
    public int hashCode() {
        return Objects.hash(codArtic, country, s25, s50, s100, s200, s250, s255, date1, date2, description, plusArtic, fasovkaKalm, prCena, idgr, idgrAdd, idgrAdd1, sortOrder, relatedIds, trTransf);
    }
}
