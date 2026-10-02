# 城市餐厨废油回收预约称重联单合规追踪服务

覆盖 **预约 → 派单 → 到店扫码核验 → 称重（含蓝牙秤离线拍照补录+人工复核）→ 运输轨迹 → 处置厂二次称重 → 合规闭环 → 结算** 的全链路，
以 **电子联单为单一口径**（司机、商户、处置厂、监管端读取同一份数据），
并处理取消、迟到、混入厨余、设备离线、**离线拍照补录待复核**、手动补录、轨迹缺失、拒收、重量质疑、监管抽查等异常，
对长期产量/营业规模/回收频次不匹配的门店自动生成风险名单，对频繁离线补录的司机扣减信用并限制补录。

## 技术栈

Java 17 · Spring Boot 3.3 · Spring Data JPA · Spring Kafka · PostgreSQL 15 · Kafka 3.8（KRaft）· Flyway · Maven

架构要点：

- **电子联单（manifests）单一口径**：派单时创建联单，称重/收料/结算在同一数据库事务内回写联单，
  各方通过 `GET /api/manifests/{id}` 看到完全相同的全链路数据，不存在各端各存一套口径的问题。
- **Kafka 领域事件总线**：7 个主题承载全部状态变更事件，审计消费者将事件落库 `audit_logs`
  形成不可抵赖的审计流水，支撑监管追溯。
- **合规闭环引擎**：收料时比对出店重量、到厂重量、运输损耗率、油桶数量、轨迹完整性，
  阻断性异常（损耗超标/桶数不符/轨迹缺失）挂起联单，工单处理完毕后自动重估闭环。
- **离线补录人工复核**：到店蓝牙秤无法连接时，司机拍摄秤面照片并逐桶手录重量提交补录批次，
  联单进入 `PENDING_REVIEW`（复核通过前禁止起运/收料/结算，重量不采信）；复核人员在复核台查看
  照片清晰度、油桶数量、历史重量、门店桶均重与司机补录频次，通过后重量才计入联单并给该批联单
  打上 `manualSupplementFlag` 人工补录标记（监管可 `?manualSupplement=true` 优先抽查）；驳回则
  回滚重量、油桶退回在店、登记异常并扣减司机信用。频繁设备离线自动生成设备检查工单，达到频次上限
  的司机被限制继续手动补录，回收公司核查设备后可解除。
- **风险分析**：按 90 天窗口比对门店实际回收量与按营业规模预估的产量，长期不匹配进入风险名单。

## 本地启动

### 方式 A：一键脚本（无 Docker 环境，本仓库验证环境即此方式）

```bash
bash scripts/start-local.sh   # 启动 PostgreSQL(5433) + Kafka(9092) + 应用(8080)
```

### 方式 B：Docker 一键全栈（有 Docker 的开发机）

```bash
docker compose up -d --build   # PostgreSQL(映射到 5433) + Kafka(9092) + 应用(8080)
```

Flyway 自动建表并写入演示数据；应用就绪后访问 http://localhost:8080 。
（也可只起依赖：`docker compose up -d postgres kafka`，再 `mvn spring-boot:run` 本地调试应用。）

> 数据库连接等配置见 `src/main/resources/application.yml`（默认 `localhost:5433/wasteoil`，账号 `wasteoil/wasteoil123`）。

### 接口文档（纯后端服务）

启动后访问：

- Swagger UI：http://localhost:8080/swagger-ui/index.html
- OpenAPI 3 JSON：http://localhost:8080/v3/api-docs

在 Swagger UI 中通过 `X-Role` / `X-Account-Id` 请求头传入下方测试账号即可在线调试。

## 测试账号（轻量鉴权：请求头 `X-Role` + `X-Account-Id`）

| 角色 | X-Role | X-Account-Id | 说明 |
|---|---|---|---|
| 商户 | `MERCHANT` | `merchant-1`（川香餐饮集团）/ `merchant-2`（老城美食广场） | 提交预约、取消、质疑重量、查回收证明与结算 |
| 回收公司 | `RECYCLER` | `recycler-1` | 派单、处理异常工单、查车辆效率 |
| 司机 | `DRIVER` | `driver-1`（张师傅）/ `driver-2`（李师傅） | 出发/到店/扫码核验/称重/轨迹/到厂 |
| 处置厂 | `PLANT` | `plant-1`（绿源废油处置厂） | 二次称重收料、接收/拒收 |
| 监管 | `REGULATOR` | `regulator-1` | 联单追溯、抽查、风险名单、趋势报表 |

