package com.oppo.starrocks.udfs.dcfunctions;

public class NormalizeAppVersion {
    public String evaluate(String appVersion) {
        return VersionSupport.normalizeApp(appVersion);
    }
}
