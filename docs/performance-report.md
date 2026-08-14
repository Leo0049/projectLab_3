# Performance report

Acceptance evidence for spec section 2.2: read-only tools at P95 under
300 ms over a 50,000-order dataset, with the composite index
`(merchant_id, status, created_at)` in place.

Regenerate with:

```bash
mvn test -Dgroups=perf -Dexcluded.test.groups=
```

Measured on embedded PostgreSQL 16.14 in the build container, so the
absolute numbers depend on the host. What the run establishes is the
shape: a typical window uses the index, and the end-to-end tool call
stays inside the budget with governance, masking and serialization
included.

## Dataset

- Orders: **50,000**, order items: **100,001**
- Indexes on `orders`: idx_orders_merchant_status_created, orders_pkey, idx_orders_customer


### Plan for a 7-day window (the common query)

```
Sort  (cost=1425.64..1426.69 rows=420 width=194) (actual time=3.561..3.564 rows=3 loops=1)
  Sort Key: (COALESCE(sum(o.total_amount), '0'::numeric)) DESC
  Sort Method: quicksort  Memory: 25kB
  Buffers: shared hit=620
  ->  GroupAggregate  (cost=1387.92..1407.34 rows=420 width=194) (actual time=3.342..3.548 rows=3 loops=1)
        Group Key: s.store_id
        Buffers: shared hit=617
        ->  Sort  (cost=1387.92..1391.46 rows=1417 width=167) (actual time=3.126..3.220 rows=1528 loops=1)
              Sort Key: s.store_id, o.order_id
              Sort Method: quicksort  Memory: 144kB
              Buffers: shared hit=617
              ->  Nested Loop  (cost=38.07..1313.75 rows=1417 width=167) (actual time=0.679..1.880 rows=1528 loops=1)
                    Buffers: shared hit=617
                    ->  Bitmap Heap Scan on orders o  (cost=37.91..1277.58 rows=1417 width=21) (actual time=0.589..1.100 rows=1528 loops=1)
                          Recheck Cond: ((merchant_id = 7) AND ((status)::text = 'COMPLETED'::text) AND (created_at >= ('2026-08-07'::cstring)::timestamp with time zone) AND (created_at < ('2026-08-15'::cstring)::timestamp with time zone))
                          Heap Blocks: exact=604
                          Buffers: shared hit=611
                          ->  Bitmap Index Scan on idx_orders_merchant_status_created  (cost=0.00..37.55 rows=1417 width=0) (actual time=0.221..0.221 rows=3056 loops=1)
                                Index Cond: ((merchant_id = 7) AND ((status)::text = 'COMPLETED'::text) AND (created_at >= ('2026-08-07'::cstring)::timestamp with time zone) AND (created_at < ('2026-08-15'::cstring)::timestamp with time zone))
                                Buffers: shared hit=7
                    ->  Memoize  (cost=0.16..0.21 rows=1 width=154) (actual time=0.000..0.000 rows=1 loops=1528)
                          Cache Key: o.store_id
                          Cache Mode: logical
                          Hits: 1525  Misses: 3  Evictions: 0  Overflows: 0  Memory Usage: 1kB
                          Buffers: shared hit=6
                          ->  Index Scan using stores_pkey on stores s  (cost=0.15..0.20 rows=1 width=154) (actual time=0.028..0.028 rows=1 loops=3)
                                Index Cond: (store_id = o.store_id)
                                Buffers: shared hit=6
Planning:
  Buffers: shared hit=108
Planning Time: 0.718 ms
Execution Time: 3.613 ms
```

### Plan for the 90-day maximum window

```
Sort  (cost=3493.71..3494.76 rows=420 width=194) (actual time=35.038..35.042 rows=3 loops=1)
  Sort Key: (COALESCE(sum(o.total_amount), '0'::numeric)) DESC
  Sort Method: quicksort  Memory: 25kB
  Buffers: shared hit=1145
  ->  GroupAggregate  (cost=3302.04..3475.41 rows=420 width=194) (actual time=32.418..35.023 rows=3 loops=1)
        Group Key: s.store_id
        Buffers: shared hit=1145
        ->  Sort  (cost=3302.04..3344.07 rows=16812 width=167) (actual time=30.496..31.593 rows=16818 loops=1)
              Sort Key: s.store_id, o.order_id
              Sort Method: quicksort  Memory: 1820kB
              Buffers: shared hit=1145
              ->  Hash Join  (cost=436.13..2122.07 rows=16812 width=167) (actual time=2.827..10.043 rows=16818 loops=1)
                    Hash Cond: (o.store_id = s.store_id)
                    Buffers: shared hit=1145
                    ->  Bitmap Heap Scan on orders o  (cost=416.68..2058.04 rows=16812 width=21) (actual time=2.795..5.494 rows=16818 loops=1)
                          Recheck Cond: ((merchant_id = 7) AND ((status)::text = 'COMPLETED'::text) AND (created_at >= ('2026-05-16'::cstring)::timestamp with time zone) AND (created_at < ('2026-08-15'::cstring)::timestamp with time zone))
                          Heap Blocks: exact=1111
                          Buffers: shared hit=1144
                          ->  Bitmap Index Scan on idx_orders_merchant_status_created  (cost=0.00..412.48 rows=16812 width=0) (actual time=1.981..1.982 rows=33636 loops=1)
                                Index Cond: ((merchant_id = 7) AND ((status)::text = 'COMPLETED'::text) AND (created_at >= ('2026-05-16'::cstring)::timestamp with time zone) AND (created_at < ('2026-08-15'::cstring)::timestamp with time zone))
                                Buffers: shared hit=33
                    ->  Hash  (cost=14.20..14.20 rows=420 width=154) (actual time=0.013..0.014 rows=5 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on stores s  (cost=0.00..14.20 rows=420 width=154) (actual time=0.008..0.010 rows=5 loops=1)
                                Buffers: shared hit=1
Planning:
  Buffers: shared hit=2
Planning Time: 0.268 ms
Execution Time: 35.151 ms
```

### End-to-end tool latency (`query_sales_summary`, 7-day window)

Measured through the MCP tool specification, so the governance chain, masking and serialization are all inside the measurement.

| Runs | P50 | P95 | Max | Budget |
|---|---|---|---|---|
| 100 | 8 ms | **16 ms** | 20 ms | 300 ms |

### `get_order_detail` (masked, two queries per call)

P95 over 100 runs: **6 ms**