演示基础数据（Flyway `V2__seed.sql` + `V3__offline_supplement.sql`）：门店 3 家（含商圈与营业规模）、
油桶 18 只（`BAR-001`~`BAR-208`）、车辆 2 台、司机 2 名（信用分 100）、处置厂 1 家（接收时间 08:00-18:00）、
称重设备 6 台（`DEV-001`~`DEV-003` 在线，`DEV-004`~`DEV-006` 无心跳离线，供离线补录演示）、
春熙路商圈早晚高峰限行规则。

## 验证方式

### 1. 自动化测试（31 个用例）

```bash
mvn test
```

> 集成测试需要本地 PostgreSQL（5433 端口，首次执行 `bash scripts/start-local.sh` 会自动创建 `wasteoil_test` 库）；Kafka 由测试内嵌启动，无需外部依赖。

- 单元测试：派单规划（容量/限行/接收时间窗）、合规闭环比对（损耗/桶数/轨迹/质检）、风险分析（15 个）
- 集成测试 `EndToEndIntegrationTest`：真实 PostgreSQL 测试库 + 内嵌 Kafka + MockMvc，
  覆盖全链路闭环、一桶一码追溯、重量质疑、监管抽查、商户取消、手动补录、拒收、
  损耗异常阻断与恢复、派单校验、角色鉴权、风险扫描、三类报表（12 个），以及
  **离线补录待复核→复核通过打人工标记并结算、在线设备拒绝补录/照片必填、
  频繁离线限录与核查解除、复核驳回回滚+扣信用+留痕（4 个）**

### 2. 端到端冒烟脚本（55 项断言，需应用已启动）

```bash
bash scripts/smoke.sh
```

按真实调用顺序走通：主流程闭环（联单 CLOSED、损耗 2kg、结算 947.2 元）→ 油桶追溯 →
商户质疑（结算冻结→按 297kg 调整为 950.4 元）→ 临时取消（派单/联单联动取消）→
手动补录（无原因 400，有原因留痕）→ 拒收（联单 REJECTED）→ 损耗 20% 阻断闭环（工单处理后恢复 CLOSED）→
抽查/风险名单/车辆效率/回收证明/监管趋势 →
**离线补录（在线拒绝→无照片拒绝→待复核→禁止起运→复核台视图→通过打标→闭环结算→监管优先抽查）→
复核驳回（回滚/退桶/扣信用/留痕）→ 频繁离线（设备检查工单+司机限录+核查解除恢复）** →
Kafka 事件审计流水落库校验。

### 实际验证结论（本次交付前执行）

- `mvn test`：**31/31 通过**（单元 15 + 集成 16，集成测试使用 EmbeddedKafka + PostgreSQL 测试库）
- `bash scripts/smoke.sh`：**55/55 通过**（真实 Kafka KRaft + PostgreSQL 15 + 应用实跑）
- 冒烟期间应用日志 0 条 ERROR；7 个 Kafka 主题全部创建；`audit_logs` 审计流水随事件持续增长
  （含 `OfflineSupplementSubmitted` / `OfflineSupplementReviewed` 补录事件）

