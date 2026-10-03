package com.evops.install;

/**
 * F8 安装派单与资质校验状态口径。
 *
 * 三张口径：
 * - 资质匹配状态（按「高空证有效期至 ≥ 计划施工日」判定，不固化）：
 *   VALID 有效 / EXPIRED 已过期；过期接不了高空点位，自动匹配跳过、手工派工硬拦。
 * - 资质匹配版本：高空证每录一次记一版（INITIAL 建档 / RENEW 换证），旧版 SUPERSEDED 可翻；
 *   派单落匹配时版本号与证件快照。
 * - 派单状态：PENDING 待派工 / ASSIGNED 已派工 / ARRIVED 已到场 / DONE 已完工 / CANCELED 已取消；
 *   每流转一次 version_no +1，事件表带快照可翻；关闭（DONE/CANCELED）即冻结。
 */
public final class InstallEnums {
    private InstallEnums() {}

    // ---- 安装队状态 ----
    /** 在册（参与匹配） */
    public static final String TEAM_ACTIVE = "ACTIVE";
    /** 停用（不参与匹配） */
    public static final String TEAM_PAUSED = "PAUSED";

    // ---- 资质匹配状态（按有效期至与计划施工日动态判定） ----
    public static final String QUAL_VALID = "VALID";
    public static final String QUAL_EXPIRED = "EXPIRED";

    // ---- 资质匹配版本：变更类型与版本状态 ----
    public static final String QUAL_INITIAL = "INITIAL";
    public static final String QUAL_RENEW = "RENEW";
    public static final String QUAL_VERSION_EFFECTIVE = "EFFECTIVE";
    public static final String QUAL_VERSION_SUPERSEDED = "SUPERSEDED";

    // ---- 派单状态 ----
    /** 待派工（已生成未匹配到队） */
    public static final String DISPATCH_PENDING = "PENDING";
    /** 已派工（已占时段，等师傅到场） */
    public static final String DISPATCH_ASSIGNED = "ASSIGNED";
    /** 已到场（师傅拍照打卡） */
    public static final String DISPATCH_ARRIVED = "ARRIVED";
    /** 已完工（回传回执，关闭冻结） */
    public static final String DISPATCH_DONE = "DONE";
    /** 已取消（随上刊单落架/对冲，关闭冻结） */
    public static final String DISPATCH_CANCELED = "CANCELED";

    // ---- 施工时段：自然日 + 上午/下午，一个队同一时段只排一个点位 ----
    public static final String SLOT_AM = "AM";
    public static final String SLOT_PM = "PM";

    // ---- 队时段占用 ----
    public static final String TEAM_SLOT_ACTIVE = "ACTIVE";
    public static final String TEAM_SLOT_RELEASED = "RELEASED";

    // ---- 派工来源：AUTO 自动匹配 / MANUAL 手工派工 ----
    public static final String ASSIGN_AUTO = "AUTO";
    public static final String ASSIGN_MANUAL = "MANUAL";

    // ---- 上刊单安装状态（t_posting_order.install_status） ----
    public static final String ORDER_INSTALL_NONE = "NONE";
    public static final String ORDER_INSTALL_PENDING = "PENDING";
    public static final String ORDER_INSTALL_INSTALLING = "INSTALLING";
    public static final String ORDER_INSTALL_INSTALLED = "INSTALLED";
    public static final String ORDER_INSTALL_CANCELED = "CANCELED";

    // ---- 照片阶段 ----
    /** 到场打卡（必填，没照片不算打卡） */
    public static final String PHASE_CHECKIN = "CHECKIN";
    /** 完工回传（可选） */
    public static final String PHASE_DONE = "DONE";

    // ---- 事件类型（只追加留底） ----
    public static final String EVENT_CREATE = "CREATE";
    public static final String EVENT_ASSIGN = "ASSIGN";
    public static final String EVENT_RESCHEDULE = "RESCHEDULE";
    public static final String EVENT_CHECKIN = "CHECKIN";
    public static final String EVENT_DONE = "DONE";
    public static final String EVENT_CANCEL = "CANCEL";
    public static final String EVENT_PHOTO_DELETE = "PHOTO_DELETE";

    // ---- 到队通知类型 ----
    public static final String NOTICE_ASSIGN = "ASSIGN";
    public static final String NOTICE_RESCHEDULE = "RESCHEDULE";
    public static final String NOTICE_CANCEL = "CANCEL";

    public static String statusLabel(String s) {
        if (s == null) {
            return "";
        }
        switch (s) {
            case DISPATCH_PENDING: return "待派工";
            case DISPATCH_ASSIGNED: return "已派工";
            case DISPATCH_ARRIVED: return "已到场";
            case DISPATCH_DONE: return "已完工";
            case DISPATCH_CANCELED: return "已取消";
            default: return s;
        }
    }

    public static String slotLabel(String s) {
        if (SLOT_AM.equals(s)) {
            return "上午";
        }
        if (SLOT_PM.equals(s)) {
            return "下午";
        }
        return s;
    }

    public static String qualLabel(String s) {
        if (QUAL_VALID.equals(s)) {
            return "有效";
        }
        if (QUAL_EXPIRED.equals(s)) {
            return "已过期";
        }
        return s;
    }

    public static String teamStatusLabel(String s) {
        if (TEAM_ACTIVE.equals(s)) {
            return "在册";
        }
        if (TEAM_PAUSED.equals(s)) {
            return "停用";
        }
        return s;
    }

    public static String installStatusLabel(String s) {
        if (s == null) {
            return "";
        }
        switch (s) {
            case ORDER_INSTALL_NONE: return "无安装";
            case ORDER_INSTALL_PENDING: return "待安装";
            case ORDER_INSTALL_INSTALLING: return "安装中";
            case ORDER_INSTALL_INSTALLED: return "已完工";
            case ORDER_INSTALL_CANCELED: return "已取消";
            default: return s;
        }
    }

    public static String phaseLabel(String s) {
        if (PHASE_CHECKIN.equals(s)) {
            return "到场打卡";
        }
        if (PHASE_DONE.equals(s)) {
            return "完工回传";
        }
        return s;
    }
}
