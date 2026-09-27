package com.wasteoil.track;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tracks")
public class TrackController {

    private final TrackService trackService;

    public TrackController(TrackService trackService) {
        this.trackService = trackService;
    }

    /** 车载终端批量上传轨迹点 */
    @PostMapping("/{dispatchId}")
    public ApiResult<Integer> upload(@PathVariable Long dispatchId,
                                     @Valid @RequestBody TrackUploadRequest req) {
        AuthContext.require(ActorRole.DRIVER, ActorRole.RECYCLER);
        return ApiResult.ok(trackService.upload(dispatchId, req.points()));
    }

    /** 查询某趟运输轨迹 */
    @GetMapping("/{dispatchId}")
    public ApiResult<List<TrackPoint>> trace(@PathVariable Long dispatchId) {
        return ApiResult.ok(trackService.trace(dispatchId));
    }

    public record TrackUploadRequest(
            @NotEmpty(message = "轨迹点不能为空") List<TrackService.TrackPointInput> points) {
    }
}
