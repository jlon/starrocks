package com.oppo.starrocks.udfs.dcfunctions;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

final class DateSupport {
    private DateSupport() {
    }

    static Integer diff(String day1, String day2) {
        if (day1 == null || day2 == null) {
            return null;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date date1 = format.parse(day1.replace("-", ""));
            Date date2 = format.parse(day2.replace("-", ""));
            long diff = date2.getTime() - date1.getTime();
            return (int) (diff / (24 * 60 * 60 * 1000));
        } catch (Exception e) {
            return null;
        }
    }

    static Integer add(String day, Integer offset) {
        if (day == null || offset == null) {
            return null;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
        try {
            Date date = format.parse(day.replace("-", ""));
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date);
            calendar.add(Calendar.DAY_OF_MONTH, offset);
            return Integer.parseInt(format.format(calendar.getTime()));
        } catch (Exception e) {
            return null;
        }
    }
}
