-- 演示种子数据（含 README 中的测试账号对应主体）

INSERT INTO merchants (id, name, license_no, contact_name, contact_phone) VALUES
    (1, '川香餐饮集团', 'LIC-CX-2024-001', '王经理', '13800000001'),
    (2, '老城美食广场', 'LIC-LC-2024-002', '刘经理', '13800000002');

INSERT INTO stores (id, merchant_id, name, address, district, open_time, close_time,
                    contact_name, contact_phone, storage_location, scale_tier,
                    expected_daily_oil_kg, qr_code, status) VALUES
    (1, 1, '川香火锅·春熙路店', '春熙路 88 号', '春熙路商圈', '09:00', '22:00',
     '王店长', '13900000001', '后厨西侧隔油池旁', 'LARGE', 60.00, 'STORE-QR-1', 'ACTIVE'),
    (2, 1, '川香火锅·金融城店', '金融城 12 号', '金融城商圈', '10:00', '21:00',
     '陈店长', '13900000002', '负一层垃圾房', 'MEDIUM', 35.00, 'STORE-QR-2', 'ACTIVE'),
    (3, 2, '老城大排档', '夜市街 6 号', '老城夜市', '16:00', '23:59',
     '刘老板', '13900000003', '店后巷油桶区', 'SMALL', 15.00, 'STORE-QR-3', 'ACTIVE');

INSERT INTO barrels (id, code, capacity_kg, store_id, status) VALUES
    (1, 'BAR-001', 200, 1, 'AT_STORE'),
    (2, 'BAR-002', 200, 1, 'AT_STORE'),
    (3, 'BAR-003', 200, 1, 'AT_STORE'),
    (4, 'BAR-004', 200, 1, 'AT_STORE'),
    (5, 'BAR-101', 200, 2, 'AT_STORE'),
    (6, 'BAR-102', 200, 2, 'AT_STORE'),
    (7, 'BAR-201', 150, 3, 'AT_STORE'),
    (8, 'BAR-202', 150, 3, 'AT_STORE');

INSERT INTO vehicles (id, plate_no, capacity_kg, status) VALUES
    (1, '川A12345', 5000, 'ACTIVE'),
    (2, '川A67890', 3000, 'ACTIVE');

INSERT INTO drivers (id, name, phone, vehicle_id, status) VALUES
    (1, '张师傅', '13700000001', 1, 'ACTIVE'),
    (2, '李师傅', '13700000002', 2, 'ACTIVE');

INSERT INTO plants (id, name, address, receiving_open, receiving_close, status) VALUES
    (1, '绿源废油处置厂', '经开区环保产业园 3 号', '08:00', '18:00', 'ACTIVE');

INSERT INTO district_restrictions (district, restrict_start, restrict_end, note) VALUES
    ('春熙路商圈', '07:00', '09:30', '早高峰货车限行'),
    ('春熙路商圈', '17:00', '19:30', '晚高峰货车限行'),
    ('老城夜市',   '11:00', '13:00', '午间卸货管制');

INSERT INTO weighing_devices (id, code, store_id, last_heartbeat_at, status) VALUES
    (1, 'DEV-001', 1, now(), 'ONLINE'),
    (2, 'DEV-002', 2, now(), 'ONLINE'),
    (3, 'DEV-003', 3, now(), 'ONLINE');

-- 序列与已插入的显式 id 对齐
SELECT setval('merchants_id_seq', 10);
SELECT setval('stores_id_seq', 10);
SELECT setval('barrels_id_seq', 20);
SELECT setval('vehicles_id_seq', 10);
SELECT setval('drivers_id_seq', 10);
SELECT setval('plants_id_seq', 10);
SELECT setval('weighing_devices_id_seq', 10);
