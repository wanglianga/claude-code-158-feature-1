package com.wasteoil.appointment;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    /** 商户提交回收预约 */
    @PostMapping
    public ApiResult<Appointment> create(@Valid @RequestBody AppointmentRequest req) {
        AuthContext.require(ActorRole.MERCHANT, ActorRole.RECYCLER);
        return ApiResult.ok(appointmentService.create(req));
    }

    /** 商户临时取消预约 */
    @PostMapping("/{id}/cancel")
    public ApiResult<Appointment> cancel(@PathVariable Long id, @RequestBody CancelBody body) {
        AuthContext.require(ActorRole.MERCHANT, ActorRole.RECYCLER);
        return ApiResult.ok(appointmentService.cancel(id, body.reason()));
    }

    @GetMapping("/{id}")
    public ApiResult<Appointment> get(@PathVariable Long id) {
        return ApiResult.ok(appointmentService.get(id));
    }

    @GetMapping
    public ApiResult<List<Appointment>> list(@RequestParam(required = false) Long storeId,
                                             @RequestParam(required = false) String status) {
        if (storeId != null) {
            return ApiResult.ok(appointmentService.listByStore(storeId));
        }
        if ("PENDING".equalsIgnoreCase(status)) {
            return ApiResult.ok(appointmentService.listPending());
        }
        return ApiResult.ok(appointmentService.listPending());
    }

    public record CancelBody(@NotBlank(message = "取消原因不能为空") String reason) {
    }
}
