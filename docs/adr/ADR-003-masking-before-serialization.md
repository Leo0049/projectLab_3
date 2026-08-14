# ADR-003: Mask before serialization, not during it

- **Status**: Accepted — supersedes the design sketched in spec section 7.2
- **Date**: 2026-08
- **Spec reference**: sections 7.2, 14 (Spike 2)

## Context

Personal data must be masked according to the caller's role, and the masking
rule should be written once rather than repeated in every tool.

The spec proposed doing this in the serialization layer: a custom Jackson module
(`BeanSerializerModifier`) registered on the `ObjectMapper`, so every tool result
is masked on the way out with no per-tool code.

The spec also flagged the risk, correctly: *if the MCP layer uses a different
`ObjectMapper`, masking silently does nothing.*

## Spike 2 result: the serialization hook does not exist

It is not a risk in Spring AI 2.0.0 — it is the actual state.

Tool results are serialized by `AbstractMcpToolMethodCallback`:

```java
private static final org.springframework.ai.util.JsonHelper jsonHelper;
...
protected McpSchema.CallToolResult convertValueToCallToolResult(Object value)
```

and `JsonHelper` wraps its own mapper:

```java
public class JsonHelper {
    private final tools.jackson.databind.json.JsonMapper jsonMapper;
    public JsonHelper() { /* builds its own */ }
}
```

Three facts follow:

1. The field is `private static final` — one instance, created internally.
2. It is **not** a Spring bean, so it cannot be replaced or post-processed.
3. Spring AI 2.0 is on **Jackson 3** (`tools.jackson.databind`), while
   application code often still holds Jackson 2 annotations
   (`com.fasterxml.jackson.annotation`), so even the module type would differ.

A module registered on the Boot `ObjectMapper` would never be consulted. It
would also never error — the exact silent failure the spec feared, except
guaranteed rather than possible.

## Decision

Mask one step earlier: `GovernanceAspect` applies `MaskingEngine` to the
returned object, and hands the **already-masked** object onward. Whatever mapper
the framework then uses, it has nothing sensitive left to serialize.

`MaskingEngine` walks records, collections and maps, applies each field's
`@Masked` strategy unless the caller's role is exempted, and rebuilds records
through their canonical constructor (results are immutable records, so masking
produces a copy).

This is confirmed end to end: in the spike, a value modified by the advice
appears in the wire payload (`rows-for-STORE|masked`).

## Why this is better than the original design, not just a workaround

- **Mapper-independent.** It cannot be broken by a serializer change, a Jackson
  major version, or a second mapper appearing somewhere.
- **Fails loudly if it regresses.** Masking now sits on the same code path as
  the rest of governance, which is covered by tests that read the wire payload.
- It also masks anything the tool would return through a non-JSON path.

The cost is that masking runs once per call in the advice rather than lazily
during serialization — irrelevant at a 200-row response cap.

## Consequences

- `@Masked` is declared on the DTO record components, so the policy stays with
  the data.
- Suite A asserts on **the serialized wire payload**, never on the DTO. A DTO
  assertion would pass even if nothing masked the bytes actually sent — the
  precise mistake spec section 7.2 warns about.
- A regex sweep asserts no full phone number (`09\d{8}`) appears anywhere in a
  payload, which catches a masked field being leaked by some *other* field.
- Audit arguments are masked separately (`ArgumentMasker`), so the log does not
  become the leak. Free-text search terms are pattern-scrubbed rather than
  wholesale-masked: turning `客戶 0912345678 的團` into `客○○…○團` removes the
  leak but also destroys the audit trail's ability to answer "what was searched
  for".
- Role-based exemption is only meaningful if some role that can call the tool is
  *not* exempt. `CS_AGENT` exists for that reason: `CS_LEAD` and `ADMIN` see the
  delivery address, front-line agents do not.
