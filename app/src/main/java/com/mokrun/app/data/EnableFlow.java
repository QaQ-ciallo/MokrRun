package com.mokrun.app.data;

/**
 * 「启用并打开体适能」的结果状态机（纯逻辑，无 Android 依赖，便于 JVM 回归）。
 *
 * 把三件事分开记录，避免把「发送成功」当成「已启用」：
 *   1. SENT     —— 命令已经发出去（广播未抛异常 / 拉起 Intent 已提交）；
 *   2. ACKED    —— 目标进程按 session 回执确认已进入 PREPARED；
 *   3. NAV      —— 把目标应用带到前台的结果（成功/失败各自保留）。
 *
 * 只有 ACKED 才算「已启用」。导航失败不回滚已启用事实，而是作为第二个结果展示。
 */
public final class EnableFlow {

    public enum Phase { IDLE, SENT, ACKED, FAILED }

    /** 等待 PREPARE 回执的超时时间 */
    public static final long ACK_TIMEOUT_MS = 8000L;

    private Phase phase = Phase.IDLE;
    private String session = "";
    private long sentAtMs;
    private String failure = "";
    private boolean navOk;
    private String navDetail = "";

    public Phase phase() { return phase; }

    public String session() { return session; }

    public String failure() { return failure; }

    public boolean navOk() { return navOk; }

    public String navDetail() { return navDetail; }

    /** 记录「已发送」。同一 session 重复点击返回 false（忽略重复） */
    public boolean begin(String session, long nowMs) {
        if (phase == Phase.SENT && session != null && session.equals(this.session)) return false;
        this.session = session == null ? "" : session;
        this.phase = Phase.SENT;
        this.sentAtMs = nowMs;
        this.failure = "";
        this.navOk = false;
        this.navDetail = "";
        return true;
    }

    /** 回执必须按 session 匹配；不匹配的忽略并返回 false */
    public boolean onAck(String ackSession, boolean ok, String reason, long nowMs) {
        if (phase != Phase.SENT) return false;
        if (ackSession == null || !ackSession.equals(session)) return false;
        if (ok) {
            phase = Phase.ACKED;
            failure = "";
        } else {
            phase = Phase.FAILED;
            failure = reason == null || reason.length() == 0 ? "目标进程拒绝了启用请求" : reason;
        }
        return true;
    }

    /** 超时：仍在 SENT 就判定失败，保证「启用中」一定有结束状态 */
    public boolean checkTimeout(long nowMs) {
        if (phase != Phase.SENT) return false;
        if (nowMs - sentAtMs <= ACK_TIMEOUT_MS) return false;
        phase = Phase.FAILED;
        failure = "未收到目标进程的启用回执（" + (ACK_TIMEOUT_MS / 1000) + " s 超时）";
        return true;
    }

    /** 导航结果单独记录：失败不影响「已启用」事实 */
    public void recordNav(boolean ok, String detail) {
        navOk = ok;
        navDetail = detail == null ? "" : detail;
    }

    /** 发送阶段就失败（无法送达） */
    public void failSend(String reason) {
        phase = Phase.FAILED;
        failure = reason == null ? "无法把启用请求送到目标应用" : reason;
    }

    public void reset() {
        phase = Phase.IDLE;
        session = "";
        failure = "";
        navOk = false;
        navDetail = "";
    }
}
