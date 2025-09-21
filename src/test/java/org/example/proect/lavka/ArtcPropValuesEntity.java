package org.example.proect.lavka;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.Objects;

@Entity
@jakarta.persistence.Table(name = "ArtcPropValues", schema = "dbo", catalog = "Paint_Ua")
public class ArtcPropValuesEntity {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @jakarta.persistence.Column(name = "ID", nullable = false)
    private int id;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Basic
    @Column(name = "PROPCODE", nullable = false)
    private int propcode;

    public int getPropcode() {
        return propcode;
    }

    public void setPropcode(int propcode) {
        this.propcode = propcode;
    }

    @Basic
    @Column(name = "COD_ARTIC", nullable = false, length = 20)
    private String codArtic;

    public String getCodArtic() {
        return codArtic;
    }

    public void setCodArtic(String codArtic) {
        this.codArtic = codArtic;
    }

    @Basic
    @Column(name = "ValueInt", nullable = true)
    private Integer valueInt;

    public Integer getValueInt() {
        return valueInt;
    }

    public void setValueInt(Integer valueInt) {
        this.valueInt = valueInt;
    }

    @Basic
    @Column(name = "ValueFloat", nullable = true, precision = 0)
    private Double valueFloat;

    public Double getValueFloat() {
        return valueFloat;
    }

    public void setValueFloat(Double valueFloat) {
        this.valueFloat = valueFloat;
    }

    @Basic
    @Column(name = "ValueChar", nullable = true, length = 64)
    private String valueChar;

    public String getValueChar() {
        return valueChar;
    }

    public void setValueChar(String valueChar) {
        this.valueChar = valueChar;
    }

    @Basic
    @Column(name = "ValueDate", nullable = true)
    private Timestamp valueDate;

    public Timestamp getValueDate() {
        return valueDate;
    }

    public void setValueDate(Timestamp valueDate) {
        this.valueDate = valueDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArtcPropValuesEntity that = (ArtcPropValuesEntity) o;
        return id == that.id && propcode == that.propcode && Objects.equals(codArtic, that.codArtic) && Objects.equals(valueInt, that.valueInt) && Objects.equals(valueFloat, that.valueFloat) && Objects.equals(valueChar, that.valueChar) && Objects.equals(valueDate, that.valueDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, propcode, codArtic, valueInt, valueFloat, valueChar, valueDate);
    }
}
