package com.wasteoil.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "wasteoil")
public class WasteOilProperties {

    /** 结算单价（元/公斤） */
    private BigDecimal unitPricePerKg = new BigDecimal("3.20");

    /** 运输损耗率阈值 */
    private BigDecimal maxLossRate = new BigDecimal("0.03");

    /** 称重设备离线判定（分钟） */
    private int deviceOfflineMinutes = 5;

    /** 司机迟到宽限（分钟） */
    private int driverLateGraceMinutes = 30;

    /** 运输途中轨迹缺失判定（分钟） */
    private int trackMissingMinutes = 10;

    /** 风险分析窗口（天） */
    private int riskWindowDays = 90;

    /** 产量/预期比值阈值 */
    private BigDecimal riskYieldRatio = new BigDecimal("0.3");

    /** 司机频繁设备离线判定窗口（天） */
    private int driverOfflineWindowDays = 30;

    /** 窗口内离线补录达到该次数即判定频繁离线 */
    private int driverOfflineFrequentThreshold = 3;

    /** 离线补录复核驳回时司机信用扣分 */
    private int driverCreditDeductOnReject = 10;
}
