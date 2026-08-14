-- Business domain, reused from the group-buy beverage platform.
-- merchant_id is the tenant discriminator on every tenant-scoped table.

CREATE TABLE merchants (
    merchant_id   BIGSERIAL PRIMARY KEY,
    merchant_name VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE stores (
    store_id    BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT      NOT NULL REFERENCES merchants (merchant_id),
    store_name  VARCHAR(64) NOT NULL
);
CREATE INDEX idx_stores_merchant ON stores (merchant_id);

CREATE TABLE products (
    product_id  BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT         NOT NULL REFERENCES merchants (merchant_id),
    name        VARCHAR(64)    NOT NULL,
    category    VARCHAR(32)    NOT NULL,
    unit_price  NUMERIC(10, 2) NOT NULL
);
CREATE INDEX idx_products_merchant ON products (merchant_id);

CREATE TABLE inventory (
    product_id      BIGINT PRIMARY KEY REFERENCES products (product_id),
    merchant_id     BIGINT   NOT NULL REFERENCES merchants (merchant_id),
    quantity        INT      NOT NULL,
    low_water_mark  INT      NOT NULL DEFAULT 20,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_inventory_merchant ON inventory (merchant_id);

CREATE TABLE customers (
    customer_id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT       NOT NULL REFERENCES merchants (merchant_id),
    name        VARCHAR(64)  NOT NULL,
    phone       VARCHAR(32)  NOT NULL,
    address     VARCHAR(255) NOT NULL,
    email       VARCHAR(128) NOT NULL
);
CREATE INDEX idx_customers_merchant ON customers (merchant_id);

CREATE TABLE group_orders (
    group_order_id BIGSERIAL PRIMARY KEY,
    merchant_id    BIGINT      NOT NULL REFERENCES merchants (merchant_id),
    store_id       BIGINT      NOT NULL REFERENCES stores (store_id),
    title          VARCHAR(128) NOT NULL,
    status         VARCHAR(16)  NOT NULL, -- OPEN / CLOSED / COMPLETED / CANCELLED
    created_at     TIMESTAMPTZ  NOT NULL,
    closes_at      TIMESTAMPTZ
);
CREATE INDEX idx_group_orders_merchant_status ON group_orders (merchant_id, status, created_at DESC);

CREATE TABLE orders (
    order_id       BIGSERIAL PRIMARY KEY,
    merchant_id    BIGINT         NOT NULL REFERENCES merchants (merchant_id),
    store_id       BIGINT         NOT NULL REFERENCES stores (store_id),
    group_order_id BIGINT         REFERENCES group_orders (group_order_id),
    customer_id    BIGINT         NOT NULL REFERENCES customers (customer_id),
    status         VARCHAR(16)    NOT NULL, -- PENDING / COMPLETED / CANCELLED
    total_amount   NUMERIC(12, 2) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL
);

-- Required by the P95 < 300ms non-functional requirement (spec section 2.2).
CREATE INDEX idx_orders_merchant_status_created ON orders (merchant_id, status, created_at);
CREATE INDEX idx_orders_customer ON orders (customer_id);

CREATE TABLE order_items (
    order_item_id BIGSERIAL PRIMARY KEY,
    order_id      BIGINT         NOT NULL REFERENCES orders (order_id),
    product_id    BIGINT         NOT NULL REFERENCES products (product_id),
    quantity      INT            NOT NULL,
    unit_price    NUMERIC(10, 2) NOT NULL,
    subtotal      NUMERIC(12, 2) NOT NULL
);
CREATE INDEX idx_order_items_order ON order_items (order_id);
CREATE INDEX idx_order_items_product ON order_items (product_id);

-- Local identities. In production these come from the corporate IdP; here the
-- authorization server issues tokens carrying role + tenant_id claims from this table.
CREATE TABLE app_users (
    user_id       BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    merchant_id   BIGINT       NOT NULL REFERENCES merchants (merchant_id),
    display_name  VARCHAR(64)  NOT NULL
);
