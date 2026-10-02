package com.wasteoil.weighing;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/weighings")
public class WeighingController {

    private final WeighingService weighingService;

    public WeighingController(WeighingService weighingService) {
        this.weighingService = weighingService;
    }

    /** 提交称重（设备直传 DEVICE / 手动补录 MANUAL / 设备离线补录 OFFLINE_MANUAL） */
    @PostMapping
    public ApiResult<WeighingRecord> submit(@Valid @RequestBody WeighingRequest req) {
        AuthContext.require(ActorRole.DRIVER, ActorRole.RECYCLER);
        return ApiResult.ok(weighingService.submit(req.dispatchId(), req.barrelCode(), req.weightKg(),
                req.deviceCode(), req.source(), req.photoUrl(), req.scalePhotoUrl(),
                req.handoverName(), req.note()));
    }

    /** 称重设备心跳 */
    @PostMapping("/devices/{code}/heartbeat")
    public ApiResult<WeighingDevice> heartbeat(@PathVariable String code) {
        return ApiResult.ok(weighingService.heartbeat(code));
    }

    public record WeighingRequest(
            @NotNull(message = "派单不能为空") Long dispatchId,
            @NotBlank(message = "油桶编号不能为空") String barrelCode,
            @NotNull(message = "称重重量不能为空")
            @DecimalMin(value = "0.1", message = "称重重量必须大于 0") BigDecimal weightKg,
            String deviceCode,
            @NotBlank(message = "称重来源不能为空") String source,
            String photoUrl,
            String scalePhotoUrl,
            String handoverName,
            String note) {
    }
}
