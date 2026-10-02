package com.wasteoil.risk;

import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import com.wasteoil.weighing.WeighingRecord;
import com.wasteoil.weighing.WeighingRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 风险分析：追踪长期趋势。同一门店的废油产量、营业规模与回收频次
 * 长期不匹配时进入风险名单，供监管部门重点关注。
 */
@Slf4j
@Service
public class RiskAnalyzer {

    private static final Set<ManifestStatus> COUNTED = EnumSet.of(
            ManifestStatus.VERIFIED, ManifestStatus.CLOSED, ManifestStatus.DISPUTED);

    private final StoreRepository storeRepository;
    private final ManifestRepository manifestRepository;
    private final WeighingRecordRepository weighingRecordRepository;
    private final RiskEntryRepository riskEntryRepository;
    private final WasteOilProperties properties;

    public RiskAnalyzer(StoreRepository storeRepository,
                        ManifestRepository manifestRepository,
                        WeighingRecordRepository weighingRecordRepository,
                        RiskEntryRepository riskEntryRepository,
                        WasteOilProperties properties) {
        this.storeRepository = storeRepository;
        this.manifestRepository = manifestRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.riskEntryRepository = riskEntryRepository;
        this.properties = properties;
    }

    /**
     * 全量扫描所有在用门店，返回本次新产生的风险条目数。
     */
    @Transactional
    public int scanAll() {
        int created = 0;
        for (Store store : storeRepository.findAll()) {
            if (!"ACTIVE".equals(store.getStatus())) {
                continue;
            }
            created += analyzeStore(store).size();
        }
        return created;
    }

    /**
     * 分析单个门店（纯逻辑，便于测试）。
     *
     * @return 新登记的风险条目
     */
    @Transactional
    public List<RiskEntry> analyzeStore(Store store) {
        int windowDays = properties.getRiskWindowDays();
        LocalDate windowEnd = LocalDate.now();
        LocalDate windowStart = windowEnd.minusDays(windowDays);
        Instant from = windowStart.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant to = Instant.now();

        List<Manifest> manifests = manifestRepository
                .findByStoreIdAndCreatedAtBetween(store.getId(), from, to).stream()
                .filter(m -> COUNTED.contains(m.getStatus()))
                .toList();

        BigDecimal totalReceived = manifests.stream()
                .map(m -> m.getArrivalWeightKg() == null ? BigDecimal.ZERO : m.getArrivalWeightKg())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long pickupCount = manifests.size();

        List<RiskEntry> created = new ArrayList<>();

        // 1. 产量与营业规模不匹配：实际回收量远低于按规模预估的产量
        BigDecimal expected = store.getExpectedDailyOilKg()
                .multiply(BigDecimal.valueOf(windowDays));
        if (expected.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal ratio = totalReceived.divide(expected, 4, RoundingMode.HALF_UP);
            if (ratio.compareTo(properties.getRiskYieldRatio()) < 0) {
                created.add(register(store, "YIELD_MISMATCH",
                        String.format("近 %d 天回收 %.1f kg，按营业规模预估 %.1f kg，比值 %.2f 低于阈值 %.2f，疑似废油流向不明",
                                windowDays, totalReceived, expected, ratio, properties.getRiskYieldRatio()),
                        70, windowStart, windowEnd));
            }
        }

        // 2. 回收频次异常：窗口期内完全没有合规回收记录
        if (pickupCount == 0) {
            created.add(register(store, "NO_RECYCLING",
                    String.format("近 %d 天无任何合规回收联单，废油去向不明", windowDays),
                    60, windowStart, windowEnd));
        }

        // 3. 手动补录占比过高：称重数据可信度低（含设备离线人工补录）
        long manualCount = manifests.stream()
                .flatMap(m -> weighingRecordRepository.findByManifestId(m.getId()).stream())
                .filter(w -> "MANUAL".equals(w.getSource()) || "OFFLINE_MANUAL".equals(w.getSource()))
                .count();
        long totalWeigh = manifests.stream()
                .mapToLong(m -> weighingRecordRepository.findByManifestId(m.getId()).size())
                .sum();
        if (totalWeigh >= 5 && manualCount * 2 > totalWeigh) {
            created.add(register(store, "MANUAL_WEIGH_HEAVY",
                    String.format("窗口期内 %d 条称重中 %d 条为手动补录，数据可信度低",
                            totalWeigh, manualCount),
                    40, windowStart, windowEnd));
        }

        return created;
    }

    private RiskEntry register(Store store, String indicator, String detail, int score,
                               LocalDate windowStart, LocalDate windowEnd) {
        // 同一门店同一指标同一窗口的未处理风险不重复登记
        var existing = riskEntryRepository.findByStoreIdAndIndicatorAndWindowEndAndStatus(
                store.getId(), indicator, windowEnd, "OPEN");
        if (existing.isPresent()) {
            return existing.get();
        }
        RiskEntry entry = new RiskEntry();
        entry.setStoreId(store.getId());
        entry.setIndicator(indicator);
        entry.setDetail(detail);
        entry.setScore(score);
        entry.setWindowStart(windowStart);
        entry.setWindowEnd(windowEnd);
        RiskEntry saved = riskEntryRepository.save(entry);
        log.info("风险名单新增: store={} indicator={} score={}", store.getId(), indicator, score);
        return saved;
    }
}
