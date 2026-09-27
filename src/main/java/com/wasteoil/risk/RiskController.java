package com.wasteoil.risk;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import com.wasteoil.common.BizException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/risks")
public class RiskController {

    private final RiskAnalyzer riskAnalyzer;
    private final RiskEntryRepository riskEntryRepository;

    public RiskController(RiskAnalyzer riskAnalyzer, RiskEntryRepository riskEntryRepository) {
        this.riskAnalyzer = riskAnalyzer;
        this.riskEntryRepository = riskEntryRepository;
    }

    /** 手动触发全量风险扫描（监管） */
    @PostMapping("/scan")
    public ApiResult<Integer> scan() {
        AuthContext.require(ActorRole.REGULATOR);
        return ApiResult.ok(riskAnalyzer.scanAll());
    }

    /** 风险名单查询 */
    @GetMapping
    public ApiResult<List<RiskEntry>> list(@RequestParam(required = false) String status) {
        AuthContext.require(ActorRole.REGULATOR, ActorRole.RECYCLER);
        if (status == null || status.isBlank()) {
            return ApiResult.ok(riskEntryRepository.findAll());
        }
        return ApiResult.ok(riskEntryRepository.findByStatus(status));
    }

    /** 监管复核风险条目 */
    @PostMapping("/{id}/review")
    public ApiResult<RiskEntry> review(@PathVariable Long id,
                                       @Valid @RequestBody ReviewBody body) {
        AuthContext.require(ActorRole.REGULATOR);
        RiskEntry entry = riskEntryRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("风险条目"));
        entry.setStatus(body.status());
        return ApiResult.ok(riskEntryRepository.save(entry));
    }

    public record ReviewBody(
            @NotBlank @Pattern(regexp = "CONFIRMED|DISMISSED",
                    message = "复核结果只能是 CONFIRMED 或 DISMISSED") String status) {
    }
}
