package com.wasteoil.supplement;

import com.wasteoil.common.ActorRole;
import com.wasteoil.common.ApiResult;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 离线补录复核接口。复核人员（回收公司/监管）查看复核队列与工作台详情，
 * 确认后放行结算或驳回（影响司机信用）。
 */
@RestController
@RequestMapping("/api/supplement-reviews")
public class SupplementReviewController {

    private final SupplementReviewService reviewService;

    public SupplementReviewController(SupplementReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 复核队列（status=PENDING/APPROVED/REJECTED，缺省全部） */
    @GetMapping
    public ApiResult<List<SupplementReview>> list(@RequestParam(required = false) String status) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(reviewService.list(status));
    }

    /** 复核工作台：秤面照片、油桶数量、历史重量、司机补录频次与信用 */
    @GetMapping("/{id}")
    public ApiResult<SupplementReviewService.ReviewDetail> detail(@PathVariable Long id) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(reviewService.detail(id));
    }

    /** 复核通过：联单打人工补录标记，恢复结算 */
    @PostMapping("/{id}/approve")
    public ApiResult<SupplementReview> approve(@PathVariable Long id,
                                               @Valid @RequestBody ReviewBody body) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(reviewService.approve(id, body.photoClarity(), body.note()));
    }

    /** 复核驳回：异常补录，扣司机信用并阻断闭环 */
    @PostMapping("/{id}/reject")
    public ApiResult<SupplementReview> reject(@PathVariable Long id,
                                              @Valid @RequestBody ReviewBody body) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(reviewService.reject(id, body.photoClarity(), body.note()));
    }

    public record ReviewBody(
            @NotBlank(message = "必须登记秤面照片清晰度（CLEAR / BLURRY）") String photoClarity,
            String note) {
    }
}
