package com.oppo.starrocks.udfs.dcfunctions;

public class Sha2EncryptDefault {
    public String evaluate(String value) {
        return CryptoSupport.sha2(value, "");
    }
}
