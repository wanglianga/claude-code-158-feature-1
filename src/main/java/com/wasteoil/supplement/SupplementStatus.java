package com.wasteoil.supplement;

/**
 * 离线补录单状态。
 */
public enum SupplementStatus {
    /** 待复核：联单挂起，不得进入后续运输收料与结算 */
    PENDING_REVIEW,
    /** 复核通过：重量采信，批次联单打上人工补录标记 */
    APPROVED,
    /** 复核驳回：重量不采信，登记异常并扣减司机信用 */
    REJECTED
}
