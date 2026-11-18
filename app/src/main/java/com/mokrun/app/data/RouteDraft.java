package com.mokrun.app.data;

/**
 * 本次会话的路线草稿校验（纯逻辑，无 Android 依赖，便于 JVM 回归）。
 *
 * 校验分两层：
 *  1. 语法/范围：起点与途经点必须是有限、在地理范围内的坐标，且至少 1 个途经点；
 *  2. 连续性：确认前必须有一份**新鲜**的真实位置，用它判断起点是否在当前位置附近。
 *
 * 连续性容差不是「必然通过应用检测」的阈值，而是模块自己的产品容差：
 * 以定位精度为基准，取 max(50 m, 3 × accuracy)。
 *   - 50 m 下限：覆盖室内网络定位常见的 20~30 m 精度与步行误差；
 *   - 3 倍精度：精度越差容差越宽，避免在弱信号下把正常起点判成不连续；
 *   - 超过容差不算硬失败，而是要求用户**再点一次确认**（明确知道起点在别处），
 *     不静默改起点、也不生成过渡轨迹。
 */
public final class RouteDraft {

    /** 位置新鲜度上限：超过这个时间的定位不用于连续性判断 */
    public static final long MAX_FIX_AGE_MS = 30000L;
    /** 连续性容差下限（米） */
    public static final double MIN_TOLERANCE_M = 50.0;
    /** 定位精度上限：超过视为不可靠，需要用户明确确认 */
    public static final double MAX_ACCURACY_M = 60.0;
    /** 判定「精度差」时的提示阈值 */
    public static final double POOR_ACCURACY_M = 35.0;

    public static final class Result {
        public final boolean ok;
        public final boolean needSecondTap;
        public final String message;
        public final double distanceM;
        public final double accuracyM;

        Result(boolean ok, boolean needSecondTap, String message, double distanceM, double accuracyM) {
            this.ok = ok;
            this.needSecondTap = needSecondTap;
            this.message = message;
            this.distanceM = distanceM;
            this.accuracyM = accuracyM;
        }
    }

    private RouteDraft() {}

    /** 起点到当前位置的平面距离（米） */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    /** 连续性容差：max(50 m, 3 × 精度) */
    public static double toleranceM(double accuracyM) {
        double acc = accuracyM > 0 && !Double.isNaN(accuracyM) ? accuracyM : MAX_ACCURACY_M;
        return Math.max(MIN_TOLERANCE_M, 3.0 * acc);
    }

    public static boolean validCoord(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isInfinite(lat)
                && !Double.isNaN(lon) && !Double.isInfinite(lon)
                && lat >= -90.0 && lat <= 90.0
                && lon >= -180.0 && lon <= 180.0;
    }

    /** @return {lat, lon}；无法解析返回 null */
    public static double[] parseOrigin(String origin) {
        if (origin == null) return null;
        String s = origin.trim();
        if (s.indexOf(',') < 0) return null;
        String[] p = s.split(",");
        if (p.length < 2) return null;
        try {
            double lat = Double.parseDouble(p[0].trim());
            double lon = Double.parseDouble(p[1].trim());
            return validCoord(lat, lon) ? new double[]{lat, lon} : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只做结构性校验（不含连续性）：起点有效 + 至少 1 个合法途经点 */
    public static String validateShape(String origin, String waypointsJson) {
        double[] o = parseOrigin(origin);
        if (o == null) return "起点无效：请重新选择起点";
        int n = waypointCount(waypointsJson);
        if (n < 1) return "还没有途经点：请绘制路线或选择一条历史路线";
        return null;
    }

    /** 途经点数量；JSON 异常或坐标非法返回 -1 */
    public static int waypointCount(String waypointsJson) {
        if (waypointsJson == null || waypointsJson.trim().length() <= 4) return 0;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(waypointsJson);
            int n = arr.length();
            for (int i = 0; i < n; i++) {
                org.json.JSONObject p = arr.getJSONObject(i);
                double lat = p.getDouble("lat");
                double lng = p.getDouble("lng");
                if (!validCoord(lat, lng)) return -1;
            }
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 确认前的完整校验。
     *
     * @param curLat/curLon 最近一次真实定位（GCJ-02）；未知时传 NaN
     * @param accuracyM     该定位的精度（米）；未知传 0
     * @param fixAgeMs      该定位的年龄（毫秒）；未知传 Long.MAX_VALUE
     * @param allowFar      用户是否已明确选择「仍然使用该起点」
     */
    public static Result check(String origin, String waypointsJson,
                               double curLat, double curLon,
                               double accuracyM, long fixAgeMs, boolean allowFar) {
        String shapeErr = validateShape(origin, waypointsJson);
        if (shapeErr != null) return new Result(false, false, shapeErr, 0, accuracyM);

        double[] o = parseOrigin(origin);
        if (o == null) return new Result(false, false, "起点无效：请重新选择起点", 0, accuracyM);

        boolean fixKnown = validCoord(curLat, curLon);
        if (!fixKnown || fixAgeMs > MAX_FIX_AGE_MS) {
            return new Result(false, false,
                    "拿不到新鲜定位（超过 " + (MAX_FIX_AGE_MS / 1000) + " s）"
                            + "：请到开阔处重新定位后再确认，或返回修改起点",
                    0, accuracyM);
        }

        double d = distanceM(o[0], o[1], curLat, curLon);
        double tol = toleranceM(accuracyM);
        String distText = String.format(java.util.Locale.US, "%.0f m", d);
        String accText = accuracyM > 0
                ? String.format(java.util.Locale.US, "%.0f m", accuracyM) : "未知";

        if (d <= tol) {
            return new Result(true, false,
                    "起点与当前位置相距 " + distText + "（定位精度 " + accText + "）",
                    d, accuracyM);
        }
        if (allowFar) {
            return new Result(true, true,
                    "已按你的确认使用该起点（相距 " + distText + "，精度 " + accText + "）",
                    d, accuracyM);
        }
        return new Result(false, true,
                "起点与当前位置相距 " + distText + "（定位精度 " + accText + "），"
                        + "超过本次容差 " + String.format(java.util.Locale.US, "%.0f m", tol)
                        + "。可以返回修改起点，或再次点击「确认路线并开始」按该起点继续",
                d, accuracyM);
    }
}
