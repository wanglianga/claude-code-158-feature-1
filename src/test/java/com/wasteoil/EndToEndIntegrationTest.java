package com.wasteoil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasteoil.audit.AuditLogRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：真实 PostgreSQL（测试库）+ 内嵌 Kafka + MockMvc。
 * 覆盖主流程闭环与各类异常场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    AuditLogRepository auditLogRepository;

    // 跨用例共享的主流程产物
    static long happyManifestId;
    static long happyDispatchId;

    // ---------- 工具方法 ----------

    private JsonNode postJson(String url, String role, String account, String body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(url)
                        .header("X-Role", role)
                        .header("X-Account-Id", account)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode getJson(String url, String role, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(url).header("X-Role", role).header("X-Account-Id", "test"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String tomorrowAt(int hour, int minute) {
        return LocalDate.now(ZONE).plusDays(1).atTime(LocalTime.of(hour, minute))
                .atZone(ZONE).toInstant().toString();
    }

    private long createAppointment(long storeId, int barrels, String estimatedWeight) throws Exception {
        JsonNode resp = postJson("/api/appointments", "MERCHANT", "merchant-1", """
                {"storeId": %d, "barrelCount": %d, "estimatedWeightKg": %s,
                 "windowStart": "%s", "windowEnd": "%s"}
                """.formatted(storeId, barrels, estimatedWeight, tomorrowAt(10, 0), tomorrowAt(11, 0)), 200);
        return resp.path("data").path("id").asLong();
    }

    private long planDispatch(long appointmentId, String start, String end) throws Exception {
        return planDispatch(appointmentId, 1, 1, start, end);
    }

    private long planDispatch(long appointmentId, long vehicleId, long driverId,
                              String start, String end) throws Exception {
        JsonNode resp = postJson("/api/dispatches", "RECYCLER", "recycler-1", """
                {"appointmentId": %d, "vehicleId": %d, "driverId": %d, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s", "routeNote": "测试路线"}
                """.formatted(appointmentId, vehicleId, driverId, start, end), 200);
        return resp.path("data").path("id").asLong();
    }

    private long manifestIdOf(long dispatchId) throws Exception {
        JsonNode resp = getJson("/api/manifests", "REGULATOR", 200);
        for (JsonNode m : resp.path("data")) {
            if (m.path("dispatchId").asLong() == dispatchId) {
                return m.path("id").asLong();
            }
        }
        throw new IllegalStateException("未找到派单 " + dispatchId + " 对应的联单");
    }

    /** 司机执行到店核验 */
    private void arriveAndVerify(long dispatchId, String storeQr, String deviceCode) throws Exception {
        arriveAndVerify(dispatchId, storeQr, deviceCode, "driver-1");
    }

    /** 司机执行到店核验（指定司机账号） */
    private void arriveAndVerify(long dispatchId, String storeQr, String deviceCode,
                                 String driverAccount) throws Exception {
        postJson("/api/dispatches/" + dispatchId + "/depart", "DRIVER", driverAccount, "{}", 200);
        postJson("/api/dispatches/" + dispatchId + "/arrive", "DRIVER", driverAccount, "{}", 200);
        postJson("/api/dispatches/" + dispatchId + "/verify", "DRIVER", driverAccount, """
                {"storeQr": "%s", "deviceCode": "%s", "handoverName": "王店长",
                 "photoUrls": "https://img.example.com/p1.jpg"}
                """.formatted(storeQr, deviceCode), 200);
    }

    private void weigh(long dispatchId, String barrelCode, String weight, String source, String note) throws Exception {
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "%s", "weightKg": %s, "deviceCode": "DEV-001",
                 "source": "%s", "photoUrl": "https://img.example.com/w.jpg",
                 "handoverName": "王店长", "note": %s}
                """.formatted(dispatchId, barrelCode, weight, source,
                note == null ? "null" : "\"" + note + "\""), 200);
    }

    private void uploadTracksAndArrivePlant(long dispatchId) throws Exception {
        postJson("/api/tracks/" + dispatchId, "DRIVER", "driver-1", """
                {"points": [{"lat": 30.66, "lng": 104.06, "recordedAt": "%s"},
                            {"lat": 30.60, "lng": 104.10, "recordedAt": "%s"}]}
                """.formatted(Instant.now(), Instant.now()), 200);
        postJson("/api/dispatches/" + dispatchId + "/arrive-plant", "DRIVER", "driver-1", "{}", 200);
    }

    // ---------- 主流程 ----------

    @Test
    @Order(1)
    void 全链路闭环_预约到结算() throws Exception {
        // 设备心跳
        postJson("/api/weighings/devices/DEV-001/heartbeat", "RECYCLER", "recycler-1", "{}", 200);

        long appointmentId = createAppointment(1, 2, "300");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        happyDispatchId = dispatchId;

        arriveAndVerify(dispatchId, "STORE-QR-1", "DEV-001");
        weigh(dispatchId, "BAR-001", "150", "DEVICE", null);
        weigh(dispatchId, "BAR-002", "148", "DEVICE", null);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);

        long manifestId = manifestIdOf(dispatchId);
        happyManifestId = manifestId;

        // 处置厂二次称重 296kg（损耗 2kg，约 0.67%）
        JsonNode receipt = postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 296, "barrelCount": 2,
                 "qualityCheck": "OK", "decision": "ACCEPT", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);
        assertThat(receipt.path("data").path("decision").asText()).isEqualTo("ACCEPT");

        // 联单应闭环：CLOSED，损耗已计算
        JsonNode detail = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        JsonNode manifest = detail.path("data").path("manifest");
        assertThat(manifest.path("status").asText()).isEqualTo("CLOSED");
        assertThat(manifest.path("complianceResult").asText()).isEqualTo("PASS");
        assertThat(manifest.path("exitWeightKg").asDouble()).isEqualTo(298.0);
        assertThat(manifest.path("arrivalWeightKg").asDouble()).isEqualTo(296.0);
        assertThat(manifest.path("lossKg").asDouble()).isEqualTo(2.0);
        // 全链路数据齐备：称重 2 条、轨迹 2 条、收料 1 条、结算 1 条
        assertThat(detail.path("data").path("weighings").size()).isEqualTo(2);
        assertThat(detail.path("data").path("tracks").size()).isEqualTo(2);
        assertThat(detail.path("data").path("receipt").isNull()).isFalse();
        assertThat(detail.path("data").path("settlement").isNull()).isFalse();

        // 结算金额 = 296 * 3.20 = 947.20
        JsonNode settlement = getJson("/api/settlements/by-manifest/" + manifestId, "MERCHANT", 200);
        assertThat(settlement.path("data").path("amount").asDouble()).isEqualTo(947.20);
        assertThat(settlement.path("data").path("status").asText()).isEqualTo("CONFIRMED");

        // Kafka 事件应已被审计消费者落库
        await().atMost(15, TimeUnit.SECONDS).until(() -> auditLogRepository.findAll().stream()
                .anyMatch(a -> "ManifestClosed".equals(a.getEventType())));
    }

    @Test
    @Order(2)
    void 一桶一码追溯() throws Exception {
        JsonNode trace = getJson("/api/barrels/BAR-001/trace", "REGULATOR", 200);
        assertThat(trace.path("data").path("barrel").path("status").asText()).isEqualTo("AT_PLANT");
        assertThat(trace.path("data").path("weighingRecords").size()).isEqualTo(1);
        assertThat(trace.path("data").path("weighingRecords").get(0).path("weightKg").asDouble())
                .isEqualTo(150.0);
    }

    @Test
    @Order(3)
    void 商户质疑重量_冻结结算_协商调整() throws Exception {
        // 商户发起质疑
        JsonNode dispute = postJson("/api/disputes", "MERCHANT", "merchant-1", """
                {"manifestId": %d, "reason": "出店称重 298kg，到厂 296kg，对损耗 2kg 有异议"}
                """.formatted(happyManifestId), 200);
        long disputeId = dispute.path("data").path("id").asLong();

        // 联单进入 DISPUTED，结算冻结
        JsonNode manifest = getJson("/api/manifests/" + happyManifestId, "MERCHANT", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("DISPUTED");
        JsonNode settlement = getJson("/api/settlements/by-manifest/" + happyManifestId, "MERCHANT", 200);
        assertThat(settlement.path("data").path("status").asText()).isEqualTo("FROZEN");

        // 协商解决：按 297kg 结算
        postJson("/api/disputes/" + disputeId + "/resolve", "RECYCLER", "recycler-1", """
                {"resolution": "双方复核称重记录，按 297kg 结算", "adjustedWeightKg": 297}
                """, 200);
        JsonNode after = getJson("/api/settlements/by-manifest/" + happyManifestId, "MERCHANT", 200);
        assertThat(after.path("data").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(after.path("data").path("amount").asDouble()).isEqualTo(950.40);
        JsonNode closed = getJson("/api/manifests/" + happyManifestId, "MERCHANT", 200);
        assertThat(closed.path("data").path("manifest").path("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    @Order(4)
    void 监管抽查不合格_登记异常并进风险名单() throws Exception {
        postJson("/api/inspections", "REGULATOR", "regulator-1", """
                {"manifestId": %d, "result": "FAIL", "note": "现场照片不清晰，交接记录不完整"}
                """.formatted(happyManifestId), 200);

        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + happyManifestId,
                "REGULATOR", 200);
        boolean hasInspectionTicket = false;
        for (JsonNode t : tickets.path("data")) {
            if ("REGULATORY_INSPECTION".equals(t.path("type").asText())) {
                hasInspectionTicket = true;
            }
        }
        assertThat(hasInspectionTicket).isTrue();

        JsonNode risks = getJson("/api/risks", "REGULATOR", 200);
        boolean hasRisk = false;
        for (JsonNode r : risks.path("data")) {
            if ("INSPECTION_FAIL".equals(r.path("indicator").asText())) {
                hasRisk = true;
            }
        }
        assertThat(hasRisk).isTrue();
    }

    // ---------- 异常场景 ----------

    @Test
    @Order(10)
    void 商户临时取消_联动取消派单与联单() throws Exception {
        long appointmentId = createAppointment(1, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        long manifestId = manifestIdOf(dispatchId);

        postJson("/api/appointments/" + appointmentId + "/cancel", "MERCHANT", "merchant-1",
                "{\"reason\": \"今日店休，暂停回收\"}", 200);

        JsonNode appointment = getJson("/api/appointments/" + appointmentId, "MERCHANT", 200);
        assertThat(appointment.path("data").path("status").asText()).isEqualTo("CANCELLED");
        JsonNode dispatch = getJson("/api/dispatches/" + dispatchId, "RECYCLER", 200);
        assertThat(dispatch.path("data").path("status").asText()).isEqualTo("CANCELLED");
        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("CANCELLED");

        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=APPOINTMENT&refId=" + appointmentId,
                "RECYCLER", 200);
        assertThat(tickets.path("data").get(0).path("type").asText()).isEqualTo("MERCHANT_CANCEL");
    }

    @Test
    @Order(11)
    void 手动补录_必须填原因并留痕() throws Exception {
        long appointmentId = createAppointment(1, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, "STORE-QR-1", "DEV-001");

        // 无原因 → 400
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-003", "weightKg": 120, "source": "MANUAL"}
                """.formatted(dispatchId), 400);

        // 有原因 → 成功，联单记录手动补录条数
        weigh(dispatchId, "BAR-003", "120", "MANUAL", "设备故障，人工磅秤复核");
        long manifestId = manifestIdOf(dispatchId);
        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("manualWeighCount").asInt()).isEqualTo(1);

        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "REGULATOR", 200);
        boolean hasManual = false;
        for (JsonNode t : tickets.path("data")) {
            if ("MANUAL_ENTRY".equals(t.path("type").asText())) {
                hasManual = true;
            }
        }
        assertThat(hasManual).isTrue();
    }

    @Test
    @Order(12)
    void 处置厂拒收_联单进入拒收态() throws Exception {
        postJson("/api/weighings/devices/DEV-002/heartbeat", "RECYCLER", "recycler-1", "{}", 200);
        long appointmentId = createAppointment(2, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, "STORE-QR-2", "DEV-002");
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-101", "weightKg": 100, "deviceCode": "DEV-002",
                 "source": "DEVICE", "handoverName": "陈店长"}
                """.formatted(dispatchId), 200);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);
        long manifestId = manifestIdOf(dispatchId);

        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 100, "barrelCount": 1,
                 "qualityCheck": "CONTAMINATED", "decision": "REJECT",
                 "rejectReason": "油桶混入大量厨余垃圾，拒绝接收", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);

        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("REJECTED");
        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "REGULATOR", 200);
        boolean hasReject = false;
        for (JsonNode t : tickets.path("data")) {
            if ("PLANT_REJECT".equals(t.path("type").asText())) {
                hasReject = true;
            }
        }
        assertThat(hasReject).isTrue();
    }

    @Test
    @Order(13)
    void 运输损耗异常_阻断闭环_处理后恢复() throws Exception {
        postJson("/api/weighings/devices/DEV-002/heartbeat", "RECYCLER", "recycler-1", "{}", 200);
        long appointmentId = createAppointment(2, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, "STORE-QR-2", "DEV-002");
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-102", "weightKg": 100, "deviceCode": "DEV-002",
                 "source": "DEVICE", "handoverName": "陈店长"}
                """.formatted(dispatchId), 200);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);
        long manifestId = manifestIdOf(dispatchId);

        // 出店 100kg，到厂仅 80kg，损耗 20% 超阈值
        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 80, "barrelCount": 1,
                 "qualityCheck": "OK", "decision": "ACCEPT", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);

        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("EXCEPTION");
        assertThat(manifest.path("data").path("manifest").path("complianceResult").asText())
                .isEqualTo("EXCEPTION");

        // 处理阻断性异常工单后，联单自动重估并闭环
        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "RECYCLER", 200);
        long ticketId = -1;
        for (JsonNode t : tickets.path("data")) {
            if ("TRANSPORT_LOSS_ABNORMAL".equals(t.path("type").asText())) {
                ticketId = t.path("id").asLong();
            }
        }
        assertThat(ticketId).isPositive();
        postJson("/api/exceptions/" + ticketId + "/resolve", "RECYCLER", "recycler-1",
                "{\"resolution\": \"确认为运输途中阀门渗漏，已维修并备案\"}", 200);

        JsonNode closed = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(closed.path("data").path("manifest").path("status").asText()).isEqualTo("CLOSED");
    }

    // ---------- 校验与鉴权 ----------

    @Test
    @Order(20)
    void 派单校验_容量不足与限行冲突() throws Exception {
        // 车辆容量 5000，预约 6000 → 拒绝
        long bigAppointment = createAppointment(1, 1, "6000");
        postJson("/api/dispatches", "RECYCLER", "recycler-1", """
                {"appointmentId": %d, "vehicleId": 1, "driverId": 1, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s"}
                """.formatted(bigAppointment, tomorrowAt(10, 0), tomorrowAt(11, 0)), 400);

        // 春熙路商圈 17:00-19:30 限行 → 拒绝
        long appointment = createAppointment(1, 1, "100");
        JsonNode resp = postJson("/api/dispatches", "RECYCLER", "recycler-1", """
                {"appointmentId": %d, "vehicleId": 1, "driverId": 1, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s"}
                """.formatted(appointment, tomorrowAt(17, 30), tomorrowAt(18, 0)), 400);
        assertThat(resp.path("message").asText()).contains("限行");
    }

    @Test
    @Order(21)
    void 角色鉴权_商户不能派单() throws Exception {
        postJson("/api/dispatches", "MERCHANT", "merchant-1", """
                {"appointmentId": 1, "vehicleId": 1, "driverId": 1, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s"}
                """.formatted(tomorrowAt(10, 0), tomorrowAt(11, 0)), 403);
    }

    // ---------- 报表与风险 ----------

    @Test
    @Order(30)
    void 风险扫描_长期不匹配的门店进入风险名单() throws Exception {
        postJson("/api/risks/scan", "REGULATOR", "regulator-1", "{}", 200);
        JsonNode risks = getJson("/api/risks", "REGULATOR", 200);
        boolean yieldMismatch = false;
        for (JsonNode r : risks.path("data")) {
            if ("YIELD_MISMATCH".equals(r.path("indicator").asText())) {
                yieldMismatch = true;
            }
        }
        // 门店预期产量（60kg/天*90天）远高于实际回收量，应被识别
        assertThat(yieldMismatch).isTrue();
    }

    @Test
    @Order(31)
    void 报表_车辆效率与商户回收证明与监管趋势() throws Exception {
        JsonNode efficiency = getJson("/api/reports/vehicle-efficiency", "RECYCLER", 200);
        assertThat(efficiency.path("data").size()).isGreaterThanOrEqualTo(1);
        JsonNode first = efficiency.path("data").get(0);
        assertThat(first.path("plateNo").asText()).isNotBlank();

        JsonNode certificate = getJson("/api/reports/stores/1/certificate", "MERCHANT", 200);
        assertThat(certificate.path("data").path("manifestCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(certificate.path("data").path("totalWeightKg").asDouble()).isGreaterThan(0);

        JsonNode trend = getJson("/api/reports/regulator/trend", "REGULATOR", 200);
        assertThat(trend.path("data").size()).isGreaterThanOrEqualTo(1);
    }

    // ---------- 称重设备离线补录与人工复核 ----------

    /** 创建门店预约并派单、到店核验（可指定门店与离线设备） */
    private long[] prepareLoading(long storeId, long driverId, long vehicleId, String qr,
                                  String deviceCode, int barrels, String estWeight) throws Exception {
        long appointmentId = createAppointment(storeId, barrels, estWeight);
        long dispatchId = planDispatch(appointmentId, vehicleId, driverId,
                tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, qr, deviceCode, driverId == 2 ? "driver-2" : "driver-1");
        return new long[]{appointmentId, dispatchId};
    }

    private JsonNode supplement(long dispatchId, String deviceCode, String itemsJson,
                                String photoUrls, String reason, String driver, int expected)
            throws Exception {
        String body = """
                {"dispatchId": %d, "deviceCode": "%s", "items": %s, "photoUrls": "%s", "reason": "%s"}
                """.formatted(dispatchId, deviceCode, itemsJson, photoUrls, reason);
        return postJson("/api/supplements", "DRIVER", driver, body, expected);
    }

    @Test
    @Order(40)
    void 离线补录_待复核_复核通过打人工标记并正常结算() throws Exception {
        long[] r = prepareLoading(1, 1, 1, "STORE-QR-1", "DEV-004", 2, "300");
        long dispatchId = r[1];

        JsonNode sup = supplement(dispatchId, "DEV-004",
                """
                [{"barrelCode":"BAR-005","weightKg":150,"handoverName":"王店长"},
                 {"barrelCode":"BAR-006","weightKg":146,"handoverName":"王店长"}]
                """,
                "https://img.example.com/scale1.jpg,https://img.example.com/scale2.jpg",
                "蓝牙秤无法连接，拍照手录", "driver-1", 200);
        long supplementId = sup.path("data").path("id").asLong();
        assertThat(sup.path("data").path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(sup.path("data").path("declaredWeightKg").asDouble()).isEqualTo(296.0);

        long manifestId = manifestIdOf(dispatchId);
        JsonNode pending = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        JsonNode pm = pending.path("data").path("manifest");
        assertThat(pm.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(pm.path("pendingReviewCount").asInt()).isEqualTo(1);
        // 复核通过前重量不采信
        assertThat(pm.path("exitWeightKg").asDouble()).isEqualTo(0.0);
        assertThat(pm.path("barrelCountExit").asInt()).isEqualTo(0);
        assertThat(pending.path("data").path("supplements").size()).isEqualTo(1);

        // 待复核期间禁止起运
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 409);

        // 复核台视图：照片、历史重量、补录频次齐备
        JsonNode view = getJson("/api/supplements/" + supplementId + "/review-view", "REGULATOR", 200);
        assertThat(view.path("data").path("photoUrls").size()).isEqualTo(2);
        assertThat(view.path("data").path("declaredAvgWeightPerBarrel").asDouble()).isEqualTo(148.0);
        assertThat(view.path("data").path("barrelHistories").size()).isEqualTo(2);
        assertThat(view.path("data").path("windowSupplementCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(view.path("data").path("driver").path("creditScore").asInt()).isEqualTo(100);

        // 复核通过：照片清晰 + 桶数相符
        postJson("/api/supplements/" + supplementId + "/review", "RECYCLER", "recycler-1", """
                {"decision":"APPROVED","photoClear":true,"barrelCountMatch":true,
                 "comment":"照片读数清晰，2 桶数量相符"}
                """, 200);

        JsonNode approved = getJson("/api/manifests/" + manifestId, "REGULATOR", 200).path("data");
        JsonNode m = approved.path("manifest");
        assertThat(m.path("status").asText()).isEqualTo("CREATED");
        assertThat(m.path("pendingReviewCount").asInt()).isEqualTo(0);
        assertThat(m.path("manualSupplementFlag").asBoolean()).isTrue();
        assertThat(m.path("exitWeightKg").asDouble()).isEqualTo(296.0);
        assertThat(m.path("barrelCountExit").asInt()).isEqualTo(2);
        assertThat(m.path("manualWeighCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(approved.path("weighings").get(0).path("reviewStatus").asText()).isEqualTo("APPROVED");

        // 复核通过后可正常起运、到厂、闭环结算
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);
        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 294, "barrelCount": 2,
                 "qualityCheck": "OK", "decision": "ACCEPT", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);
        JsonNode closed = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(closed.path("data").path("manifest").path("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.path("data").path("settlement").path("amount").asDouble()).isEqualTo(940.8);

        // 监管抽查优先查看人工补录批次
        JsonNode flagged = getJson("/api/manifests?manualSupplement=true", "REGULATOR", 200);
        boolean contains = false;
        for (JsonNode x : flagged.path("data")) {
            if (x.path("id").asLong() == manifestId) {
                contains = true;
            }
            assertThat(x.path("manualSupplementFlag").asBoolean()).isTrue();
        }
        assertThat(contains).isTrue();
    }

    @Test
    @Order(41)
    void 离线补录_在线设备拒绝且照片必填() throws Exception {
        postJson("/api/weighings/devices/DEV-001/heartbeat", "RECYCLER", "recycler-1", "{}", 200);
        long[] r = prepareLoading(1, 1, 1, "STORE-QR-1", "DEV-001", 1, "100");
        long dispatchId = r[1];

        // 设备在线 → 必须走直传，不能补录
        supplement(dispatchId, "DEV-001",
                "[{\"barrelCode\":\"BAR-004\",\"weightKg\":90}]",
                "https://img.example.com/s.jpg", "蓝牙连不上", "driver-1", 409);

        // 离线设备但无照片 → 拒绝
        supplement(dispatchId, "DEV-004",
                "[{\"barrelCode\":\"BAR-004\",\"weightKg\":90}]",
                "", "蓝牙连不上", "driver-1", 400);

        // 司机无权访问复核台
        mvc.perform(get("/api/supplements/1/review-view")
                        .header("X-Role", "DRIVER").header("X-Account-Id", "driver-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(42)
    void 频繁离线_设备检查工单并限制司机补录_核查解除后恢复() throws Exception {
        // 第 1 次离线补录（driver-2 / DEV-006）
        long[] a = prepareLoading(3, 2, 2, "STORE-QR-3", "DEV-006", 1, "100");
        supplement(a[1], "DEV-006", "[{\"barrelCode\":\"BAR-203\",\"weightKg\":80}]",
                "https://img.example.com/a.jpg", "设备无响应", "driver-2", 200);
        JsonNode profile1 = getJson("/api/supplements/drivers/2", "RECYCLER", 200);
        assertThat(profile1.path("data").path("manualBlocked").asBoolean()).isFalse();

        // 第 2 次：达到测试阈值（2 次）→ 限制司机 + 设备检查工单
        long[] b = prepareLoading(3, 2, 2, "STORE-QR-3", "DEV-006", 1, "100");
        supplement(b[1], "DEV-006", "[{\"barrelCode\":\"BAR-204\",\"weightKg\":82}]",
                "https://img.example.com/b.jpg", "设备无响应", "driver-2", 200);
        JsonNode profile2 = getJson("/api/supplements/drivers/2", "RECYCLER", 200);
        assertThat(profile2.path("data").path("manualBlocked").asBoolean()).isTrue();
        assertThat(profile2.path("data").path("windowSupplementCount").asInt()).isGreaterThanOrEqualTo(2);

        JsonNode deviceTickets = getJson("/api/exceptions/by-ref?refType=DEVICE&refId=6", "REGULATOR", 200);
        boolean deviceCheck = false;
        for (JsonNode t : deviceTickets.path("data")) {
            if ("DEVICE_CHECK".equals(t.path("type").asText())) {
                deviceCheck = true;
            }
        }
        assertThat(deviceCheck).isTrue();

        // 被限制后再次补录 → 拒绝
        long[] c = prepareLoading(3, 2, 2, "STORE-QR-3", "DEV-006", 1, "100");
        supplement(c[1], "DEV-006", "[{\"barrelCode\":\"BAR-205\",\"weightKg\":85}]",
                "https://img.example.com/c.jpg", "设备无响应", "driver-2", 409);

        // 回收公司核查设备后解除 → 频次重新起算，可继续补录
        postJson("/api/supplements/drivers/2/unblock", "RECYCLER", "recycler-1",
                "{\"comment\":\"已更换蓝牙模块并现场复测正常\"}", 200);
        JsonNode profile3 = getJson("/api/supplements/drivers/2", "RECYCLER", 200);
        assertThat(profile3.path("data").path("manualBlocked").asBoolean()).isFalse();
        supplement(c[1], "DEV-006", "[{\"barrelCode\":\"BAR-205\",\"weightKg\":85}]",
                "https://img.example.com/c.jpg", "设备无响应", "driver-2", 200);
    }

    @Test
    @Order(43)
    void 复核驳回_回滚重量_油桶退回_扣减信用并留痕() throws Exception {
        long[] r = prepareLoading(1, 1, 1, "STORE-QR-1", "DEV-004", 1, "100");
        long dispatchId = r[1];
        JsonNode sup = supplement(dispatchId, "DEV-004",
                "[{\"barrelCode\":\"BAR-004\",\"weightKg\":95,\"handoverName\":\"王店长\"}]",
                "https://img.example.com/blur.jpg", "蓝牙秤离线", "driver-1", 200);
        long supplementId = sup.path("data").path("id").asLong();
        long manifestId = manifestIdOf(dispatchId);

        // 驳回不填意见 → 拒绝；照片不清晰却通过 → 拒绝
        postJson("/api/supplements/" + supplementId + "/review", "RECYCLER", "recycler-1",
                "{\"decision\":\"REJECTED\",\"photoClear\":false,\"barrelCountMatch\":true}", 400);
        postJson("/api/supplements/" + supplementId + "/review", "RECYCLER", "recycler-1",
                "{\"decision\":\"APPROVED\",\"photoClear\":false,\"barrelCountMatch\":true}", 400);

        // 正式驳回
        postJson("/api/supplements/" + supplementId + "/review", "RECYCLER", "recycler-1", """
                {"decision":"REJECTED","photoClear":false,"barrelCountMatch":false,
                 "comment":"照片模糊无法辨认秤面读数，桶数与现场不符"}
                """, 200);

        JsonNode after = getJson("/api/manifests/" + manifestId, "REGULATOR", 200).path("data");
        assertThat(after.path("manifest").path("status").asText()).isEqualTo("CREATED");
        assertThat(after.path("manifest").path("manualSupplementFlag").asBoolean()).isFalse();
        assertThat(after.path("manifest").path("exitWeightKg").asDouble()).isEqualTo(0.0);
        assertThat(after.path("manifest").path("pendingReviewCount").asInt()).isEqualTo(0);
        // 待复核称重明细已删除
        assertThat(after.path("weighings").size()).isEqualTo(0);

        // 油桶退回在店状态
        JsonNode trace = getJson("/api/barrels/BAR-004/trace", "REGULATOR", 200);
        assertThat(trace.path("data").path("barrel").path("status").asText()).isEqualTo("AT_STORE");

        // 司机信用扣减（100 - 20 = 80）
        JsonNode profile = getJson("/api/supplements/drivers/1", "RECYCLER", 200);
        assertThat(profile.path("data").path("creditScore").asInt()).isEqualTo(80);
        assertThat(profile.path("data").path("windowRejectedCount").asInt()).isGreaterThanOrEqualTo(1);

        // 异常补录留痕
        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "REGULATOR", 200);
        boolean rejectedTicket = false;
        for (JsonNode t : tickets.path("data")) {
            if ("SUPPLEMENT_REJECTED".equals(t.path("type").asText())) {
                rejectedTicket = true;
            }
        }
        assertThat(rejectedTicket).isTrue();
    }
}
