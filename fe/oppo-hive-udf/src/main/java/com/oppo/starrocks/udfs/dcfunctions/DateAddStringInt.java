package com.oppo.starrocks.udfs.dcfunctions;

public class DateAddStringInt {
    public Integer evaluate(String day, Integer offset) {
        return DateSupport.add(day, offset);
    }
}
