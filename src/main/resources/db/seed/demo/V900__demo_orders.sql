-- Demo dataset: 50,000 orders spread over the last 180 days, per spec section 2.2.
-- Deterministic (setseed) so EXPLAIN ANALYZE numbers are reproducible across runs.
-- Independent of the original group-buy platform's data (spec section 2.3).

SELECT setseed(0.42);

-- Group orders: ~2,000 buying rounds.
INSERT INTO group_orders (merchant_id, store_id, title, status, created_at, closes_at)
SELECT m.merchant_id,
       s.store_id,
       s.store_name || ' 第 ' || g.n || ' 團',
       (ARRAY['COMPLETED', 'COMPLETED', 'COMPLETED', 'CLOSED', 'OPEN', 'CANCELLED'])[1 + (g.n % 6)],
       now() - make_interval(days => (g.n % 180)),
       now() - make_interval(days => (g.n % 180)) + interval '6 hours'
FROM generate_series(1, 2000) AS g(n)
    -- OFFSET, not ORDER BY <expr>: an expression that ties between stores
    -- resolves the tie the same way every time, which silently starved one
    -- tenant of all its rows. This is exactly uniform across the five stores.
         JOIN LATERAL (
    SELECT store_id, store_name, merchant_id
    FROM stores
    ORDER BY store_id
    OFFSET (g.n % 5)
    LIMIT 1
    ) s ON TRUE
         JOIN merchants m ON m.merchant_id = s.merchant_id;

-- 50,000 orders. Tenant split follows the store the order is placed at, so tenant
-- isolation has real cross-tenant data to exclude.
INSERT INTO orders (merchant_id, store_id, group_order_id, customer_id, status, total_amount, created_at)
SELECT s.merchant_id,
       s.store_id,
       go.group_order_id,
       c.customer_id,
       (ARRAY['COMPLETED', 'COMPLETED', 'COMPLETED', 'COMPLETED', 'PENDING', 'CANCELLED'])[1 + (o.n % 6)],
       0,
       now() - make_interval(days => (o.n % 180), hours => (o.n % 24), mins => (o.n % 60))
FROM generate_series(1, 50000) AS o(n)
         JOIN LATERAL (
    SELECT store_id, merchant_id FROM stores ORDER BY store_id OFFSET (o.n % 5) LIMIT 1
    ) s ON TRUE
         JOIN LATERAL (
    SELECT customer_id FROM customers
    WHERE customers.merchant_id = s.merchant_id
    ORDER BY customer_id OFFSET (o.n % 3) LIMIT 1
    ) c ON TRUE
         LEFT JOIN LATERAL (
    SELECT group_order_id FROM group_orders
    WHERE group_orders.merchant_id = s.merchant_id
    ORDER BY group_order_id OFFSET (o.n % 100) LIMIT 1
    ) go ON TRUE;

-- One to three line items per order, always from the order's own tenant.
INSERT INTO order_items (order_id, product_id, quantity, unit_price, subtotal)
SELECT ord.order_id,
       p.product_id,
       q.quantity,
       p.unit_price,
       p.unit_price * q.quantity
FROM orders ord
         JOIN LATERAL generate_series(1, 1 + (ord.order_id % 3)) AS line(k) ON TRUE
         JOIN LATERAL (
    SELECT product_id, unit_price FROM products
    WHERE products.merchant_id = ord.merchant_id
    ORDER BY product_id OFFSET ((ord.order_id + line.k) % 5) LIMIT 1
    ) p ON TRUE
         JOIN LATERAL (SELECT 1 + ((ord.order_id + line.k) % 4) AS quantity) q ON TRUE;

-- Keep order totals consistent with their line items.
UPDATE orders o
SET total_amount = i.total
FROM (SELECT order_id, SUM(subtotal) AS total FROM order_items GROUP BY order_id) i
WHERE i.order_id = o.order_id;

ANALYZE orders;
ANALYZE order_items;
ANALYZE group_orders;
