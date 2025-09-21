package org.example.proect.lavka;

import jakarta.persistence.*;

import java.util.Objects;

@Entity
@jakarta.persistence.Table(name = "ALL_CENA", schema = "dbo", catalog = "Paint_Ua")
public class AllCenaEntity {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @jakarta.persistence.Column(name = "ARTIC", nullable = false, length = 20)
    private String artic;

    public String getArtic() {
        return artic;
    }

    public void setArtic(String artic) {
        this.artic = artic;
    }

    @Basic
    @Column(name = "ARTIC_BAZA", nullable = true, length = 20)
    private String articBaza;

    public String getArticBaza() {
        return articBaza;
    }

    public void setArticBaza(String articBaza) {
        this.articBaza = articBaza;
    }

    @Basic
    @Column(name = "PR_BAZA", nullable = true)
    private Integer prBaza;

    public Integer getPrBaza() {
        return prBaza;
    }

    public void setPrBaza(Integer prBaza) {
        this.prBaza = prBaza;
    }

    @Basic
    @Column(name = "CENA_UA", nullable = true, precision = 0)
    private Double cenaUa;

    public Double getCenaUa() {
        return cenaUa;
    }

    public void setCenaUa(Double cenaUa) {
        this.cenaUa = cenaUa;
    }

    @Basic
    @Column(name = "CENA_RUB", nullable = true, precision = 0)
    private Double cenaRub;

    public Double getCenaRub() {
        return cenaRub;
    }

    public void setCenaRub(Double cenaRub) {
        this.cenaRub = cenaRub;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AllCenaEntity that = (AllCenaEntity) o;
        return Objects.equals(artic, that.artic) && Objects.equals(articBaza, that.articBaza) && Objects.equals(prBaza, that.prBaza) && Objects.equals(cenaUa, that.cenaUa) && Objects.equals(cenaRub, that.cenaRub);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artic, articBaza, prBaza, cenaUa, cenaRub);
    }
}
