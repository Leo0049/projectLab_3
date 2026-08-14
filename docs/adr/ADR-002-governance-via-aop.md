# ADR-002: Governance as an AOP chain, and the tool-discovery bug it caused

- **Status**: Accepted
- **Date**: 2026-08
- **Spec reference**: sections 5.3, 14 (Spike 1)

## Context

`SecurityFilterChain` authenticates the HTTP request, but at that layer the
server can see only a token and a path — not which tool is being called or with
what arguments. Tool-level RBAC, per-tool quotas, the audit record and the
approval gate all need the tool name and its parameters.

The alternative to a cross-cutting chain is calling a `governance.check(...)`
helper at the top of every tool method, which makes security depend on a
developer remembering to write a line.

## Decision

Governance is a single Spring AOP `@Around` advice on
`@annotation(org.springframework.ai.mcp.annotation.McpTool)`, ordered at
`Ordered.HIGHEST_PRECEDENCE`. A new tool needs only `@McpTool` and `@ToolRisk`;
the chain then applies automatically, and startup fails if `@ToolRisk` is
missing.

## Spike 1 result: AOP works, but it silently breaks tool discovery

This is the finding the spike existed to produce, and it was not the expected
one.

Spring AI 2.0.0 enumerates tool methods in
`AbstractMcpToolProvider#doGetClassMethods` as:

```java
bean.getClass().getDeclaredMethods()
```

When the governance aspect proxies a tool bean, `bean.getClass()` is the CGLIB
subclass. `getDeclaredMethods()` does not walk up to the superclass, and an
**overriding method does not inherit annotations**. So the provider sees methods
with no `@McpTool` on them and registers **zero tools**.

There is no exception and no log line. The application starts normally and
advertises an empty tool list. Measured directly:

```
isProxy         = true
bean.getClass() = SalesTools$$SpringCGLIB$$0
query hasMcpTool= false
discoveredTools = 0
```

Note how bad this failure mode is: adding the security layer removes all the
functionality, quietly. Any test that exercised tools through a service class
rather than the protocol would still pass.

### The fix

Resolve the target class before scanning:

```java
new SyncMcpToolProvider(toolBeans) {
    @Override
    protected Method[] doGetClassMethods(Object bean) {
        return AopUtils.getTargetClass(bean).getDeclaredMethods();
    }
};
```

With that, discovery returns the tool, the advice runs, and — importantly for
ADR-003 — the advice's **return value** is what gets serialized.

This lives in `GovernedMcpSpecificationConfig`, with Spring AI's own scanner
disabled (`spring.ai.mcp.server.annotation-scanner.enabled=false`) so there is
exactly one path building tool specifications.

## Consequences

- **Tool methods must return `Object`.** The advice substitutes an
  `UntrustedDataEnvelope` (and for T3, a pending-approval payload). A CGLIB
  proxy casts the advice's return value to the declared return type, so a
  concrete return type throws `ClassCastException` at call time. The concrete
  types still exist one layer down, in the services and DTO records.
- `GovernedMcpSpecificationConfig` throws if zero tools are discovered, so the
  silent failure can never come back silently.
- `Spike1AopInterceptionTest` pins both behaviours. If a Spring AI upgrade
  changes either, it fails loudly instead of emptying the server.
- The `@Primary` marker is needed because `ToolCallbackConverterAutoConfiguration`
  also contributes a (here empty) `List<SyncToolSpecification>`.

## Ordering

The advice must sit **outside** Spring's transaction advisor. See ADR-004 — it
is the same decision viewed from the transaction side.
