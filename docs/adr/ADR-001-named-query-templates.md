# ADR-001: Named query templates instead of text-to-SQL

- **Status**: Accepted
- **Date**: 2026-08
- **Spec reference**: section 7.1

## Context

The model needs to answer open-ended operational questions ("how did we do last
week?", "what's running low?"). The obvious way to allow that is text-to-SQL:
let the model write a query and run it.

## Decision

The model cannot write SQL. It selects a **template id** from a closed set
defined in `query-templates.yml` and supplies bound parameters. The SQL
structure is fixed at build time.

## Rationale

| | text-to-SQL | named templates |
|---|---|---|
| Flexibility | High — answers unanticipated questions | Low — only what was defined |
| SQL injection | Model emits SQL; full attack surface | Parameterised, structure fixed |
| Tenant isolation | Depends on the model adding a predicate | Server injects it; not optional |
| Performance | Can produce a full table scan | Each template's plan can be checked |
| Auditability | Every query differs | Fixed template id, clean lineage |
| Testability | Cannot be enumerated | Finite, so 100% coverage is achievable |

The trade is flexibility for control, and the last row is what actually decides
it: because there are twelve templates rather than infinite queries, Suite A can
assert the tenant predicate on **every** path. That claim is impossible to make
about generated SQL.

## Consequences

- Every template must bind `:__tenant`. `QueryTemplateRegistry` refuses to start
  otherwise, so a missing predicate is a boot failure, not a leak. `StartupValidationTest`
  proves the check fires.
- The row cap is applied centrally by wrapping each template
  (`SELECT * FROM (<template>) LIMIT :__limit`), so a template author cannot
  forget it.
- New questions need a code change. Accepted: an enterprise deployment reviews
  new data access anyway, and this makes the review a diff.
- Optional parameters need explicit casts (`:productId::bigint`). PostgreSQL
  cannot infer a type for an untyped NULL bind — found while wiring the first
  tool, not in review.

## Alternatives considered

- **Text-to-SQL with a read-only role and row-level security.** RLS would give
  real isolation, but the query plan and result size stay unbounded, and the
  audit trail becomes "here is some SQL a model wrote", which is not something
  an auditor can reason about.
- **Templates with a free-text `WHERE` fragment.** Reintroduces injection
  through the back door for a fraction of the flexibility.

## Future direction

If more flexibility is needed, add richer *parameterisation* — optional
groupings, more dimensions — rather than opening up SQL generation.
