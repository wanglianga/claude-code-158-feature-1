package com.wasteoil.merchant;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    private final MerchantRepository merchantRepository;
    private final StoreRepository storeRepository;

    public MerchantController(MerchantRepository merchantRepository,
                              StoreRepository storeRepository) {
        this.merchantRepository = merchantRepository;
        this.storeRepository = storeRepository;
    }

    @GetMapping
    public ApiResult<List<Merchant>> merchants() {
        return ApiResult.ok(merchantRepository.findAll());
    }

    @GetMapping("/{merchantId}/stores")
    public ApiResult<List<Store>> stores(@PathVariable Long merchantId) {
        return ApiResult.ok(storeRepository.findByMerchantId(merchantId));
    }

    @GetMapping("/stores")
    public ApiResult<List<Store>> allStores() {
        return ApiResult.ok(storeRepository.findAll());
    }

    /** 登记门店（回收公司/监管维护基础数据） */
    @PostMapping("/stores")
    public ApiResult<Store> createStore(@RequestBody Store store) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(storeRepository.save(store));
    }
}
