package com.wasteoil.inspection;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inspections")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    /** 监管抽查登记 */
    @PostMapping
    public ApiResult<Inspection> inspect(@Valid @RequestBody InspectionRequest req) {
        AuthContext.require(ActorRole.REGULATOR);
        String inspector = AuthContext.accountId() == null ? req.inspector() : AuthContext.accountId();
        return ApiResult.ok(inspectionService.inspect(req.manifestId(), inspector,
                req.result(), req.note()));
    }

    @GetMapping("/by-manifest/{manifestId}")
    public ApiResult<List<Inspection>> listByManifest(@PathVariable Long manifestId) {
        return ApiResult.ok(inspectionService.listByManifest(manifestId));
    }

    public record InspectionRequest(
            @NotNull(message = "联单不能为空") Long manifestId,
            String inspector,
            @NotBlank(message = "抽查结果不能为空")
            @Pattern(regexp = "PASS|FAIL", message = "抽查结果只能是 PASS 或 FAIL") String result,
            String note) {
    }
}
