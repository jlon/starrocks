package com.oppo.starrocks.udfs.dcfunctions;

public class Sha2EncryptWithAlgorithm {
    public String evaluate(String value, String algorithm) {
        return CryptoSupport.sha2(value, algorithm);
    }
}
