-- Reference data shared by the demo and test datasets.
-- Order volume differs per environment and is seeded separately
-- (db/seed/demo for docker compose, db/seed/test for Suite A).

INSERT INTO merchants (merchant_id, merchant_name) VALUES
    (7, '珍豆坊'),
    (9, '茶研所');

INSERT INTO stores (store_id, merchant_id, store_name) VALUES
    (101, 7, '信義店'),
    (102, 7, '大安店'),
    (103, 7, '中山店'),
    (201, 9, '板橋店'),
    (202, 9, '新莊店');

INSERT INTO products (product_id, merchant_id, name, category, unit_price) VALUES
    (1001, 7, '珍珠奶茶',   'MILK_TEA', 60.00),
    (1002, 7, '四季春',     'TEA',      40.00),
    (1003, 7, '紅茶拿鐵',   'MILK_TEA', 55.00),
    (1004, 7, '檸檬青茶',   'TEA',      50.00),
    (1005, 7, '芋圓鮮奶',   'SPECIAL',  75.00),
    (2001, 9, '烏龍拿鐵',   'MILK_TEA', 58.00),
    (2002, 9, '蜂蜜綠茶',   'TEA',      45.00),
    (2003, 9, '桂花烏龍',   'TEA',      52.00),
    (2004, 9, '黑糖鮮奶',   'SPECIAL',  68.00),
    (2005, 9, '柚子綠茶',   'TEA',      48.00);

-- 珍珠奶茶 sits at 120 so the spec section 8.1 demo (delta -50 -> 70) reproduces exactly.
INSERT INTO inventory (product_id, merchant_id, quantity, low_water_mark) VALUES
    (1001, 7, 120, 30),
    (1002, 7, 15,  20),   -- below low water mark on purpose
    (1003, 7, 200, 30),
    (1004, 7, 8,   20),   -- below low water mark on purpose
    (1005, 7, 60,  20),
    (2001, 9, 90,  20),
    (2002, 9, 12,  20),   -- below low water mark, but belongs to tenant 9
    (2003, 9, 140, 30),
    (2004, 9, 45,  20),
    (2005, 9, 70,  25);

INSERT INTO customers (customer_id, merchant_id, name, phone, address, email) VALUES
    (5001, 7, '王小明', '0912345678', '台北市信義區松高路11號5樓',   'ming@example.com'),
    (5002, 7, '陳美麗', '0922333444', '台北市大安區忠孝東路四段2號', 'mei@example.com'),
    (5003, 7, '李大文', '0933777888', '台北市中山區南京東路三段9號', 'wen@example.com'),
    (6001, 9, '林建宏', '0955111222', '新北市板橋區文化路一段8號',   'hong@example.com'),
    (6002, 9, '吳雅婷', '0966222333', '新北市板橋區中山路二段15號', 'ting@example.com'),
    (6003, 9, '周俊傑', '0977444555', '新北市新莊區中正路三段7號', 'jie@example.com');

-- Demo identities. Passwords are bcrypt-hashed; see README for the credential table.
INSERT INTO app_users (user_id, username, password_hash, role, merchant_id, display_name) VALUES
    (42, 'alice', '{bcrypt}$2a$10$PpDYSK2KZsW7E14FBSNnOOaQ7fjdBqf6n9QSs.r.o7cm/sJwyzpLO', 'STORE_MANAGER', 7, '愛麗絲 (信義店長)'),
    (43, 'bob',   '{bcrypt}$2a$10$PpDYSK2KZsW7E14FBSNnOOaQ7fjdBqf6n9QSs.r.o7cm/sJwyzpLO', 'CS_LEAD',       7, '鮑伯 (客服主管)'),
    (44, 'carol', '{bcrypt}$2a$10$0g3wpiDZQV1Gre7S29HeA.ZFgl1qRgFeBXjec/xomKTN5RMEVEnlq', 'ADMIN',         7, '卡蘿 (系統管理員)'),
    (45, 'erin',  '{bcrypt}$2a$10$PpDYSK2KZsW7E14FBSNnOOaQ7fjdBqf6n9QSs.r.o7cm/sJwyzpLO', 'APPROVER',      7, '艾琳 (審批者)'),
    (46, 'frank', '{bcrypt}$2a$10$PpDYSK2KZsW7E14FBSNnOOaQ7fjdBqf6n9QSs.r.o7cm/sJwyzpLO', 'ANALYST',       7, '法蘭克 (營運分析)'),
    (47, 'grace', '{bcrypt}$2a$10$PpDYSK2KZsW7E14FBSNnOOaQ7fjdBqf6n9QSs.r.o7cm/sJwyzpLO', 'AUDITOR',       7, '葛蕾絲 (稽核)'),
    (48, 'dave',  '{bcrypt}$2a$10$PpDYSK2KZsW7E14FBSNnOOaQ7fjdBqf6n9QSs.r.o7cm/sJwyzpLO', 'STORE_MANAGER', 9, '戴夫 (板橋店長)'),
    (49, 'hana',  '{bcrypt}$2a$10$PpDYSK2KZsW7E14FBSNnOOaQ7fjdBqf6n9QSs.r.o7cm/sJwyzpLO', 'CS_AGENT',      7, '花奈 (客服專員)');

SELECT setval('merchants_merchant_id_seq', 100);
SELECT setval('stores_store_id_seq', 1000);
SELECT setval('products_product_id_seq', 10000);
SELECT setval('customers_customer_id_seq', 10000);
SELECT setval('app_users_user_id_seq', 1000);
