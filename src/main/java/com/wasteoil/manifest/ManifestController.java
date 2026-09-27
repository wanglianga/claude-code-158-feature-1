package com.wasteoil.manifest;

import com.wasteoil.common.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 电子联单查询。所有角色共用同一接口、同一份数据。
 */
@RestController
@RequestMapping("/api/manifests")
public class ManifestController {

    private final ManifestRepository manifestRepository;
    private final ManifestDetailService detailService;

    public ManifestController(ManifestRepository manifestRepository,
                              ManifestDetailService detailService) {
        this.manifestRepository = manifestRepository;
        this.detailService = detailService;
    }

    /** 联单全链路详情（单一口径） */
    @GetMapping("/{id}")
    public ApiResult<ManifestDetailService.ManifestDetail> detail(@PathVariable Long id) {
        return ApiResult.ok(detailService.detail(id));
    }

    @GetMapping
    public ApiResult<List<Manifest>> list(@RequestParam(required = false) Long storeId,
                                          @RequestParam(required = false) String status) {
        if (storeId != null) {
            return ApiResult.ok(manifestRepository.findByStoreIdOrderByCreatedAtDesc(storeId));
        }
        if (status != null && !status.isBlank()) {
            return ApiResult.ok(manifestRepository.findByStatus(
                    ManifestStatus.valueOf(status.toUpperCase())));
        }
        return ApiResult.ok(manifestRepository.findAll());
    }
}
