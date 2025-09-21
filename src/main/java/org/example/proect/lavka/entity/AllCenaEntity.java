//package org.example.proect_lavka;
//
//import jakarta.persistence.*;
//import lombok.Getter;
//import lombok.Setter;
//
//import java.util.Objects;
//
//@Setter
//@Getter
//@Entity
//@jakarta.persistence.Table(name = "ALL_CENA", schema = "dbo", catalog = "Paint_Ua")
//public class AllCenaEntity {
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    @Id
//    @jakarta.persistence.Column(name = "ARTIC", nullable = false, length = 20)
//    private String artic;
//
//    @Basic
//    @Column(name = "ARTIC_BAZA", nullable = true, length = 20)
//    private String articBaza;
//
//    @Basic
//    @Column(name = "PR_BAZA", nullable = true)
//    private Integer prBaza;
//
//    @Basic
//    @Column(name = "CENA_UA", nullable = true, precision = 0)
//    private Double cenaUa;
//
//    @Basic
//    @Column(name = "CENA_RUB", nullable = true, precision = 0)
//    private Double cenaRub;
//
//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        AllCenaEntity that = (AllCenaEntity) o;
//        return Objects.equals(artic, that.artic) && Objects.equals(articBaza, that.articBaza) && Objects.equals(prBaza, that.prBaza) && Objects.equals(cenaUa, that.cenaUa) && Objects.equals(cenaRub, that.cenaRub);
//    }
//
//    @Override
//    public int hashCode() {
//        return Objects.hash(artic, articBaza, prBaza, cenaUa, cenaRub);
//    }
//}
