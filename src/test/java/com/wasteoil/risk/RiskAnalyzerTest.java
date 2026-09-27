package com.wasteoil.risk;

import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import com.wasteoil.weighing.WeighingRecord;
import com.wasteoil.weighing.WeighingRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 风险分析单元测试：产量/营业规模/回收频次长期不匹配进入风险名单。
 */
@ExtendWith(MockitoExtension.class)
class RiskAnalyzerTest {

    @Mock
    StoreRepository storeRepository;
    @Mock
    ManifestRepository manifestRepository;
    @Mock
    WeighingRecordRepository weighingRecordRepository;
    @Mock
    RiskEntryRepository riskEntryRepository;

    RiskAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        WasteOilProperties props = new WasteOilProperties();
        analyzer = new RiskAnalyzer(storeRepository, manifestRepository,
                weighingRecordRepository, riskEntryRepository, props);
        lenient().when(riskEntryRepository.findByStoreIdAndIndicatorAndWindowEndAndStatus(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(riskEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Store store(String expectedDailyOilKg) {
        Store s = new Store();
        s.setId(1L);
        s.setStatus("ACTIVE");
        s.setExpectedDailyOilKg(new BigDecimal(expectedDailyOilKg));
        return s;
    }

    private Manifest closedManifest(long id, String arrivalWeightKg) {
        Manifest m = new Manifest();
        m.setId(id);
        m.setStatus(ManifestStatus.CLOSED);
        m.setArrivalWeightKg(new BigDecimal(arrivalWeightKg));
        return m;
    }

    @Test
    void 长期无回收记录_进入风险名单() {
        when(manifestRepository.findByStoreIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of());

        List<RiskEntry> entries = analyzer.analyzeStore(store("60"));

        assertThat(entries).extracting(RiskEntry::getIndicator)
                .contains("YIELD_MISMATCH", "NO_RECYCLING");
    }

    @Test
    void 产量远低于营业规模_进入风险名单() {
        // 90 天预估 60*90=5400kg，实际仅 500kg → 比值 0.09 < 0.3
        when(manifestRepository.findByStoreIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(closedManifest(1L, "500")));
        when(weighingRecordRepository.findByManifestId(1L)).thenReturn(List.of());

        List<RiskEntry> entries = analyzer.analyzeStore(store("60"));

        assertThat(entries).extracting(RiskEntry::getIndicator).contains("YIELD_MISMATCH");
        assertThat(entries).extracting(RiskEntry::getIndicator).doesNotContain("NO_RECYCLING");
    }

    @Test
    void 产量与规模匹配_不进风险名单() {
        // 90 天预估 900kg，实际 800kg → 比值 0.89
        when(manifestRepository.findByStoreIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(closedManifest(1L, "800")));
        WeighingRecord device = new WeighingRecord();
        device.setSource("DEVICE");
        when(weighingRecordRepository.findByManifestId(1L)).thenReturn(List.of(device));

        List<RiskEntry> entries = analyzer.analyzeStore(store("10"));

        assertThat(entries).isEmpty();
    }

    @Test
    void 手动补录占比过高_进入风险名单() {
        when(manifestRepository.findByStoreIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(closedManifest(1L, "800")));
        WeighingRecord manual = new WeighingRecord();
        manual.setSource("MANUAL");
        when(weighingRecordRepository.findByManifestId(1L))
                .thenReturn(List.of(manual, manual, manual, manual, manual));

        List<RiskEntry> entries = analyzer.analyzeStore(store("10"));

        assertThat(entries).extracting(RiskEntry::getIndicator).contains("MANUAL_WEIGH_HEAVY");
    }
}
