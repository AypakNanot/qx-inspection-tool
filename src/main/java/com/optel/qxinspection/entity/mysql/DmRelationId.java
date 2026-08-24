package com.optel.qxinspection.entity.mysql;

import java.io.Serializable;
import java.util.Objects;

public class DmRelationId implements Serializable {
    private String oid;
    private Integer type;

    public DmRelationId() {}

    public DmRelationId(String oid, Integer type) {
        this.oid = oid;
        this.type = type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DmRelationId that = (DmRelationId) o;
        return Objects.equals(oid, that.oid) && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(oid, type);
    }
}
