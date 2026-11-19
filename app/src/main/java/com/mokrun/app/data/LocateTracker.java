package com.mokrun.app.data;

/**
 * 「定位当前位置」按钮的请求状态机（纯逻辑，无 Android 依赖，便于 JVM 回归）。
 *
 * 负责三件容易出错的事：
 *  1. 单飞：请求进行中再次点击直接忽略，避免并发定位与重复回灌；
 *  2. 新鲜度：缓存只在足够新时才能当成本次结果，不能拿任意陈旧缓存宣布定位完成；
 *  3. 结束状态：每次请求都必须以 DONE 或 FAILED 收尾，不允许静默无响应。
 */
public final class LocateTracker {

    public enum State { IDLE, REQUESTING, DONE, FAILED }

    /** 缓存可接受的最大年龄：仅用于「先显示」的预览，不用于宣布定位完成 */
    public static final long PREVIEW_MAX_AGE_MS = 60000L;
    /** 一次定位请求的超时时间 */
    public static final long TIMEOUT_MS = 12000L;
    /** 缓存足够新时直接当作「当前位置」，不必再等系统定位 */
    public static final long FRESH_ENOUGH_MS = 5000L;
    /** 等不到新点时，最多用这么旧的缓存兜底（会如实标注时间） */
    public static final long FALLBACK_MAX_AGE_MS = 120000L;

    private State state = State.IDLE;
    private long requestAtMs = 0L;
    private int requestCount = 0;
    private String lastError = "";

    public State state() { return state; }

    public boolean isRequesting() { return state == State.REQUESTING; }

    public int requestCount() { return requestCount; }

    public String lastError() { return lastError; }

    /** 请求进行中返回 false（重复点击被忽略），否则进入 REQUESTING 并返回 true */
    public boolean begin(long nowMs) {
        if (state == State.REQUESTING) return false;
        state = State.REQUESTING;
        requestAtMs = nowMs;
        requestCount++;
        lastError = "";
        return true;
    }

    /** 是否已经超时；超时同时把状态推进到 FAILED，保证有结束状态 */
    public boolean checkTimeout(long nowMs) {
        if (state != State.REQUESTING) return false;
        if (nowMs - requestAtMs <= TIMEOUT_MS) return false;
        fail("定位超时：没有在 " + (TIMEOUT_MS / 1000) + " s 内拿到结果，请到开阔处重试");
        return true;
    }

    public void succeed() {
        state = State.DONE;
        lastError = "";
    }

    public void fail(String reason) {
        state = State.FAILED;
        lastError = reason == null ? "定位失败" : reason;
    }

    /** 兜底缓存是否还能用（新鲜度单独判定，仅用于「等不到新点」时收尾） */
    public static boolean usableFallback(long ageMs) {
        return ageMs >= 0 && ageMs <= FALLBACK_MAX_AGE_MS;
    }

    /** 缓存是否够新：只有在 PREVIEW_MAX_AGE_MS 内才允许先画出来 */
    public static boolean cacheUsable(long fixAgeMs) {
        return fixAgeMs >= 0 && fixAgeMs <= PREVIEW_MAX_AGE_MS;
    }

    /** 重置到 IDLE（Activity 退出/重新进入时使用） */
    public void reset() {
        state = State.IDLE;
        requestAtMs = 0L;
        lastError = "";
    }
}
