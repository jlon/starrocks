package com.oppo.starrocks.udfs.dcfunctions;

public class CalLocDist2Strings {
    public String evaluate(String loc1, String loc2) {
        return DistanceSupport.distance(loc1, loc2);
    }
}
