package com.wasteoil.exception;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import com.wasteoil.compliance.ComplianceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/exceptions")
public class ExceptionController {

    private final ExceptionService exceptionService;
    private final ComplianceService complianceService;

    public ExceptionController(ExceptionService exceptionService,
                               ComplianceService complianceService) {
        this.exceptionService = exceptionService;
        this.complianceService = complianceService;
    }

    @GetMapping
    public ApiResult<List<ExceptionTicket>> list(@RequestParam(required = false) String status) {
        return ApiResult.ok(exceptionService.list(status));
    }

    @GetMapping("/by-ref")
    public ApiResult<List<ExceptionTicket>> listByRef(@RequestParam String refType,
                                                      @RequestParam Long refId) {
        return ApiResult.ok(exceptionService.listByRef(refType, refId));
    }

    /**
     * 处理异常工单。若该工单是联单的阻断性异常，处理后自动重估联单闭环。
     */
    @PostMapping("/{id}/resolve")
    public ApiResult<ExceptionTicket> resolve(@PathVariable Long id,
                                              @Valid @RequestBody ResolveBody body) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        ExceptionTicket ticket = exceptionService.resolve(id, body.resolution());
        if (ticket.getBlocking() && "MANIFEST".equals(ticket.getRefType())) {
            complianceService.reevaluate(ticket.getRefId());
        }
        return ApiResult.ok(ticket);
    }

    public record ResolveBody(@NotBlank(message = "处理说明不能为空") String resolution) {
    }
}
