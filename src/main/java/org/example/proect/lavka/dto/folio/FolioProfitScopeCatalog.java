package org.example.proect.lavka.dto.folio;

import java.util.List;

public record FolioProfitScopeCatalog(List<Warehouse> warehouses,List<Group> groups,List<Subgroup> subgroups) {
    public FolioProfitScopeCatalog {
        warehouses=List.copyOf(warehouses); groups=List.copyOf(groups); subgroups=List.copyOf(subgroups);
    }
    public record Warehouse(int id,String name) {}
    public record Group(String id,String name,List<String> path) {
        public Group {path=List.copyOf(path);}
    }
    public record Subgroup(String id,String groupId,String parentId,String name,int level,List<String> path) {
        public Subgroup {path=List.copyOf(path);}
    }
}
