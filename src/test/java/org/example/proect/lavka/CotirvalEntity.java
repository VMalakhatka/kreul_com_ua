package org.example.proect.lavka;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.Objects;

@Entity
@jakarta.persistence.Table(name = "COTIRVAL", schema = "dbo", catalog = "Paint_Ua")
@IdClass(CotirvalEntityPK.class)
public class CotirvalEntity {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @jakarta.persistence.Column(name = "DATECOTIR", nullable = false)
    private Timestamp datecotir;

    public Timestamp getDatecotir() {
        return datecotir;
    }

    public void setDatecotir(Timestamp datecotir) {
        this.datecotir = datecotir;
    }

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @jakarta.persistence.Column(name = "CODVALUT", nullable = false, length = 8)
    private String codvalut;

    public String getCodvalut() {
        return codvalut;
    }

    public void setCodvalut(String codvalut) {
        this.codvalut = codvalut;
    }

    @Basic
    @Column(name = "VALCOTIR", nullable = false, precision = 0)
    private double valcotir;

    public double getValcotir() {
        return valcotir;
    }

    public void setValcotir(double valcotir) {
        this.valcotir = valcotir;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CotirvalEntity that = (CotirvalEntity) o;
        return Double.compare(valcotir, that.valcotir) == 0 && Objects.equals(datecotir, that.datecotir) && Objects.equals(codvalut, that.codvalut);
    }

    @Override
    public int hashCode() {
        return Objects.hash(datecotir, codvalut, valcotir);
    }
}
