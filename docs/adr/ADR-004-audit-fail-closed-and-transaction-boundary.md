# ADR-004: Fail-closed audit, `REQUIRES_NEW`, and advice ordering

- **Status**: Accepted
- **Date**: 2026-08
- **Spec reference**: sections 9.1, 5.3

## Context

In an enterprise setting an operation with no record is treated as one that did
not happen — which means an operation that *runs* without a record is worse than
one that is refused.

## Decision

1. **Fail-closed.** The audit record is written *before* the tool runs. If that
   write fails, the call is refused.
2. **`REQUIRES_NEW`.** Every audit write runs in its own transaction and commits
   independently of the business transaction.
3. **Advice ordering.** `GovernanceAspect` is `Ordered.HIGHEST_PRECEDENCE`, so it
   sits outside Spring's transaction advisor.

Points 2 and 3 are the same decision seen from two sides, which is why they are
one ADR.

## Rationale

If the audit row shared the business transaction, a rollback in the tool would
take the audit row with it. The result: **failed operations leave no trace** —
exactly inverted from what you want, since a failed write attempt is usually the
more interesting event. Nothing would error; the table would simply be missing
rows nobody knew to look for.

Ordering matters for the same reason. If the advice ran *inside* the transaction
advisor, the "independent" pre-write would be enlisted in the business
transaction and `REQUIRES_NEW` would be doing nothing useful.

## Consequences

- `AuditWriter.recordPre` returns the row id; the outcome, row count and latency
  are filled in afterwards by a second independent transaction.
- A failed completion write is logged and counted
  (`mcp_audit_write_failures_total`) but never masks the original error — the
  pre-write already committed, so the call is accounted for.
- Denials and quota rejections are audited too. A refused call is evidence.
- Approval execution uses the same reasoning in reverse: the write runs in a
  `REQUIRES_NEW` transaction (`ApprovalExecutionRunner`) so that a failed
  statement rolls back cleanly and the outer transaction can still record
  `EXECUTION_FAILED`. Without the split, the failed statement would poison the
  transaction and the status update would fail too.
- Arguments are stringified before being serialized into the audit row. Audit
  writing must not be the thing that breaks a call, so it does not do reflective
  serialization of arbitrary parameter types.

## Verification

`AuditFailClosedTest` covers both directions:

- a tool that throws still leaves a committed row with `decision=ALLOW`,
  `outcome=FAILED`;
- with `AuditWriter` stubbed to fail, the call is refused and never executes.

It also asserts the `@Order` value directly, so someone "tidying up" the
annotation breaks a test rather than the guarantee.

## Related finding: error messages must be sanitised

While testing fail-closed behaviour, a separate leak surfaced: Spring AI reports
a tool failure's **root cause**, so raising an exception with an internal cause
attached hands the model — and the user — text like
`Conversion from JSON to java.time.LocalDate failed` or a PostgreSQL parser
error.

Two layers now prevent that (spec section 10):

- `GovernanceAspect.sanitize` converts anything not explicitly authored for the
  model into a trace id, with the real error logged server-side.
- Failures that happen *before* the chain is entered — argument binding, mainly —
  cannot be caught there, so the tool specification wrapper in
  `GovernedMcpSpecificationConfig` inverts the default: error text is forwarded
  only if it carries the model-safe tag, and anything else becomes a trace id.
