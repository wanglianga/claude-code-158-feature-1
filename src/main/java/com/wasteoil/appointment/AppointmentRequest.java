package com.wasteoil.appointment;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 商户提交回收预约的请求。
 */
public record AppointmentRequest(
        @NotNull(message = "门店不能为空") Long storeId,
        @NotNull(message = "油桶数量不能为空") @Min(value = 1, message = "油桶数量至少为 1") Integer barrelCount,
        @NotNull(message = "预计重量不能为空") @DecimalMin(value = "0.1", message = "预计重量必须大于 0") BigDecimal estimatedWeightKg,
        String storageLocation,
        String contactName,
        String contactPhone,
        @NotNull(message = "上门时间窗开始不能为空") Instant windowStart,
        @NotNull(message = "上门时间窗结束不能为空") Instant windowEnd) {
}
