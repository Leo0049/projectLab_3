# Evaluation report

Three suites, with deliberately different standards:

| Suite | Runs | Standard | Result |
|---|---|---|---|
| **A — governance** | every push | 100%, build fails otherwise | **218 / 218 pass** |
| **B — LLM evaluation** | nightly / on demand, needs an API key | reported, never enforced | see `eval-report-suite-b.md` |
| **Performance** | nightly / on demand | P95 < 300 ms | see `performance-report.md` |

Security cannot be guaranteed probabilistically, which is the whole reason
Suite A and Suite B are separated rather than averaged into one number.

## Suite A — deterministic governance tests

```bash
mvn verify
```

| Category | Cases | Status |
|---|---|---|
| Masking rules, per strategy and edge case | 26 | pass |
| Tool-level RBAC (tool × role matrix) | 26 | pass |
| HTTP filter chains, role gates, CSRF, audit console | 17 | pass |
| Approval state machine, every legal and illegal edge | 13 | pass |
| Query template coverage (every template executed) | 12 | pass |
| Approval flow, idempotency and retry budget | 11 | pass |
| Masking traversal (nested records, collections, maps) | 11 | pass |
| Sales groupings and tool filters | 10 | pass |
| Audit retention, archival and partition expiry | 9 | pass |
| Redis rate limiting against a real Redis server | 9 | pass |
| Masking, asserted on the wire payload | 8 | pass |
| Identity derived from JWT claims | 8 | pass |
| Tenant isolation | 8 | pass |
| Audit fail-closed and transaction boundary | 6 | pass |
| Error-message contract | 6 | pass |
| Eval dataset validation | 6 | pass |
| Rate limiting, minute window | 5 | pass |
| Rate limiter wiring and fail-fast | 5 | pass |
| Startup validation | 5 | pass |
| Daily quota | 4 | pass |
| Response row cap and truncation | 4 | pass |
| MCP resource registration | 3 | pass |
| Tool discovery over the protocol | 2 | pass |
| Spike 1 regression (AOP proxy vs discovery) | 2 | pass |
| Audit write failure refuses the call | 1 | pass |
| Migration and seed smoke test | 1 | pass |
| **Total** | **218** | **100% pass** |

Suite A runs against real PostgreSQL 16 (started in-process, no Docker daemon
required) and real Redis where one is available.

### Defects found by auditing coverage against claims

The suite grew from 82 to 218 through two deliberate audits. Everything it
caught shared one property — **it failed silently**, with no error, no log line,
and a green build:

1. **`RedisRateLimiter` was never constructed, in any environment.** Backend
   selection used `@ConditionalOnMissingBean` in user configuration, which is
   evaluated before auto-configuration registers Redis, so the in-memory
   limiter always won. A multi-instance deployment would have allowed N times
   the configured rate. Now selected explicitly, verified against a real Redis,
   and startup fails rather than degrading.
2. **`MaskingEngine` threw on any record whose declaring class was not
   public** — `setAccessible` was called on the canonical constructor but not
   on the component accessors. Production DTOs happen to be public.
3. **Two of the three sales templates had never executed.** Tool fixtures only
   ever passed `groupBy=STORE`. A coverage test now fails the build if any
   registered template has no parameters defined for it.
4. **The Redis tests were not in the suite.** Named `…IT`, which surefire does
   not pick up, so nine passing tests ran only when invoked by hand.
5. **Boot 4's Redis auto-configuration exclusions were misspelled** in the test
   profile (`RedisAutoConfiguration` rather than `DataRedisAutoConfiguration`),
   so they were silently ignored and `/actuator/health` reported DOWN.

Two smaller fixes: `inventory.single` and `singleProduct` were dead code, and
the test seed's line items did not sum to their order totals, so revenue by
store and revenue by product disagreed for the same period.

## Suite B — LLM evaluation

**The harness is implemented and runnable. It has not been run here, because
this environment has no API key, so no scores are recorded.**

```bash
ANTHROPIC_API_KEY=... mvn test -Dgroups=llm-eval -Dexcluded.test.groups=
```

It puts each of the 30 questions to a real model with the **registered** tool
schemas and descriptions, executes the model's tool calls through the **real
governance chain**, scores the result and writes `docs/eval-report-suite-b.md`.
Without a key it skips with a clear message rather than failing.

Targets are recorded in the report and never asserted: turning a floating
metric into a red build teaches people to ignore red builds.

| Metric | Definition | Target |
|---|---|---|
| Tool selection accuracy | correct tool calls / questions expecting a call (27) | ≥ 90% |
| Argument accuracy | fully correct arguments / calls with expected arguments | ≥ 85% |
| Abstention accuracy | unanswerable questions with no tool call (3) | 100% |
| Isolation under injection prompts | no cross-tenant data in any response | proven by Suite A |

The last row does not depend on this suite. Tenant isolation is asserted
deterministically on every push; Suite B would only cross-check it.

## Performance

```bash
mvn test -Dgroups=perf -Dexcluded.test.groups=
```

Seeds 50,000 orders, captures `EXPLAIN ANALYZE`, and measures end-to-end tool
latency through the governance chain. Full output in
[`performance-report.md`](performance-report.md); the headline is P95 **16 ms**
against a 300 ms budget, with the composite index used as intended.
