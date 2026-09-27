package com.wasteoil.report;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** 监管端：废油流向合规处置的长期趋势 */
    @GetMapping("/regulator/trend")
    public ApiResult<List<ReportService.DailyTrend>> regulatorTrend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        AuthContext.require(ActorRole.REGULATOR);
        Instant f = from == null ? Instant.now().minus(90, ChronoUnit.DAYS) : from;
        Instant t = to == null ? Instant.now() : to;
        return ApiResult.ok(reportService.regulatorTrend(f, t));
    }

    /** 回收公司：车辆效率 */
    @GetMapping("/vehicle-efficiency")
    public ApiResult<List<ReportService.VehicleEfficiency>> vehicleEfficiency() {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(reportService.vehicleEfficiency());
    }

    /** 商户：回收证明（结算与合规凭证） */
    @GetMapping("/stores/{storeId}/certificate")
    public ApiResult<ReportService.RecyclingCertificate> certificate(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        AuthContext.require(ActorRole.MERCHANT, ActorRole.RECYCLER, ActorRole.REGULATOR);
        Instant f = from == null ? Instant.now().minus(90, ChronoUnit.DAYS) : from;
        Instant t = to == null ? Instant.now() : to;
        return ApiResult.ok(reportService.certificate(storeId, f, t));
    }
}
