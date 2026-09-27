package com.wasteoil.barrel;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import com.wasteoil.common.BizException;
import com.wasteoil.weighing.WeighingRecord;
import com.wasteoil.weighing.WeighingRecordRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 油桶管理：一桶一码，全生命周期追溯。
 */
@RestController
@RequestMapping("/api/barrels")
public class BarrelController {

    private final BarrelRepository barrelRepository;
    private final WeighingRecordRepository weighingRecordRepository;

    public BarrelController(BarrelRepository barrelRepository,
                            WeighingRecordRepository weighingRecordRepository) {
        this.barrelRepository = barrelRepository;
        this.weighingRecordRepository = weighingRecordRepository;
    }

    /** 登记油桶 */
    @PostMapping
    public ApiResult<Barrel> register(@RequestBody Barrel barrel) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.MERCHANT);
        barrel.setId(null);
        barrel.setStatus(BarrelStatus.AT_STORE);
        return ApiResult.ok(barrelRepository.save(barrel));
    }

    @GetMapping
    public ApiResult<List<Barrel>> list(@RequestParam(required = false) Long storeId) {
        if (storeId != null) {
            return ApiResult.ok(barrelRepository.findByStoreId(storeId));
        }
        return ApiResult.ok(barrelRepository.findAll());
    }

    /**
     * 一桶一码追溯：每桶废油的来源、称重、运输、接收全链路记录。
     */
    @GetMapping("/{code}/trace")
    public ApiResult<BarrelTrace> trace(@PathVariable String code) {
        Barrel barrel = barrelRepository.findByCode(code)
                .orElseThrow(() -> BizException.notFound("油桶: " + code));
        List<WeighingRecord> records = weighingRecordRepository
                .findByBarrelIdOrderByWeighedAtDesc(barrel.getId());
        return ApiResult.ok(new BarrelTrace(barrel, records));
    }

    public record BarrelTrace(Barrel barrel, List<WeighingRecord> weighingRecords) {
    }
}