## 主要 API

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/appointments` | 商户 | 提交回收预约（门店/桶数/预计重量/存放位置/营业时段/联系人） |
| POST | `/api/appointments/{id}/cancel` | 商户 | 临时取消（联动取消派单与联单，登记异常） |
| POST | `/api/dispatches` | 回收公司 | 生成派单（校验车辆容量、商圈限行、处置厂接收时间窗，同时创建电子联单） |
| POST | `/api/dispatches/{id}/depart` `/arrive` | 司机 | 出发 / 到店（超时自动登记司机迟到） |
| POST | `/api/dispatches/{id}/verify` | 司机 | 扫码核验门店二维码、称重设备、交接人、现场照片 |
| POST | `/api/weighings` | 司机 | 称重（`DEVICE` 直传 / `MANUAL` 手动补录须填原因；离线设备数据打标；受司机补录限制约束） |
| POST | `/api/weighings/devices/{code}/heartbeat` | 设备 | 称重设备心跳 |
| POST | `/api/supplements` | 司机 | 蓝牙秤离线补录：秤面照片 + 逐桶手录重量，联单进入 `PENDING_REVIEW` |
| GET | `/api/supplements?status=` | 回收/监管 | 补录单列表（复核台看 `PENDING_REVIEW`） |
| GET | `/api/supplements/{id}/review-view` | 回收/监管 | 复核台：照片清晰度、油桶数量、历史重量、门店桶均重、司机补录频次与信用 |
| POST | `/api/supplements/{id}/review` | 回收/监管 | 复核 `APPROVED`（须照片清晰+桶数相符）/ `REJECTED`（须意见，扣司机信用） |
| GET | `/api/supplements/drivers/{id}` | 回收/监管 | 司机补录画像：信用分、限录状态、窗口补录/驳回频次、设备检查工单 |
| POST | `/api/supplements/drivers/{id}/unblock` | 回收公司 | 核查设备后解除司机手动补录限制（频次重新起算） |
| POST | `/api/supplements/scan-frequent` | 回收/监管 | 主动扫描频繁离线设备/司机（另有每 10 分钟定时扫描） |
| GET | `/api/manifests?manualSupplement=true` | 各端 | 只返回人工补录标记联单，监管抽查优先查看 |
| POST | `/api/dispatches/{id}/load-complete` | 司机 | 装车完成起运（联单转 IN_TRANSIT） |
| POST | `/api/tracks/{dispatchId}` | 司机 | 批量上传运输轨迹点 |
| POST | `/api/receipts` | 处置厂 | 收料：二次称重 + 质检 + 接收/拒收，自动触发合规比对 |
| GET | `/api/manifests/{id}` | 各端 | 联单全链路详情（单一口径：预约/派单/称重/轨迹/收料/结算/异常） |
| GET | `/api/barrels/{code}/trace` | 各端 | 一桶一码追溯 |
| POST | `/api/disputes` `/api/disputes/{id}/resolve` | 商户/回收公司 | 重量质疑与协商解决（结算冻结/调整） |
| GET/POST | `/api/exceptions` `/api/exceptions/{id}/resolve` | 回收公司/监管 | 异常工单查询与处理（阻断类处理后联单自动重估） |
| POST | `/api/inspections` | 监管 | 抽查登记（不合格自动入风险名单） |
| POST/GET | `/api/risks/scan` `/api/risks` | 监管 | 风险扫描与风险名单 |
| GET | `/api/reports/regulator/trend` | 监管 | 废油流向合规处置的长期趋势 |
| GET | `/api/reports/vehicle-efficiency` | 回收公司 | 车辆效率（趟次/运量/准点） |
| GET | `/api/reports/stores/{id}/certificate` | 商户 | 回收证明（联单清单 + 合计 + 结算金额） |
| GET | `/api/settlements?storeId=` | 商户 | 结算单查询 |

## 异常场景处理一览

| 场景 | 机制 |
|---|---|
| 商户临时取消 | 预约/派单/联单联动取消，登记 `MERCHANT_CANCEL` 工单 |
| 司机迟到 | 到店超时判定 + 每分钟定时扫描，登记 `DRIVER_LATE` |
| 油桶混入厨余 | 收料质检 `CONTAMINATED`，联单标记 `qualityIssue` 并登记工单（不阻断闭环） |
| 称重设备离线 | 心跳超时判定离线，离线数据在联单上计数留痕，登记 `DEVICE_OFFLINE` |
| 蓝牙秤离线补录 | 拍照 + 手录重量提交补录批次，联单 `PENDING_REVIEW` 且禁止起运/结算；复核通过才采信并打人工补录标记 |
| 异常补录复核驳回 | 回滚重量、油桶退回在店，登记 `SUPPLEMENT_REJECTED`，扣减司机信用（信用过低限录） |
| 频繁设备离线 | 设备维度达阈值登记 `DEVICE_CHECK` 要求回收公司检查；司机维度达上限限制手动补录，核查后解除 |
| 司机手动补录 | 必须填写原因，联单记录补录条数，登记 `MANUAL_ENTRY`；同样受补录限制与信用约束 |
| 运输轨迹缺失 | 定时扫描 + 收料时合规校验，阻断闭环直至工单处理 |
| 处置厂拒收 | 联单 `REJECTED`，登记 `PLANT_REJECT` 由回收公司处置 |
| 商户质疑重量 | 联单 `DISPUTED`、结算冻结，协商后按调整重量重新结算并闭环 |
| 监管抽查 | 抽查登记，不合格自动登记异常并将门店列入风险名单 |
| 长期产量不匹配 | 每日定时 + 手动扫描，90 天窗口产量/规模/频次比对，生成风险名单 |

## 目录结构

```
src/main/java/com/wasteoil/
├── appointment/   预约提交与取消
├── dispatch/      派单规划（容量/限行/时间窗）与运输执行
├── weighing/      扫码核验、设备/手动称重、设备心跳
├── supplement/    蓝牙秤离线拍照补录与人工复核、司机信用与频繁离线管控
├── track/         运输轨迹与缺失扫描
├── plant/         处置厂二次称重收料
├── compliance/    合规闭环比对引擎
├── manifest/      电子联单（单一口径）与全链路详情
├── settlement/    结算（联单为唯一结算依据）
├── exception/     异常工单（阻断/非阻断）
├── dispute/       商户重量质疑
├── inspection/    监管抽查
├── risk/          长期趋势风险分析与风险名单
├── report/        监管趋势/车辆效率/回收证明
├── audit+ kafka/  领域事件总线与审计流水落库
└── merchant|barrel/ 基础数据与一桶一码追溯
```
