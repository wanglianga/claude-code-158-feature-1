-- 称重设备离线补录与人工复核：
-- 司机到店蓝牙秤离线时，可拍摄秤面照片并手动录入重量，联单进入待复核状态；
-- 复核人员核对照片清晰度、油桶数量、历史重量与司机补录频次后决策；
-- 复核通过的批次联单打上人工补录标记，供监管抽查优先查看；
-- 异常补录影响司机信用，频繁离线将要求检查设备直至限制手动补录。

-- ============ 司机信用与补录限制 ============
ALTER TABLE drivers ADD COLUMN credit_score   INT     NOT NULL DEFAULT 100;  -- 信用分，异常补录扣减
ALTER TABLE drivers ADD COLUMN manual_blocked BOOLEAN NOT NULL DEFAULT FALSE; -- 是否被限制手动补录
ALTER TABLE drivers ADD COLUMN last_device_check_at TIMESTAMPTZ; -- 回收公司最近一次核查设备时间，补录频次自此重计

-- ============ 联单：待复核状态聚合字段 + 人工补录标记 ============
ALTER TABLE manifests ADD COLUMN pending_review_count    INT     NOT NULL DEFAULT 0;     -- 待复核补录桶数
ALTER TABLE manifests ADD COLUMN manual_supplement_flag BOOLEAN NOT NULL DEFAULT FALSE; -- 复核通过的人工补录批次标记

-- ============ 称重记录：复核状态与所属补录单 ============
-- PENDING 待复核 / APPROVED 复核通过（正常设备直传亦为 APPROVED）/ REJECTED 复核驳回
ALTER TABLE weighing_records ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE weighing_records ADD COLUMN supplement_id BIGINT;

-- ============ 离线补录单（一次拍照手录对应一张补录单，可含多桶） ============
CREATE TABLE offline_supplements (
    id                    BIGSERIAL PRIMARY KEY,
    supplement_no         VARCHAR(32)   NOT NULL UNIQUE,
    dispatch_id           BIGINT        NOT NULL REFERENCES dispatch_orders (id),
    manifest_id           BIGINT        NOT NULL REFERENCES manifests (id),
    driver_id             BIGINT        NOT NULL REFERENCES drivers (id),
    device_id             BIGINT        NOT NULL REFERENCES weighing_devices (id),
    store_id              BIGINT        NOT NULL REFERENCES stores (id),
    declared_weight_kg    NUMERIC(12, 2) NOT NULL,  -- 司机手录申报总重
    declared_barrel_count INT           NOT NULL,   -- 申报油桶数量
    barrel_codes          VARCHAR(512)  NOT NULL,   -- 申报桶码（逗号分隔，驳回后仍留痕）
    photo_urls            VARCHAR(2048) NOT NULL,   -- 秤面照片（多张以逗号分隔）
    reason                VARCHAR(512)  NOT NULL,   -- 设备离线情况说明
    status                VARCHAR(16)   NOT NULL DEFAULT 'PENDING_REVIEW',
    photo_clear           BOOLEAN,                  -- 复核员判定照片是否清晰
    barrel_count_match    BOOLEAN,                  -- 复核员判定油桶数量是否相符
    reviewer              VARCHAR(64),
    review_comment        VARCHAR(1024),
    reviewed_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_supplement_driver ON offline_supplements (driver_id, created_at);
CREATE INDEX idx_supplement_manifest ON offline_supplements (manifest_id);
CREATE INDEX idx_supplement_status ON offline_supplements (status);

ALTER TABLE weighing_records
    ADD CONSTRAINT fk_weighing_supplement FOREIGN KEY (supplement_id)
        REFERENCES offline_supplements (id);
CREATE INDEX idx_weighing_supplement ON weighing_records (supplement_id);

CREATE SEQUENCE supplement_no_seq START 1;

-- ============ 演示补充油桶（供离线补录流程使用） ============
INSERT INTO barrels (id, code, capacity_kg, store_id, status) VALUES
    (9,  'BAR-005', 200, 1, 'AT_STORE'),
    (10, 'BAR-006', 200, 1, 'AT_STORE'),
    (11, 'BAR-103', 200, 2, 'AT_STORE'),
    (12, 'BAR-104', 200, 2, 'AT_STORE'),
    (13, 'BAR-203', 150, 3, 'AT_STORE'),
    (14, 'BAR-204', 150, 3, 'AT_STORE'),
    (15, 'BAR-205', 150, 3, 'AT_STORE'),
    (16, 'BAR-206', 150, 3, 'AT_STORE'),
    (17, 'BAR-207', 150, 3, 'AT_STORE'),
    (18, 'BAR-208', 150, 3, 'AT_STORE');
SELECT setval('barrels_id_seq', 30);

-- ============ 从未心跳的蓝牙秤（天然离线，供离线补录演示：每门店一台） ============
INSERT INTO weighing_devices (id, code, store_id, last_heartbeat_at, status) VALUES
    (4, 'DEV-004', 1, NULL, 'OFFLINE'),
    (5, 'DEV-005', 2, NULL, 'OFFLINE'),
    (6, 'DEV-006', 3, NULL, 'OFFLINE');
SELECT setval('weighing_devices_id_seq', 10);
