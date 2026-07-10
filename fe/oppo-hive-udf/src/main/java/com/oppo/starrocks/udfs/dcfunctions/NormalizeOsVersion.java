package com.oppo.starrocks.udfs.dcfunctions;

public class NormalizeOsVersion {
    public String evaluate(String version) {
        return VersionSupport.normalizeOs(version);
    }
}
