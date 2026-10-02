package com.wasteoil.supplement;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import com.wasteoil.common.BizException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 离线补录与人工复核接口。
 * 司机提交（拍照 + 手录重量），回收公司/监管复核，监管可优先抽查人工补录批次。
 */
@RestController
@RequestMapping("/api/supplements")
public class SupplementController {

    private final SupplementService supplementService;

    public SupplementController(SupplementService supplementService) {
        this.supplementService = supplementService;
    }

    /** 司机提交离线补录批次（蓝牙秤无法连接） */
    @PostMapping
    public ApiResult<OfflineSupplement> submit(@Valid @RequestBody SupplementRequest req) {
        AuthContext.require(ActorRole.DRIVER, ActorRole.RECYCLER);
        List<SupplementService.SupplementItem> items = req.items().stream()
                .map(i -> new SupplementService.SupplementItem(
                        i.barrelCode(), i.weightKg(), i.handoverName()))
                .toList();
        return ApiResult.ok(supplementService.submit(req.dispatchId(), req.deviceCode(),
                items, req.photoUrls(), req.reason()));
    }

    /** 补录单列表（可按状态过滤；复核台主要看 PENDING_REVIEW，仅回收/监管） */
    @GetMapping
    public ApiResult<List<OfflineSupplement>> list(@RequestParam(required = false) String status) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(supplementService.list(status));
    }

    /** 复核台视图：照片、油桶数量核对、历史重量、司机补录频次与信用 */
    @GetMapping("/{id}/review-view")
    public ApiResult<SupplementService.SupplementReviewView> reviewView(@PathVariable Long id) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(supplementService.reviewView(id));
    }

    /** 复核决策：APPROVED（须照片清晰+桶数相符）/ REJECTED（须填写意见，扣司机信用） */
    @PostMapping("/{id}/review")
    public ApiResult<OfflineSupplement> review(@PathVariable Long id,
                                               @Valid @RequestBody ReviewRequest req) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(supplementService.review(id, req.decision(),
                Boolean.TRUE.equals(req.photoClear()), Boolean.TRUE.equals(req.barrelCountMatch()),
                req.comment()));
    }

    /** 回收公司核查设备后解除司机手动补录限制 */
    @PostMapping("/drivers/{driverId}/unblock")
    public ApiResult<SupplementService.DriverProfile> unblock(@PathVariable Long driverId,
                                            @RequestBody(required = false) UnblockRequest req) {
        AuthContext.require(ActorRole.RECYCLER);
        String comment = req == null ? null : req.comment();
        return ApiResult.ok(supplementService.unblockDriver(driverId, comment));
    }

    /** 查询司机补录画像：信用分、是否限录、窗口内补录/驳回频次 */
    @GetMapping("/drivers/{driverId}")
    public ApiResult<SupplementService.DriverProfile> driver(@PathVariable Long driverId) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR, ActorRole.DRIVER);
        return ApiResult.ok(supplementService.driverProfile(driverId));
    }

    /** 主动触发频繁离线扫描（设备检查工单 / 司机限录） */
    @PostMapping("/scan-frequent")
    public ApiResult<Integer> scanFrequent() {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(supplementService.scanFrequentOffline());
    }

    public record SupplementItemRequest(
            @NotBlank(message = "油桶编号不能为空") String barrelCode,
            @NotNull(message = "补录重量不能为空")
            @DecimalMin(value = "0.1", message = "补录重量必须大于 0") BigDecimal weightKg,
            String handoverName) {
    }

    public record SupplementRequest(
            @NotNull(message = "派单不能为空") Long dispatchId,
            @NotBlank(message = "蓝牙秤设备编号不能为空") String deviceCode,
            @NotEmpty(message = "补录油桶明细不能为空") List<SupplementItemRequest> items,
            @NotBlank(message = "秤面照片不能为空") String photoUrls,
            @NotBlank(message = "设备离线情况说明不能为空") String reason) {
    }

    public record ReviewRequest(
            @NotBlank(message = "复核决策不能为空") String decision,
            Boolean photoClear,
            Boolean barrelCountMatch,
            String comment) {
    }

    public record UnblockRequest(String comment) {
    }
}
