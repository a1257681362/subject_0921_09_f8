package com.evops.ledger;

/** 底账状态口径。 */
public final class LedgerEnums {
    private LedgerEnums() {}

    /** 点位状态 */
    public static final String SITE_LISTED = "LISTED";

    /** 单据种类：上刊单 / 对冲单 */
    public static final String KIND_POSTING = "POSTING";
    public static final String KIND_OFFSET = "OFFSET";

    /** 上刊单状态：挂起 / 落架 / 已对冲 */
    public static final String ORDER_HUNG = "HUNG";
    public static final String ORDER_TAKEN_DOWN = "TAKEN_DOWN";
    public static final String ORDER_OFFSET = "OFFSET";

    /** 档期占用：占用中 / 已释放 */
    public static final String SLOT_ACTIVE = "ACTIVE";
    public static final String SLOT_RELEASED = "RELEASED";

    /** 事件类型 */
    public static final String EVENT_CREATE = "CREATE";
    public static final String EVENT_EDIT = "EDIT";
    public static final String EVENT_TAKE_DOWN = "TAKE_DOWN";
    public static final String EVENT_OFFSET = "OFFSET";
    public static final String EVENT_ROLLBACK = "ROLLBACK";

    /** 经纬度建档状态：未建档 / 已建档 */
    public static final String GEO_UNFILED = "UNFILED";
    public static final String GEO_FILED = "FILED";

    /** 经纬度建档版本来源：首次建档 / 纠偏重录 */
    public static final String GEO_SOURCE_INITIAL = "INITIAL";
    public static final String GEO_SOURCE_CORRECT = "CORRECT";

    /** 经纬度建档版本状态：生效中 / 已被新版取代 */
    public static final String GEO_VERSION_EFFECTIVE = "EFFECTIVE";
    public static final String GEO_VERSION_SUPERSEDED = "SUPERSEDED";
}
