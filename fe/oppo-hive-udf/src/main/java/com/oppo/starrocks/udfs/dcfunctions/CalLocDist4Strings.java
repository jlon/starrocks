package com.oppo.starrocks.udfs.dcfunctions;

public class CalLocDist4Strings {
    public String evaluate(String lng1, String lat1, String lng2, String lat2) {
        return DistanceSupport.distance(lng1, lat1, lng2, lat2);
    }
}
