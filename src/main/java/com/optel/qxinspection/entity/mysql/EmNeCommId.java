package com.optel.qxinspection.entity.mysql;

import java.io.Serializable;
import java.util.Objects;

/**
 * EmNeComm复合主键
 */
public class EmNeCommId implements Serializable {

    private String oid;
    private String ipAddr;

    public EmNeCommId() {}

    public EmNeCommId(String oid, String ipAddr) {
        this.oid = oid;
        this.ipAddr = ipAddr;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmNeCommId that = (EmNeCommId) o;
        return Objects.equals(oid, that.oid) && Objects.equals(ipAddr, that.ipAddr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(oid, ipAddr);
    }
}
