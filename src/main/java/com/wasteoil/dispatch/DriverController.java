package com.wasteoil.dispatch;

import com.wasteoil.common.ActorRole;
import com.wasteoil.common.ApiResult;
import com.wasteoil.common.AuthContext;
import com.wasteoil.common.BizException;
import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.weighing.WeighingRecordRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 司机管理：信用分与手动补录限制。
 * 同一司机频繁设备离线时，回收公司可限制其继续手动补录（检查设备后解除）。
 */
@RestController
@RequestMapping("/api/drivers")
public class DriverController {

    private final DriverRepository driverRepository;
    private final WeighingRecordRepository weighingRecordRepository;
    private final EventPublisher eventPublisher;
    private final WasteOilProperties properties;

    public DriverController(DriverRepository driverRepository,
                            WeighingRecordRepository weighingRecordRepository,
                            EventPublisher eventPublisher,
                            WasteOilProperties properties) {
        this.driverRepository = driverRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /** 司机列表（含信用分、补录频次、是否被限制手动补录） */
    @GetMapping
    @Transactional(readOnly = true)
    public ApiResult<List<DriverView>> list() {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        Instant since = Instant.now().minus(properties.getDriverOfflineWindowDays(), ChronoUnit.DAYS);
        List<DriverView> views = driverRepository.findAll().stream()
                .map(d -> new DriverView(d,
                        weighingRecordRepository.countOfflineSupplementsByDriverSince(d.getId(), since),
                        properties.getDriverOfflineWindowDays()))
                .toList();
        return ApiResult.ok(views);
    }

    /** 限制司机手动补录（频繁设备离线时，回收公司执行） */
    @PostMapping("/{id}/restrict")
    @Transactional
    public ApiResult<Driver> restrict(@PathVariable Long id, @Valid @RequestBody RestrictBody body) {
        AuthContext.require(ActorRole.RECYCLER);
        Driver driver = driverRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("司机"));
        if (Boolean.TRUE.equals(driver.getManualRestricted())) {
            throw BizException.conflict("该司机已处于限制手动补录状态");
        }
        driver.setManualRestricted(true);
        Driver saved = driverRepository.save(driver);
        publish(saved, "DriverManualRestricted", body.reason());
        return ApiResult.ok(saved);
    }

    /** 解除限制（设备检查完毕，恢复司机手动补录） */
    @PostMapping("/{id}/unrestrict")
    @Transactional
    public ApiResult<Driver> unrestrict(@PathVariable Long id) {
        AuthContext.require(ActorRole.RECYCLER);
        Driver driver = driverRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("司机"));
        if (!Boolean.TRUE.equals(driver.getManualRestricted())) {
            throw BizException.conflict("该司机未被限制手动补录");
        }
        driver.setManualRestricted(false);
        Driver saved = driverRepository.save(driver);
        publish(saved, "DriverManualUnrestricted", null);
        return ApiResult.ok(saved);
    }

    private void publish(Driver driver, String type, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("driverId", driver.getId());
        payload.put("driverName", driver.getName());
        payload.put("creditScore", driver.getCreditScore());
        payload.put("manualRestricted", driver.getManualRestricted());
        if (reason != null) {
            payload.put("reason", reason);
        }
        eventPublisher.publish(KafkaTopics.DISPATCH_EVENTS, "driver-" + driver.getId(), type, payload);
    }

    /** 司机视图：基础信息 + 窗口期离线补录次数 */
    public record DriverView(Driver driver, long offlineSupplementsInWindow, int windowDays) {
    }

    public record RestrictBody(@NotBlank(message = "限制原因不能为空") String reason) {
    }
}
