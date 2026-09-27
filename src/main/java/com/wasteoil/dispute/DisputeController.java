package com.wasteoil.dispute;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    private final DisputeService disputeService;

    public DisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    /** 商户质疑联单重量 */
    @PostMapping
    public ApiResult<Dispute> raise(@Valid @RequestBody DisputeRequest req) {
        AuthContext.require(ActorRole.MERCHANT);
        return ApiResult.ok(disputeService.raise(req.manifestId(), req.reason()));
    }

    /** 解决质疑（回收公司/监管） */
    @PostMapping("/{id}/resolve")
    public ApiResult<Dispute> resolve(@PathVariable Long id,
                                      @Valid @RequestBody ResolveRequest req) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(disputeService.resolve(id, req.resolution(), req.adjustedWeightKg()));
    }

    @GetMapping
    public ApiResult<List<Dispute>> list(@RequestParam(required = false) String status) {
        return ApiResult.ok(disputeService.list(status));
    }

    public record DisputeRequest(
            @NotNull(message = "联单不能为空") Long manifestId,
            @NotBlank(message = "质疑原因不能为空") String reason) {
    }

    public record ResolveRequest(
            @NotBlank(message = "处理结论不能为空") String resolution,
            BigDecimal adjustedWeightKg) {
    }
}
