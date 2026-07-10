package com.oppo.starrocks.udfs.dcfunctions;

public class DateDiffString {
    public Integer evaluate(String day1, String day2) {
        return DateSupport.diff(day1, day2);
    }
}
