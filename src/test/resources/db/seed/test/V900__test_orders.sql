-- Deterministic dataset for Suite A. Every number the governance tests assert on
-- is fixed here, so a failing assertion means a governance regression, not data drift.
--
-- Tenant 7, January 2026, COMPLETED only:
--   store 101 信義店 -> 3 orders, revenue 450.00
--   store 102 大安店 -> 2 orders, revenue 420.00
--   store 103 中山店 -> 1 order,  revenue  80.00
--   (one CANCELLED 500.00 order at store 101 must never appear in revenue)
-- Tenant 9, same window:
--   store 201 板橋店 -> 2 orders, revenue 1000.00

INSERT INTO group_orders (group_order_id, merchant_id, store_id, title, status, created_at, closes_at) VALUES
    (7001, 7, 101, '信義店 1/5 下午茶團',   'COMPLETED', TIMESTAMPTZ '2026-01-05 09:00:00+08', TIMESTAMPTZ '2026-01-05 15:00:00+08'),
    (7002, 7, 102, '大安店 1/12 週會團',    'COMPLETED', TIMESTAMPTZ '2026-01-12 09:00:00+08', TIMESTAMPTZ '2026-01-12 15:00:00+08'),
    (7003, 7, 103, '中山店 1/20 加班團',    'OPEN',      TIMESTAMPTZ '2026-01-20 09:00:00+08', TIMESTAMPTZ '2026-01-20 18:00:00+08'),
    (7004, 7, 101, '信義店 1/25 尾牙預訂團', 'CANCELLED', TIMESTAMPTZ '2026-01-25 09:00:00+08', NULL),
    (9001, 9, 201, '板橋店 1/08 開工團',    'COMPLETED', TIMESTAMPTZ '2026-01-08 09:00:00+08', TIMESTAMPTZ '2026-01-08 15:00:00+08');

INSERT INTO orders (order_id, merchant_id, store_id, group_order_id, customer_id, status, total_amount, created_at) VALUES
    -- tenant 7 / store 101
    (90001, 7, 101, 7001, 5001, 'COMPLETED', 100.00, TIMESTAMPTZ '2026-01-05 10:00:00+08'),
    (90002, 7, 101, 7001, 5002, 'COMPLETED', 200.00, TIMESTAMPTZ '2026-01-05 11:00:00+08'),
    (90003, 7, 101, 7001, 5003, 'COMPLETED', 150.00, TIMESTAMPTZ '2026-01-06 10:30:00+08'),
    -- tenant 7 / store 102
    (90004, 7, 102, 7002, 5001, 'COMPLETED', 300.00, TIMESTAMPTZ '2026-01-12 10:00:00+08'),
    (90005, 7, 102, 7002, 5002, 'COMPLETED', 120.00, TIMESTAMPTZ '2026-01-12 12:00:00+08'),
    -- tenant 7 / store 103
    (90006, 7, 103, 7003, 5003, 'COMPLETED',  80.00, TIMESTAMPTZ '2026-01-20 10:00:00+08'),
    -- cancelled: excluded from every revenue template
    (90007, 7, 101, 7004, 5001, 'CANCELLED', 500.00, TIMESTAMPTZ '2026-01-25 10:00:00+08'),
    -- outside the January window: guards against unbounded date ranges
    (90008, 7, 101, NULL, 5001, 'COMPLETED', 777.00, TIMESTAMPTZ '2026-02-10 10:00:00+08'),
    -- tenant 9
    (90101, 9, 201, 9001, 6001, 'COMPLETED', 999.00, TIMESTAMPTZ '2026-01-08 10:00:00+08'),
    (90102, 9, 201, 9001, 6001, 'COMPLETED',   1.00, TIMESTAMPTZ '2026-01-08 11:00:00+08');

-- Item mix drives list_top_products. Tenant 7 January totals:
--   珍珠奶茶 (1001) qty 14, 四季春 (1002) qty 9, 紅茶拿鐵 (1003) qty 4
INSERT INTO order_items (order_id, product_id, quantity, unit_price, subtotal) VALUES
    (90001, 1001, 5, 60.00, 300.00),
    (90001, 1002, 2, 40.00,  80.00),
    (90002, 1001, 4, 60.00, 240.00),
    (90002, 1003, 1, 55.00,  55.00),
    (90003, 1002, 3, 40.00, 120.00),
    (90004, 1001, 3, 60.00, 180.00),
    (90004, 1003, 3, 55.00, 165.00),
    (90005, 1002, 4, 40.00, 160.00),
    (90006, 1001, 2, 60.00, 120.00),
    (90007, 1001, 9, 60.00, 540.00),   -- cancelled order, must not rank
    (90101, 2001, 8, 58.00, 464.00),
    (90102, 2002, 1, 45.00,  45.00);

SELECT setval('group_orders_group_order_id_seq', 100000);
SELECT setval('orders_order_id_seq', 100000);
