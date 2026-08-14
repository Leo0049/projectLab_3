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

## What is implemented here

- `questions.yml` — the 30-question dataset (spec section 11.3), with the
  expected tools and, where the answer is unambiguous, expected arguments.
- `EvalDataset` — loader.
- `EvalScorer` — the metric definitions and arithmetic.
- `EvalDatasetTest` — runs in **Suite A**, free and deterministic. It checks the
  dataset is well-formed, that every expected tool actually exists on the
  server, and that the scoring maths is right. This catches the errors that
  would otherwise only surface partway through a paid nightly run.

## What is not yet implemented

**The driver that actually talks to a model has not been written or run, so
there are no scores yet.** `docs/eval-report.md` is a template with the numbers
left blank on purpose. Publishing invented figures would be worse than
publishing none: the first question anyone asks about a metric is how it was
measured.

To complete it:

1. Start the server (`docker compose up`).
2. Connect an MCP client to `http://localhost:8080/mcp` as a fixed identity
   (the `demo-cs-lead-key` API key keeps the run reproducible).
3. For each question, send `prompt`, record the tool calls the model makes as
   `EvalScorer.Observation`, and let the conversation finish.
4. Pass the observations to `EvalScorer.score(...)` and render
   `docs/eval-report.md`.

The nightly GitHub Actions job is already wired for this and expects an
`ANTHROPIC_API_KEY` secret; it is marked `continue-on-error` so a bad night
never turns the build red.

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
