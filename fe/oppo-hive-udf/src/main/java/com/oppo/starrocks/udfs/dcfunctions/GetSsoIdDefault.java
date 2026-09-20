package com.oppo.starrocks.udfs.dcfunctions;

public class GetSsoIdDefault {
    public String evaluate(String ssoid) {
        return CryptoSupport.getSsoId(ssoid, CryptoSupport.DEFAULT_AES_KEY);
    }
}
