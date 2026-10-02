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
 * 称重设备离线补录端到端测试：
 * 蓝牙秤无法连接 → 拍摄秤面照片手动补录 → 联单待复核 →
 * 复核（照片清晰度/油桶数量/历史重量/司机补录频次）→ 通过打标放行结算 /
 * 驳回扣司机信用阻断闭环；同一司机频繁离线 → 工单要求检查设备或限制补录。
 *
 * <p>使用门店 3（老城大排档）+ 司机 2（李师傅）+ 车辆 2，与主流程测试数据隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OfflineSupplementIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    AuditLogRepository auditLogRepository;

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

    private JsonNode getJson(String url, String role, String account, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(url).header("X-Role", role).header("X-Account-Id", account))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String tomorrowAt(int hour, int minute) {
        return LocalDate.now(ZONE).plusDays(1).atTime(LocalTime.of(hour, minute))
                .atZone(ZONE).toInstant().toString();
    }

    /** 门店 3 预约 + 派单（车辆 2 / 司机 2）并到店核验，返回派单 ID */
    private long newLoadingDispatch() throws Exception {
        return newLoadingDispatch("driver-2", 2, 2);
    }

    /** 门店 3 预约 + 派单并到店核验，返回派单 ID */
    private long newLoadingDispatch(String driverAccount, long driverId, long vehicleId) throws Exception {
        JsonNode appointment = postJson("/api/appointments", "MERCHANT", "merchant-2", """
                {"storeId": 3, "barrelCount": 1, "estimatedWeightKg": 100,
                 "windowStart": "%s", "windowEnd": "%s"}
                """.formatted(tomorrowAt(10, 0), tomorrowAt(11, 0)), 200);
        long appointmentId = appointment.path("data").path("id").asLong();
        JsonNode dispatch = postJson("/api/dispatches", "RECYCLER", "recycler-1", """
                {"appointmentId": %d, "vehicleId": %d, "driverId": %d, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s", "routeNote": "夜市→经开区"}
                """.formatted(appointmentId, vehicleId, driverId, tomorrowAt(10, 0), tomorrowAt(11, 0)), 200);
        long dispatchId = dispatch.path("data").path("id").asLong();
        postJson("/api/dispatches/" + dispatchId + "/depart", "DRIVER", driverAccount, "{}", 200);
        postJson("/api/dispatches/" + dispatchId + "/arrive", "DRIVER", driverAccount, "{}", 200);
        postJson("/api/dispatches/" + dispatchId + "/verify", "DRIVER", driverAccount, """
                {"storeQr": "STORE-QR-3", "deviceCode": "DEV-003", "handoverName": "刘老板"}
                """, 200);
        return dispatchId;
    }

    private long manifestIdOf(long dispatchId) throws Exception {
        JsonNode resp = getJson("/api/manifests", "REGULATOR", "regulator-1", 200);
        for (JsonNode m : resp.path("data")) {
            if (m.path("dispatchId").asLong() == dispatchId) {
                return m.path("id").asLong();
            }
        }
        throw new IllegalStateException("未找到派单 " + dispatchId + " 对应的联单");
    }

    private JsonNode manifestOf(long manifestId) throws Exception {
        return getJson("/api/manifests/" + manifestId, "REGULATOR", "regulator-1", 200)
                .path("data").path("manifest");
    }

    private void offlineSupplement(long dispatchId, String barrelCode, String weight) throws Exception {
        offlineSupplement(dispatchId, "driver-2", barrelCode, weight);
    }

    private void offlineSupplement(long dispatchId, String driverAccount, String barrelCode, String weight) throws Exception {
        postJson("/api/weighings", "DRIVER", driverAccount, """
                {"dispatchId": %d, "barrelCode": "%s", "weightKg": %s, "source": "OFFLINE_MANUAL",
                 "scalePhotoUrl": "https://img.example.com/scale-%s.jpg",
                 "handoverName": "刘老板", "note": "蓝牙秤无法连接，拍摄秤面照片人工读数"}
                """.formatted(dispatchId, barrelCode, weight, barrelCode), 200);
    }

    private void transitAndReceive(long dispatchId, long manifestId, String arrivalWeight) throws Exception {
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-2", "{}", 200);
        postJson("/api/tracks/" + dispatchId, "DRIVER", "driver-2", """
                {"points": [{"lat": 30.62, "lng": 104.03, "recordedAt": "%s"},
                            {"lat": 30.58, "lng": 104.12, "recordedAt": "%s"}]}
                """.formatted(Instant.now(), Instant.now()), 200);
        postJson("/api/dispatches/" + dispatchId + "/arrive-plant", "DRIVER", "driver-2", "{}", 200);
        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": %s, "barrelCount": 1,
                 "qualityCheck": "OK", "decision": "ACCEPT", "receivedBy": "厂验收员"}
                """.formatted(manifestId, arrivalWeight), 200);
    }

    private long reviewIdOf(long manifestId) throws Exception {
        JsonNode queue = getJson("/api/supplement-reviews?status=PENDING", "RECYCLER", "recycler-1", 200);
        for (JsonNode r : queue.path("data")) {
            if (r.path("manifestId").asLong() == manifestId) {
                return r.path("id").asLong();
            }
        }
        throw new IllegalStateException("未找到联单 " + manifestId + " 的待复核单");
    }

    // ---------- 复核通过全流程 ----------

    @Test
    @Order(1)
    void 离线补录_待复核_复核通过后打标并放行结算() throws Exception {
        long dispatchId = newLoadingDispatch();

        // 无原因 → 400；无秤面照片 → 400
        postJson("/api/weighings", "DRIVER", "driver-2", """
                {"dispatchId": %d, "barrelCode": "BAR-201", "weightKg": 120, "source": "OFFLINE_MANUAL",
                 "scalePhotoUrl": "https://img.example.com/scale.jpg"}
                """.formatted(dispatchId), 400);
        postJson("/api/weighings", "DRIVER", "driver-2", """
                {"dispatchId": %d, "barrelCode": "BAR-201", "weightKg": 120, "source": "OFFLINE_MANUAL",
                 "note": "蓝牙秤无法连接"}
                """.formatted(dispatchId), 400);

        // 拍摄秤面照片 + 原因 → 补录成功
        offlineSupplement(dispatchId, "BAR-201", "120");
        long manifestId = manifestIdOf(dispatchId);

        // 联单进入待复核：补录/离线计数留痕
        JsonNode manifest = manifestOf(manifestId);
        assertThat(manifest.path("supplementReviewStatus").asText()).isEqualTo("PENDING");
        assertThat(manifest.path("manualWeighCount").asInt()).isEqualTo(1);
        assertThat(manifest.path("offlineWeighCount").asInt()).isEqualTo(1);
        assertThat(manifest.path("manualSupplementFlag").asBoolean()).isFalse();

        // 复核工作台：秤面照片、油桶数量、历史重量、司机补录频次与信用
        long reviewId = reviewIdOf(manifestId);
        JsonNode detail = getJson("/api/supplement-reviews/" + reviewId, "RECYCLER", "recycler-1", 200)
                .path("data");
        assertThat(detail.path("supplements").size()).isEqualTo(1);
        assertThat(detail.path("supplements").get(0).path("scalePhotoUrl").asText())
                .isEqualTo("https://img.example.com/scale-BAR-201.jpg");
        assertThat(detail.path("barrelCountExit").asInt()).isEqualTo(1);
        assertThat(detail.path("appointmentBarrelCount").asInt()).isEqualTo(1);
        assertThat(detail.path("histories").get(0).path("barrelCode").asText()).isEqualTo("BAR-201");
        assertThat(detail.path("histories").get(0).path("recentWeighings").size()).isEqualTo(1);
        JsonNode driver = detail.path("driver");
        assertThat(driver.path("driverId").asLong()).isEqualTo(2L);
        assertThat(driver.path("creditScore").asInt()).isEqualTo(100);
        assertThat(driver.path("offlineSupplementsInWindow").asInt()).isEqualTo(1);

        // 运输到处置厂收料：合规通过但复核未完成 → 待复核，不生成结算
        transitAndReceive(dispatchId, manifestId, "118");
        JsonNode pending = getJson("/api/manifests/" + manifestId, "REGULATOR", "regulator-1", 200)
                .path("data");
        assertThat(pending.path("manifest").path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(pending.path("manifest").path("complianceResult").asText()).isEqualTo("PASS");
        assertThat(pending.path("settlement").isNull()).isTrue();

        // 复核通过（登记照片清晰度）→ 联单打人工补录标记并闭环结算
        postJson("/api/supplement-reviews/" + reviewId + "/approve", "RECYCLER", "recycler-1",
                "{\"photoClarity\": \"CLEAR\", \"note\": \"秤面照片清晰，读数与历史重量一致\"}", 200);
        JsonNode closed = getJson("/api/manifests/" + manifestId, "REGULATOR", "regulator-1", 200)
                .path("data");
        assertThat(closed.path("manifest").path("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.path("manifest").path("supplementReviewStatus").asText()).isEqualTo("APPROVED");
        assertThat(closed.path("manifest").path("manualSupplementFlag").asBoolean()).isTrue();
        assertThat(closed.path("supplementReview").path("photoClarity").asText()).isEqualTo("CLEAR");
        // 结算金额 = 118 * 3.20 = 377.60
        assertThat(closed.path("settlement").path("amount").asDouble()).isEqualTo(377.60);

        // 重复复核 → 409
        postJson("/api/supplement-reviews/" + reviewId + "/approve", "RECYCLER", "recycler-1",
                "{\"photoClarity\": \"CLEAR\"}", 409);

        // 监管抽查优先查看：带人工补录标记的联单进入优先抽查目标
        JsonNode targets = getJson("/api/inspections/priority-targets", "REGULATOR", "regulator-1", 200);
        boolean found = false;
        for (JsonNode t : targets.path("data")) {
            if (t.path("manifest").path("id").asLong() == manifestId) {
                found = true;
                assertThat(t.path("inspected").asBoolean()).isFalse();
            }
        }
        assertThat(found).isTrue();

        // 复核事件进入审计流水
        await().atMost(15, TimeUnit.SECONDS).until(() -> auditLogRepository.findAll().stream()
                .anyMatch(a -> "SupplementReviewApproved".equals(a.getEventType())));
    }

    // ---------- 复核驳回 ----------

    @Test
    @Order(2)
    void 复核驳回_扣司机信用_阻断闭环_工单处置后恢复() throws Exception {
        long dispatchId = newLoadingDispatch();
        offlineSupplement(dispatchId, "BAR-202", "100");
        long manifestId = manifestIdOf(dispatchId);
        long reviewId = reviewIdOf(manifestId);

        // 驳回必须填原因
        postJson("/api/supplement-reviews/" + reviewId + "/reject", "RECYCLER", "recycler-1",
                "{\"photoClarity\": \"BLURRY\"}", 400);
        // 必须登记照片清晰度
        postJson("/api/supplement-reviews/" + reviewId + "/reject", "RECYCLER", "recycler-1",
                "{\"note\": \"读数无法辨认\"}", 400);

        // 收料后联单待复核
        transitAndReceive(dispatchId, manifestId, "100");
        assertThat(manifestOf(manifestId).path("status").asText()).isEqualTo("PENDING_REVIEW");

        // 复核驳回：异常补录 → 司机信用 100 → 90，联单转异常，登记阻断工单
        postJson("/api/supplement-reviews/" + reviewId + "/reject", "RECYCLER", "recycler-1",
                "{\"photoClarity\": \"BLURRY\", \"note\": \"秤面照片模糊，读数无法辨认，疑似虚报\"}", 200);
        JsonNode manifest = manifestOf(manifestId);
        assertThat(manifest.path("status").asText()).isEqualTo("EXCEPTION");
        assertThat(manifest.path("supplementReviewStatus").asText()).isEqualTo("REJECTED");
        assertThat(manifest.path("manualSupplementFlag").asBoolean()).isFalse();

        JsonNode drivers = getJson("/api/drivers", "RECYCLER", "recycler-1", 200);
        for (JsonNode d : drivers.path("data")) {
            if (d.path("driver").path("id").asLong() == 2L) {
                assertThat(d.path("driver").path("creditScore").asInt()).isEqualTo(90);
            }
        }

        // 阻断工单处置后联单重估闭环
        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "RECYCLER", "recycler-1", 200);
        long ticketId = -1;
        for (JsonNode t : tickets.path("data")) {
            if ("OFFLINE_SUPPLEMENT_REJECTED".equals(t.path("type").asText())) {
                assertThat(t.path("blocking").asBoolean()).isTrue();
                ticketId = t.path("id").asLong();
            }
        }
        assertThat(ticketId).isPositive();
        postJson("/api/exceptions/" + ticketId + "/resolve", "RECYCLER", "recycler-1",
                "{\"resolution\": \"已联系门店现场复磅，确认重量无误\"}", 200);
        assertThat(manifestOf(manifestId).path("status").asText()).isEqualTo("CLOSED");
    }

    // ---------- 频繁设备离线 ----------

    @Test
    @Order(3)
    void 频繁设备离线_登记工单_限制补录与解除() throws Exception {
        // 司机 3（王师傅）专职本场景：补录频次计数不受其他用例影响
        // 第 1、2 次离线补录：不触发频繁离线工单
        long d1 = newLoadingDispatch("driver-3", 3, 1);
        offlineSupplement(d1, "driver-3", "BAR-203", "110");
        long d2 = newLoadingDispatch("driver-3", 3, 1);
        offlineSupplement(d2, "driver-3", "BAR-204", "115");
        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=DRIVER&refId=3",
                "RECYCLER", "recycler-1", 200);
        for (JsonNode t : tickets.path("data")) {
            assertThat(t.path("type").asText()).isNotEqualTo("DRIVER_OFFLINE_FREQUENT");
        }

        // 第 3 次（达阈值）：登记频繁离线工单，要求回收公司检查设备或限制补录
        long d3 = newLoadingDispatch("driver-3", 3, 1);
        offlineSupplement(d3, "driver-3", "BAR-205", "118");
        tickets = getJson("/api/exceptions/by-ref?refType=DRIVER&refId=3",
                "RECYCLER", "recycler-1", 200);
        boolean hasFrequent = false;
        for (JsonNode t : tickets.path("data")) {
            if ("DRIVER_OFFLINE_FREQUENT".equals(t.path("type").asText())) {
                hasFrequent = true;
                assertThat(t.path("description").asText()).contains("检查称重设备");
            }
        }
        assertThat(hasFrequent).isTrue();

        // 司机列表可见补录频次
        JsonNode drivers = getJson("/api/drivers", "RECYCLER", "recycler-1", 200);
        for (JsonNode d : drivers.path("data")) {
            if (d.path("driver").path("id").asLong() == 3L) {
                assertThat(d.path("offlineSupplementsInWindow").asInt()).isEqualTo(3);
            }
        }

        // 回收公司限制该司机继续手动补录
        postJson("/api/drivers/3/restrict", "RECYCLER", "recycler-1",
                "{\"reason\": \"频繁设备离线，暂停手动补录权限，待检查称重设备\"}", 200);
        long d4 = newLoadingDispatch("driver-3", 3, 1);
        JsonNode resp = postJson("/api/weighings", "DRIVER", "driver-3", """
                {"dispatchId": %d, "barrelCode": "BAR-203", "weightKg": 100, "source": "OFFLINE_MANUAL",
                 "scalePhotoUrl": "https://img.example.com/scale-x.jpg", "note": "蓝牙秤无法连接"}
                """.formatted(d4), 409);
        assertThat(resp.path("message").asText()).contains("已被限制手动补录");

        // 设备检查完毕解除限制
        postJson("/api/drivers/3/unrestrict", "RECYCLER", "recycler-1", "{}", 200);
        JsonNode after = getJson("/api/drivers", "RECYCLER", "recycler-1", 200);
        for (JsonNode d : after.path("data")) {
            if (d.path("driver").path("id").asLong() == 3L) {
                assertThat(d.path("driver").path("manualRestricted").asBoolean()).isFalse();
            }
        }
    }

    // ---------- 鉴权 ----------

    @Test
    @Order(4)
    void 复核与司机管理_角色鉴权() throws Exception {
        // 司机不能查看复核队列
        getJson("/api/supplement-reviews", "DRIVER", "driver-2", 403);
        // 商户不能限制司机
        postJson("/api/drivers/2/restrict", "MERCHANT", "merchant-1",
                "{\"reason\": \"越权操作\"}", 403);
        // 商户不能查看优先抽查目标
        getJson("/api/inspections/priority-targets", "MERCHANT", "merchant-1", 403);
    }
}
