# Suite B — LLM evaluation

Suite A proves the security properties. Suite B measures something different
and weaker: **whether the model picks the right tool with the right
arguments.** That number floats between runs, so it is reported, not enforced.

## Why the two suites are separate

v1.0 of the spec put everything in one LLM-driven suite, which produced a
metric like "unauthorised access blocked: 100%". That number is close to
meaningless: if the model never attempted the call, the block rate is trivially
100%. Success depended on the model *trying* the thing being tested.

So the split is:

| | Suite A | Suite B |
|---|---|---|
| Measures | permissions, tenant isolation, masking, audit, idempotency | tool choice, argument accuracy |
| Runs | every push (CI) | nightly / on demand |
| Cost | zero | API calls per run |
| Determinism | deterministic | varies run to run |
| Standard | **100%, build fails otherwise** | target, non-blocking |

Security cannot be guaranteed probabilistically. That is the whole reason for
the split, and it is the honest way to report both numbers.

## What is implemented

- `questions.yml` — the 30-question dataset (spec section 11.3), with the
  expected tools and, where the answer is unambiguous, expected arguments.
- `EvalDataset` — loader.
- `EvalScorer` — the metric definitions and arithmetic.
- `EvalDatasetTest` — runs in **Suite A**, free and deterministic. It checks the
  dataset is well-formed, that every expected tool actually exists on the
  server, and that the scoring maths is right. This catches the errors that
  would otherwise only surface partway through a paid nightly run.

- `AnthropicMessagesClient` — a small Messages API client. Written by hand
  rather than pulling an SDK into the dependency tree to serve one nightly test.
- `EvalRunner` — the agent loop: it hands the model the **registered** tool
  schemas and descriptions, executes the tool calls it makes through the
  **real governance chain**, and feeds results (including refusals) back.
- `LlmEvaluationTest` — the entry point. Skips with a clear message when
  `ANTHROPIC_API_KEY` is absent, so a missing credential is never a red build.

## Running it

```bash
ANTHROPIC_API_KEY=... mvn test -Dgroups=llm-eval -Dexcluded.test.groups=
```

Results are written to `docs/eval-report-suite-b.md`: metrics, a per-question
table of expected versus called tools, and any question that failed to
complete. The nightly GitHub Actions job runs the same command and is marked
`continue-on-error`, so a bad night never turns the build red.

Two choices worth knowing about:

- **The run authenticates as ADMIN of tenant 7**, so every tool is reachable.
  Suite B measures tool *selection*; permissions are Suite A's job. A restricted
  identity would conflate "chose the wrong tool" with "was refused" and make the
  score depend on the role rather than the descriptions.
- **Tool calls really execute.** They go through the governance chain against
  the seeded database, so an injection question is answered by the same tenant
  scoping that protects production — not by a stub.

## Not yet run

**No scores are recorded in this repository.** The environment it was built in
has no API key, and `docs/eval-report.md` says so rather than showing invented
figures. The first question anyone asks about a metric is how it was measured.

## Metric definitions

- **Tool selection accuracy** — questions where the model called every expected
  tool, over questions that should produce a call. The three unanswerable
  questions are excluded from the denominator; counting them as successes would
  reward doing nothing.
- **Argument accuracy** — calls where every expected argument matched, over
  calls with expected arguments specified. Extra arguments are allowed.
- **Abstention accuracy** — unanswerable questions where the model called
  nothing.

## A note on the injection questions

The four `INJECTION` questions do **not** measure whether isolation holds —
Suite A already proves that deterministically, on every push. They measure how
the model *behaves* when the server refuses it: whether it relays the refusal
honestly, or claims success, invents unmasked data, or keeps probing for a way
around.
