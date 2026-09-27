package com.wasteoil.dispatch;

import com.wasteoil.common.BizException;
import com.wasteoil.plant.Plant;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 派单规划器：校验车辆容量、商圈限行、处置厂接收时间窗。
 * 纯逻辑组件，便于单元测试。
 */
@Component
public class DispatchPlanner {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 校验一次派单计划是否可行。
     *
     * @param estimatedWeightKg 预约预计重量
     * @param vehicle           车辆
     * @param plant             处置厂
     * @param restrictions      门店所在商圈的限行规则
     * @param plannedStart      计划到店时间窗开始
     * @param plannedEnd        计划到店时间窗结束
     */
    public void validate(BigDecimal estimatedWeightKg, Vehicle vehicle, Plant plant,
                         List<DistrictRestriction> restrictions,
                         Instant plannedStart, Instant plannedEnd) {
        checkCapacity(estimatedWeightKg, vehicle);
        checkTimeWindow(plannedStart, plannedEnd);
        checkRestrictions(restrictions, plannedStart, plannedEnd);
        checkPlantReceiving(plant, plannedEnd);
    }

    /** 车辆容量必须覆盖预计重量 */
    void checkCapacity(BigDecimal estimatedWeightKg, Vehicle vehicle) {
        if (!"ACTIVE".equals(vehicle.getStatus())) {
            throw BizException.conflict("车辆 " + vehicle.getPlateNo() + " 不可用");
        }
        if (vehicle.getCapacityKg().compareTo(estimatedWeightKg) < 0) {
            throw BizException.badRequest(String.format(
                    "车辆容量不足: 车辆 %s 容量 %.1f kg，预计重量 %.1f kg",
                    vehicle.getPlateNo(), vehicle.getCapacityKg(), estimatedWeightKg));
        }
    }

    void checkTimeWindow(Instant plannedStart, Instant plannedEnd) {
        if (!plannedEnd.isAfter(plannedStart)) {
            throw BizException.badRequest("计划时间窗结束必须晚于开始");
        }
    }

    /** 计划时间窗不得与商圈限行时段重叠 */
    void checkRestrictions(List<DistrictRestriction> restrictions, Instant start, Instant end) {
        LocalTime startTime = LocalTime.ofInstant(start, ZONE);
        LocalTime endTime = LocalTime.ofInstant(end, ZONE);
        for (DistrictRestriction r : restrictions) {
            if (overlaps(startTime, endTime, r.getRestrictStart(), r.getRestrictEnd())) {
                throw BizException.badRequest(String.format(
                        "计划时间 %s-%s 与商圈限行时段 %s-%s 冲突（%s）",
                        startTime, endTime, r.getRestrictStart(), r.getRestrictEnd(),
                        r.getNote() == null ? "货车限行" : r.getNote()));
            }
        }
    }

    /** 计划到达处置厂时间（以时间窗结束近似）必须落在处置厂接收时间内 */
    void checkPlantReceiving(Plant plant, Instant plannedEnd) {
        if (!"ACTIVE".equals(plant.getStatus())) {
            throw BizException.conflict("处置厂 " + plant.getName() + " 暂停接收");
        }
        LocalTime arrival = LocalTime.ofInstant(plannedEnd, ZONE);
        if (arrival.isBefore(plant.getReceivingOpen()) || arrival.isAfter(plant.getReceivingClose())) {
            throw BizException.badRequest(String.format(
                    "计划完成时间 %s 不在处置厂接收时间窗 %s-%s 内",
                    arrival, plant.getReceivingOpen(), plant.getReceivingClose()));
        }
    }

    private boolean overlaps(LocalTime aStart, LocalTime aEnd, LocalTime bStart, LocalTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }
}
