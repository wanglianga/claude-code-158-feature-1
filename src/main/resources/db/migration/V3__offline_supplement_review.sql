-- ============ 称重设备离线补录与人工复核 ============

-- 司机关系：司机到店蓝牙秤无法连接时，允许拍摄秤面照片并手动录入重量，
-- 该批联单进入待复核，复核通过并打上人工补录标记后才允许进入结算。

-- 司机：信用分与手动补录限制
ALTER TABLE drivers ADD COLUMN credit_score INT NOT NULL DEFAULT 100;
ALTER TABLE drivers ADD COLUMN manual_restricted BOOLEAN NOT NULL DEFAULT FALSE;

-- 联单：补录复核状态（NONE/PENDING/APPROVED/REJECTED）与人工补录标记
ALTER TABLE manifests ADD COLUMN supplement_review_status VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE manifests ADD COLUMN manual_supplement_flag BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_manifests_supplement_review ON manifests (supplement_review_status);
CREATE INDEX idx_manifests_manual_supplement ON manifests (manual_supplement_flag);

-- 称重记录：秤面照片（离线补录必传，复核时核对清晰度）
ALTER TABLE weighing_records ADD COLUMN scale_photo_url VARCHAR(512);

-- 补录复核单：一张联单对应一条复核单（该批联单统一复核）
CREATE TABLE supplement_reviews (
    id                BIGSERIAL PRIMARY KEY,
    manifest_id       BIGINT      NOT NULL UNIQUE REFERENCES manifests (id),
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/APPROVED/REJECTED
    supplement_count  INT         NOT NULL DEFAULT 0,          -- 本批离线补录条数
    photo_clarity     VARCHAR(16),                             -- 复核登记：CLEAR / BLURRY
    review_note       VARCHAR(1024),
    reviewer          VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at       TIMESTAMPTZ
);
CREATE INDEX idx_supplement_reviews_status ON supplement_reviews (status);

-- 演示补充：老城大排档（门店 3）增补油桶，用于离线补录复核场景演示
INSERT INTO barrels (code, capacity_kg, store_id, status) VALUES
    ('BAR-203', 150, 3, 'AT_STORE'),
    ('BAR-204', 150, 3, 'AT_STORE'),
    ('BAR-205', 150, 3, 'AT_STORE');

-- 演示补充：司机 3（王师傅），用于频繁设备离线管控场景
INSERT INTO drivers (id, name, phone, vehicle_id, status) VALUES
    (3, '王师傅', '13700000003', 1, 'ACTIVE');
