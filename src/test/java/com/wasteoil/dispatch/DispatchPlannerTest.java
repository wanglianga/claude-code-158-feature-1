package com.wasteoil.dispatch;

import com.wasteoil.common.BizException;
import com.wasteoil.plant.Plant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 派单规划器单元测试：车辆容量、商圈限行、处置厂接收时间窗。
 */
class DispatchPlannerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private DispatchPlanner planner;
    private Vehicle vehicle;
    private Plant plant;

    @BeforeEach
    void setUp() {
        planner = new DispatchPlanner();
        vehicle = new Vehicle();
        vehicle.setPlateNo("川A12345");
        vehicle.setCapacityKg(new BigDecimal("5000"));
        vehicle.setStatus("ACTIVE");
        plant = new Plant();
        plant.setName("绿源废油处置厂");
        plant.setReceivingOpen(LocalTime.of(8, 0));
        plant.setReceivingClose(LocalTime.of(18, 0));
        plant.setStatus("ACTIVE");
    }

    private Instant at(LocalTime time) {
        // 固定用明天，避免"过去时间"干扰
        return LocalDate.now().plusDays(1).atTime(time).atZone(ZONE).toInstant();
    }

    @Test
    void 正常派单通过校验() {
        DistrictRestriction r = restriction("07:00", "09:30");
        assertThatCode(() -> planner.validate(new BigDecimal("800"), vehicle, plant,
                List.of(r), at(LocalTime.of(10, 0)), at(LocalTime.of(11, 0))))
                .doesNotThrowAnyException();
    }

    @Test
    void 车辆容量不足被拒绝() {
        assertThatThrownBy(() -> planner.validate(new BigDecimal("6000"), vehicle, plant,
                List.of(), at(LocalTime.of(10, 0)), at(LocalTime.of(11, 0))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("车辆容量不足");
    }

    @Test
    void 限行时段冲突被拒绝() {
        DistrictRestriction morning = restriction("07:00", "09:30");
        DistrictRestriction evening = restriction("17:00", "19:30");
        // 09:00-10:00 与 07:00-09:30 重叠
        assertThatThrownBy(() -> planner.validate(new BigDecimal("800"), vehicle, plant,
                List.of(morning, evening), at(LocalTime.of(9, 0)), at(LocalTime.of(10, 0))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("限行");
        // 18:00-18:30 与 17:00-19:30 重叠
        assertThatThrownBy(() -> planner.validate(new BigDecimal("800"), vehicle, plant,
                List.of(morning, evening), at(LocalTime.of(18, 0)), at(LocalTime.of(18, 30))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("限行");
    }

    @Test
    void 超出处置厂接收时间被拒绝() {
        // 19:00 完成，处置厂 18:00 停止接收
        assertThatThrownBy(() -> planner.validate(new BigDecimal("800"), vehicle, plant,
                List.of(), at(LocalTime.of(18, 30)), at(LocalTime.of(19, 0))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("接收时间窗");
    }

    @Test
    void 时间窗颠倒被拒绝() {
        assertThatThrownBy(() -> planner.validate(new BigDecimal("800"), vehicle, plant,
                List.of(), at(LocalTime.of(11, 0)), at(LocalTime.of(10, 0))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("结束必须晚于开始");
    }

    private DistrictRestriction restriction(String start, String end) {
        DistrictRestriction r = new DistrictRestriction();
        r.setDistrict("春熙路商圈");
        r.setRestrictStart(LocalTime.parse(start));
        r.setRestrictEnd(LocalTime.parse(end));
        r.setNote("货车限行");
        return r;
    }
}
