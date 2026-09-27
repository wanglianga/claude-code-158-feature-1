package com.wasteoil.common;

/**
 * 调用方角色。通过请求头 X-Role 传入（演示用轻量鉴权，见 AuthInterceptor）。
 */
public enum ActorRole {
    /** 餐饮商户 */
    MERCHANT,
    /** 回收公司（调度方） */
    RECYCLER,
    /** 司机 */
    DRIVER,
    /** 处置厂 */
    PLANT,
    /** 监管部门 */
    REGULATOR
}
