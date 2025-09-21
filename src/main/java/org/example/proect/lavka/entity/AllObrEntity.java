//package org.example.proect_lavka;
//
//import jakarta.persistence.*;
//
//import java.util.Objects;
//
//@Entity
//@jakarta.persistence.Table(name = "ALL_OBR", schema = "dbo", catalog = "Paint_Ua")
//public class AllObrEntity {
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
//    @Column(name = "OBR", nullable = true)
//    private Long obr;
//
//    public Long getObr() {
//        return obr;
//    }
//
//    public void setObr(Long obr) {
//        this.obr = obr;
//    }
//
//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        AllObrEntity that = (AllObrEntity) o;
//        return key == that.key && Objects.equals(artic, that.artic) && Objects.equals(obr, that.obr);
//    }
//
//    @Override
//    public int hashCode() {
//        return Objects.hash(key, artic, obr);
//    }
//}
