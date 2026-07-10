package com.oppo.starrocks.udfs.dcfunctions;

public class DateAddInt {
    public Integer evaluate(Integer day, Integer offset) {
        if (day == null) {
            return null;
        }
        return DateSupport.add(String.valueOf(day), offset);
    }
}
