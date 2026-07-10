package com.oppo.starrocks.udfs.dcfunctions;

public class GetSsoIdWithKey {
    public String evaluate(String ssoid, String key) {
        return CryptoSupport.getSsoId(ssoid, key);
    }
}
