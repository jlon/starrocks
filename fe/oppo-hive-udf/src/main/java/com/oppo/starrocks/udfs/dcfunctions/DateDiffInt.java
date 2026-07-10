package com.oppo.starrocks.udfs.dcfunctions;

public class DateDiffInt {
    public Integer evaluate(Integer day1, Integer day2) {
        if (day1 == null || day2 == null) {
            return null;
        }
        return DateSupport.diff(String.valueOf(day1), String.valueOf(day2));
    }
}
