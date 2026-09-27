-- 城市餐厨废油回收预约称重联单合规追踪服务 - 初始 schema

-- ============ 商户与门店 ============
CREATE TABLE merchants (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    license_no    VARCHAR(64)  NOT NULL UNIQUE,
    contact_name  VARCHAR(64)  NOT NULL,
    contact_phone VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE stores (
    id                     BIGSERIAL PRIMARY KEY,
    merchant_id            BIGINT       NOT NULL REFERENCES merchants (id),
    name                   VARCHAR(128) NOT NULL,
    address                VARCHAR(256) NOT NULL,
    district               VARCHAR(64)  NOT NULL,           -- 所属商圈，用于限行规则
    open_time              TIME         NOT NULL,           -- 营业开始
    close_time             TIME         NOT NULL,           -- 营业结束
    contact_name           VARCHAR(64)  NOT NULL,
    contact_phone          VARCHAR(32)  NOT NULL,
    storage_location       VARCHAR(256) NOT NULL,           -- 废油存放位置
    scale_tier             VARCHAR(16)  NOT NULL,           -- 营业规模档位 SMALL/MEDIUM/LARGE
    expected_daily_oil_kg  NUMERIC(10, 2) NOT NULL,         -- 按营业规模预估的日均废油量
    qr_code                VARCHAR(64)  NOT NULL UNIQUE,    -- 门店核验二维码内容
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============ 油桶（一桶一码） ============
CREATE TABLE barrels (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,   -- 桶身二维码
    capacity_kg NUMERIC(10, 2) NOT NULL,
    store_id    BIGINT       NOT NULL REFERENCES stores (id),
    status      VARCHAR(16)  NOT NULL DEFAULT 'AT_STORE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============ 回收公司资源 ============
CREATE TABLE vehicles (
    id          BIGSERIAL PRIMARY KEY,
    plate_no    VARCHAR(32)  NOT NULL UNIQUE,
    capacity_kg NUMERIC(10, 2) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE drivers (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    phone      VARCHAR(32) NOT NULL,
    vehicle_id BIGINT      NOT NULL REFERENCES vehicles (id),
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 处置厂 ============
CREATE TABLE plants (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    address        VARCHAR(256) NOT NULL,
    receiving_open TIME         NOT NULL,   -- 接收窗口开始
    receiving_close TIME        NOT NULL,   -- 接收窗口结束
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============ 商圈限行规则 ============
CREATE TABLE district_restrictions (
    id           BIGSERIAL PRIMARY KEY,
    district     VARCHAR(64) NOT NULL,
    restrict_start TIME      NOT NULL,
    restrict_end   TIME      NOT NULL,
    note         VARCHAR(256)
);

-- ============ 称重设备 ============
CREATE TABLE weighing_devices (
    id                BIGSERIAL PRIMARY KEY,
    code              VARCHAR(64) NOT NULL UNIQUE,
    store_id          BIGINT      NOT NULL REFERENCES stores (id),
    last_heartbeat_at TIMESTAMPTZ,
    status            VARCHAR(16) NOT NULL DEFAULT 'ONLINE',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 回收预约 ============
CREATE TABLE appointments (
    id                  BIGSERIAL PRIMARY KEY,
    store_id            BIGINT        NOT NULL REFERENCES stores (id),
    barrel_count        INT           NOT NULL,
    estimated_weight_kg NUMERIC(10, 2) NOT NULL,
    storage_location    VARCHAR(256)  NOT NULL,
    contact_name        VARCHAR(64)   NOT NULL,
    contact_phone       VARCHAR(32)   NOT NULL,
    window_start        TIMESTAMPTZ   NOT NULL,  -- 期望上门时间窗
    window_end          TIMESTAMPTZ   NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    cancel_reason       VARCHAR(256),
    version             INT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_appointments_store ON appointments (store_id);
CREATE INDEX idx_appointments_status ON appointments (status);

-- ============ 派单 ============
CREATE TABLE dispatch_orders (
    id             BIGSERIAL PRIMARY KEY,
    appointment_id BIGINT      NOT NULL UNIQUE REFERENCES appointments (id),
    vehicle_id     BIGINT      NOT NULL REFERENCES vehicles (id),
    driver_id      BIGINT      NOT NULL REFERENCES drivers (id),
    plant_id       BIGINT      NOT NULL REFERENCES plants (id),
    planned_start  TIMESTAMPTZ NOT NULL,
    planned_end    TIMESTAMPTZ NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
    departed_at    TIMESTAMPTZ,
    arrived_at     TIMESTAMPTZ,
    load_done_at   TIMESTAMPTZ,
    plant_arrived_at TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    route_note     VARCHAR(512),
    version        INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dispatch_status ON dispatch_orders (status);
CREATE INDEX idx_dispatch_vehicle ON dispatch_orders (vehicle_id);

-- ============ 电子联单（单一口径：结算依据 + 合规凭证） ============
CREATE TABLE manifests (
    id                   BIGSERIAL PRIMARY KEY,
    manifest_no          VARCHAR(32)   NOT NULL UNIQUE,
    dispatch_id          BIGINT        NOT NULL UNIQUE REFERENCES dispatch_orders (id),
    store_id             BIGINT        NOT NULL REFERENCES stores (id),
    plant_id             BIGINT        NOT NULL REFERENCES plants (id),
    status               VARCHAR(16)   NOT NULL DEFAULT 'CREATED',
    barrel_count_exit    INT           NOT NULL DEFAULT 0,   -- 出店装车桶数
    barrel_count_arrival INT,                                 -- 到厂桶数
    exit_weight_kg       NUMERIC(12, 2) NOT NULL DEFAULT 0,  -- 出店重量（门店称重合计）
    arrival_weight_kg    NUMERIC(12, 2),                     -- 到厂重量（处置厂二次称重）
    loss_kg              NUMERIC(12, 2),                     -- 运输损耗
    loss_rate            NUMERIC(8, 4),                      -- 损耗率
    manual_weigh_count   INT           NOT NULL DEFAULT 0,   -- 手动补录条数
    offline_weigh_count  INT           NOT NULL DEFAULT 0,   -- 设备离线称重条数
    track_missing        BOOLEAN       NOT NULL DEFAULT FALSE,
    quality_issue        BOOLEAN       NOT NULL DEFAULT FALSE,
    compliance_result    VARCHAR(32),                        -- PASS / EXCEPTION
    exception_note       VARCHAR(1024),
    version              INT           NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_manifests_store ON manifests (store_id);
CREATE INDEX idx_manifests_status ON manifests (status);

CREATE SEQUENCE manifest_no_seq START 1;

-- ============ 称重记录 ============
CREATE TABLE weighing_records (
    id            BIGSERIAL PRIMARY KEY,
    dispatch_id   BIGINT        NOT NULL REFERENCES dispatch_orders (id),
    manifest_id   BIGINT        NOT NULL REFERENCES manifests (id),
    barrel_id     BIGINT        NOT NULL REFERENCES barrels (id),
    device_id     BIGINT        REFERENCES weighing_devices (id),
    weight_kg     NUMERIC(10, 2) NOT NULL,
    source        VARCHAR(16)   NOT NULL,       -- DEVICE / MANUAL
    device_offline BOOLEAN      NOT NULL DEFAULT FALSE,
    photo_url     VARCHAR(512),
    handover_name VARCHAR(64),                  -- 交接人
    note          VARCHAR(512),
    weighed_at    TIMESTAMPTZ   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (dispatch_id, barrel_id)             -- 同一趟同一桶只称一次
);
CREATE INDEX idx_weighing_barrel ON weighing_records (barrel_id);

-- ============ 扫码核验记录 ============
CREATE TABLE pickup_verifications (
    id           BIGSERIAL PRIMARY KEY,
    dispatch_id  BIGINT      NOT NULL REFERENCES dispatch_orders (id),
    store_qr     VARCHAR(64) NOT NULL,
    device_code  VARCHAR(64),
    handover_name VARCHAR(64) NOT NULL,
    photo_urls   VARCHAR(2048),
    verified_by  VARCHAR(64) NOT NULL,
    verified_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 运输轨迹 ============
CREATE TABLE track_points (
    id          BIGSERIAL PRIMARY KEY,
    dispatch_id BIGINT         NOT NULL REFERENCES dispatch_orders (id),
    lat         NUMERIC(10, 6) NOT NULL,
    lng         NUMERIC(10, 6) NOT NULL,
    recorded_at TIMESTAMPTZ    NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_track_dispatch ON track_points (dispatch_id, recorded_at);

-- ============ 处置厂收料 ============
CREATE TABLE plant_receipts (
    id                BIGSERIAL PRIMARY KEY,
    manifest_id       BIGINT        NOT NULL UNIQUE REFERENCES manifests (id),
    arrival_weight_kg NUMERIC(12, 2) NOT NULL,
    barrel_count      INT           NOT NULL,
    quality_check     VARCHAR(16)   NOT NULL,   -- OK / CONTAMINATED(混入厨余)
    decision          VARCHAR(16)   NOT NULL,   -- ACCEPT / REJECT
    reject_reason     VARCHAR(512),
    received_by       VARCHAR(64)   NOT NULL,
    received_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ============ 异常工单 ============
CREATE TABLE exception_tickets (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(40)  NOT NULL,
    ref_type    VARCHAR(32)  NOT NULL,   -- APPOINTMENT/DISPATCH/MANIFEST/...
    ref_id      BIGINT       NOT NULL,
    description VARCHAR(1024) NOT NULL,
    blocking    BOOLEAN      NOT NULL DEFAULT FALSE,  -- 是否阻断联单闭环
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    handler     VARCHAR(64),
    resolution  VARCHAR(1024),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    handled_at  TIMESTAMPTZ
);
CREATE INDEX idx_tickets_ref ON exception_tickets (ref_type, ref_id);
CREATE INDEX idx_tickets_status ON exception_tickets (status);

-- ============ 商户重量质疑 ============
CREATE TABLE disputes (
    id                 BIGSERIAL PRIMARY KEY,
    manifest_id        BIGINT       NOT NULL REFERENCES manifests (id),
    merchant_id        BIGINT       NOT NULL REFERENCES merchants (id),
    reason             VARCHAR(1024) NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    resolution         VARCHAR(1024),
    adjusted_weight_kg NUMERIC(12, 2),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at        TIMESTAMPTZ
);

-- ============ 监管抽查 ============
CREATE TABLE inspections (
    id          BIGSERIAL PRIMARY KEY,
    manifest_id BIGINT      NOT NULL REFERENCES manifests (id),
    inspector   VARCHAR(64) NOT NULL,
    result      VARCHAR(16) NOT NULL,   -- PASS / FAIL
    note        VARCHAR(1024),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 结算单 ============
CREATE TABLE settlements (
    id           BIGSERIAL PRIMARY KEY,
    manifest_id  BIGINT         NOT NULL UNIQUE REFERENCES manifests (id),
    store_id     BIGINT         NOT NULL REFERENCES stores (id),
    weight_kg    NUMERIC(12, 2) NOT NULL,   -- 结算重量（默认到厂重量）
    unit_price   NUMERIC(10, 2) NOT NULL,
    amount       NUMERIC(12, 2) NOT NULL,
    status       VARCHAR(16)    NOT NULL DEFAULT 'PENDING',  -- PENDING/FROZEN/CONFIRMED
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- ============ 风险名单 ============
CREATE TABLE risk_entries (
    id           BIGSERIAL PRIMARY KEY,
    store_id     BIGINT       NOT NULL REFERENCES stores (id),
    indicator    VARCHAR(64)  NOT NULL,   -- YIELD_MISMATCH / NO_RECYCLING / MANUAL_WEIGH_HEAVY / INSPECTION_FAIL
    detail       VARCHAR(1024) NOT NULL,
    score        INT          NOT NULL,
    window_start DATE         NOT NULL,
    window_end   DATE         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'OPEN',  -- OPEN/CONFIRMED/DISMISSED
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_store ON risk_entries (store_id);

-- ============ 审计流水（Kafka 事件落库，全链路留痕） ============
CREATE TABLE audit_logs (
    id         BIGSERIAL PRIMARY KEY,
    topic      VARCHAR(64)  NOT NULL,
    event_id   VARCHAR(64)  NOT NULL,
    event_type VARCHAR(64)  NOT NULL,
    payload    TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_type ON audit_logs (event_type);
