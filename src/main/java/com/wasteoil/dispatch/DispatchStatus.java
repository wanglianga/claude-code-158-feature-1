package com.wasteoil.dispatch;

public enum DispatchStatus {
    /** 已派单待出发 */
    PLANNED,
    /** 运输前往门店途中 */
    EN_ROUTE,
    /** 已到店 */
    ARRIVED,
    /** 装车称重中 */
    LOADING,
    /** 运输前往处置厂途中 */
    IN_TRANSIT,
    /** 已到厂 */
    ARRIVED_PLANT,
    /** 已完成 */
    COMPLETED,
    /** 已取消 */
    CANCELLED
}
