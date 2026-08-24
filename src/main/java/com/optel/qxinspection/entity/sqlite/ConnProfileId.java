package com.optel.qxinspection.entity.sqlite;

import java.io.Serializable;
import java.util.Objects;

public class ConnProfileId implements Serializable {
    private String scope;
    private String neOid;

    public ConnProfileId() {}

    public ConnProfileId(String scope, String neOid) {
        this.scope = scope;
        this.neOid = neOid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConnProfileId that = (ConnProfileId) o;
        return Objects.equals(scope, that.scope) && Objects.equals(neOid, that.neOid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, neOid);
    }
}
