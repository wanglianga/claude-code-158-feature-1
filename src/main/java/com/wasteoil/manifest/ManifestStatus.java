package com.wasteoil.manifest;

public enum ManifestStatus {
    /** 已创建（派单时） */
    CREATED,
    /** 已装车起运 */
    IN_TRANSIT,
    /** 处置厂已收料（二次称重完成） */
    RECEIVED,
    /** 合规比对通过 */
    VERIFIED,
    /** 已结算闭环 */
    CLOSED,
    /** 异常待处理 */
    EXCEPTION,
    /** 离线补录待人工复核（复核通过前不得起运/收料/结算） */
    PENDING_REVIEW,
    /** 处置厂拒收 */
    REJECTED,
    /** 商户质疑中 */
    DISPUTED,
    /** 已取消（预约取消） */
    CANCELLED
}
