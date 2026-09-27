package com.wasteoil.appointment;

public enum AppointmentStatus {
    /** 待派单 */
    PENDING,
    /** 已派单 */
    DISPATCHED,
    /** 已取消 */
    CANCELLED,
    /** 已完成（联单闭环） */
    COMPLETED
}
