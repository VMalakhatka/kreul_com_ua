package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.*;

public record FolioProfitScopeSettings(
        @JsonDeserialize(using=VersionDeserializer.class) Long version,
        @JsonDeserialize(contentUsing=WarehouseIdDeserializer.class) List<Integer> kyivWarehouseIds,
        @JsonDeserialize(contentUsing=WarehouseIdDeserializer.class) List<Integer> odesaWarehouseIds,
        @JsonDeserialize(contentUsing=WarehouseIdDeserializer.class) List<Integer> kyivStockWarehouseIds,
        @JsonDeserialize(contentUsing=WarehouseIdDeserializer.class) List<Integer> odesaStockWarehouseIds,
        @JsonDeserialize(contentUsing=GroupIdDeserializer.class) List<String> excludedInventoryGroupIds,
        @JsonDeserialize(contentUsing=GroupIdDeserializer.class) List<String> excludedInventorySubgroupIds) {
    public FolioProfitScopeSettings {
        kyivWarehouseIds=copy(kyivWarehouseIds); odesaWarehouseIds=copy(odesaWarehouseIds);
        kyivStockWarehouseIds=copy(kyivStockWarehouseIds); odesaStockWarehouseIds=copy(odesaStockWarehouseIds);
        excludedInventoryGroupIds=copy(excludedInventoryGroupIds); excludedInventorySubgroupIds=copy(excludedInventorySubgroupIds);
    }
    private static <T> List<T> copy(List<T> values) {
        return values==null?null:Collections.unmodifiableList(new ArrayList<>(values));
    }
    public FolioProfitScopeSettings withVersion(long next) {
        return new FolioProfitScopeSettings(next,kyivWarehouseIds,odesaWarehouseIds,kyivStockWarehouseIds,
                odesaStockWarehouseIds,excludedInventoryGroupIds,excludedInventorySubgroupIds);
    }
    public FolioProfitScopeSettings withStockWarehouses(List<Integer> kyiv,List<Integer> odesa) {
        return new FolioProfitScopeSettings(version,kyivWarehouseIds,odesaWarehouseIds,kyiv,odesa,
                excludedInventoryGroupIds,excludedInventorySubgroupIds);
    }
    public static final class VersionDeserializer extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser p,DeserializationContext context) throws IOException {
            if(p.currentToken()==JsonToken.VALUE_NUMBER_INT) {
                long value=p.getLongValue(); if(value>=0&&value<Long.MAX_VALUE) return value;
            }
            throw JsonMappingException.from(p,"Scope settings version must be a non-negative integer");
        }
    }
    public static final class WarehouseIdDeserializer extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser p,DeserializationContext context) throws IOException {
            if(p.currentToken()==JsonToken.VALUE_NUMBER_INT) return p.getIntValue();
            throw JsonMappingException.from(p,"Warehouse ID must be an integer");
        }
    }
    public static final class GroupIdDeserializer extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser p,DeserializationContext context) throws IOException {
            if(p.currentToken()==JsonToken.VALUE_STRING) return p.getText();
            throw JsonMappingException.from(p,"Group ID must be a string from the scope catalog");
        }
    }
}
