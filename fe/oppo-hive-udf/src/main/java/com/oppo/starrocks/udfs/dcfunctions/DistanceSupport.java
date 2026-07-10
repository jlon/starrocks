package com.oppo.starrocks.udfs.dcfunctions;

final class DistanceSupport {
    private static final double EARTH_RADIUS = 6378.137 * 1000;

    private DistanceSupport() {
    }

    static String distance(String lng1, String lat1, String lng2, String lat2) {
        if (lng1 == null || lat1 == null || lng2 == null || lat2 == null) {
            return null;
        }
        double result = distanceCal(Double.valueOf(lng1), Double.valueOf(lat1), Double.valueOf(lng2), Double.valueOf(lat2));
        return String.valueOf(result);
    }

    static String distance(String loc1, String loc2) {
        if (loc1 == null || loc2 == null) {
            return null;
        }
        String[] p1 = loc1.split(",");
        String[] p2 = loc2.split(",");
        return distance(p1[0], p1[1], p2[0], p2[1]);
    }

    private static double distanceCal(double lng1, double lat1, double lng2, double lat2) {
        double radLat1 = rad(lat1);
        double radLat2 = rad(lat2);
        double a = radLat1 - radLat2;
        double b = rad(lng1) - rad(lng2);
        double s = 2 * Math.asin(Math.sqrt(Math.pow(Math.sin(a / 2), 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.pow(Math.sin(b / 2), 2)));
        return s * EARTH_RADIUS;
    }

    private static double rad(double d) {
        return d * Math.PI / 180.0;
    }
}
