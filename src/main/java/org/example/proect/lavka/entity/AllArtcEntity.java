//package org.example.proect_lavka.entity;
//
//import jakarta.persistence.*;
//import lombok.Getter;
//import lombok.Setter;
//
//import java.sql.Timestamp;
//import java.util.Objects;
//
//@Setter
//@Getter
//@Entity
////@jakarta.persistence.Table(name = "ALL_ARTC", schema = "dbo", catalog = "Paint_Ua")
//@Table(name = "ALL_ARTC")
////@Table(name = "ALL_ARTC", schema = "dbo")
//public class AllArtcEntity {
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    @Id
//    @jakarta.persistence.Column(name = "COD_ARTIC", nullable = false, length = 20)
//    private String codArtic;
//
//    @Basic
//    @Column(name = "COUNTRY", nullable = true, length = 50)
//    private String country;
//
//    @Basic
//    @Column(name = "S25", nullable = true, length = 25)
//    private String s25;
//
//    @Basic
//    @Column(name = "S50", nullable = true, length = 50)
//    private String s50;
//
//    @Basic
//    @Column(name = "S100", nullable = true, length = 100)
//    private String s100;
//
//    @Basic
//    @Column(name = "S200", nullable = true, length = 200)
//    private String s200;
//
//    @Basic
//    @Column(name = "S250", nullable = true, length = 250)
//    private String s250;
//
//    @Basic
//    @Column(name = "S255", nullable = true, length = 255)
//    private String s255;
//
//    @Basic
//    @Column(name = "DATE1", nullable = true)
//    private Timestamp date1;
//
//    @Basic
//    @Column(name = "DATE2", nullable = true)
//    private Timestamp date2;
//
//    @Basic
//    @Column(name = "DESCRIPTION", nullable = true, length = 5000)
//    private String description;
//
//    @Basic
//    @Column(name = "PLUS_ARTIC", nullable = false)
//    private long plusArtic;
//
//    @Basic
//    @Column(name = "FASOVKA_KALM", nullable = false)
//    private int fasovkaKalm;
//
//    @Basic
//    @Column(name = "PR_CENA", nullable = true)
//    private Integer prCena;
//
//    @Basic
//    @Column(name = "idgr", nullable = true)
//    private Integer idgr;
//
//    @Basic
//    @Column(name = "idgr_add", nullable = true, length = 50)
//    private String idgrAdd;
//
//    @Basic
//    @Column(name = "idgr_add1", nullable = true)
//    private Integer idgrAdd1;
//
//    @Basic
//    @Column(name = "sort_order", nullable = true)
//    private Integer sortOrder;
//
//    @Basic
//    @Column(name = "related_ids", nullable = true, length = 100)
//    private String relatedIds;
//
//    @Basic
//    @Column(name = "tr_transf", nullable = true, precision = 0)
//    private Double trTransf;
//
//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        AllArtcEntity that = (AllArtcEntity) o;
//        return plusArtic == that.plusArtic && fasovkaKalm == that.fasovkaKalm && Objects.equals(codArtic, that.codArtic) && Objects.equals(country, that.country) && Objects.equals(s25, that.s25) && Objects.equals(s50, that.s50) && Objects.equals(s100, that.s100) && Objects.equals(s200, that.s200) && Objects.equals(s250, that.s250) && Objects.equals(s255, that.s255) && Objects.equals(date1, that.date1) && Objects.equals(date2, that.date2) && Objects.equals(description, that.description) && Objects.equals(prCena, that.prCena) && Objects.equals(idgr, that.idgr) && Objects.equals(idgrAdd, that.idgrAdd) && Objects.equals(idgrAdd1, that.idgrAdd1) && Objects.equals(sortOrder, that.sortOrder) && Objects.equals(relatedIds, that.relatedIds) && Objects.equals(trTransf, that.trTransf);
//    }
//
//    @Override
//    public int hashCode() {
//        return Objects.hash(codArtic, country, s25, s50, s100, s200, s250, s255, date1, date2, description, plusArtic, fasovkaKalm, prCena, idgr, idgrAdd, idgrAdd1, sortOrder, relatedIds, trTransf);
//    }
//}
