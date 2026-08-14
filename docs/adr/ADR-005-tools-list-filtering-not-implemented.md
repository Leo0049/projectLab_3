# ADR-005: `tools/list` is not filtered per caller

- **Status**: Accepted — spec section 5.4 deliberately not implemented
- **Date**: 2026-08
- **Spec reference**: section 5.4

## Context

Spec section 5.4 asks for `tools/list` to be filtered by role, with three
benefits: less capability-surface disclosure, fewer wasted model turns on tools
that will be refused, and less context spent on unusable tool descriptions. It
also says, explicitly, that if this is not technically feasible it drops to P2
because call-time enforcement still holds.

## Decision

Not implemented. Every caller sees the full tool list; every call is still
authorised individually.

## Why: there is no extension point, and the only route is a bad one

Three findings, all verified against Spring AI 2.0.0 and MCP SDK 2.0.0 rather
than assumed:

1. **No filter hook exists.** `mcp-core` contains no filter, predicate,
   interceptor or middleware type. `McpAsyncServer.toolsListRequestHandler()`
   is private and is assembled inside a private `prepareRequestHandlers()`.
2. **The tool list is fixed at construction.** `McpServer.sync(transport)`
   takes a `List<SyncToolSpecification>` once. `addTool` / `removeTool` exist
   but are global, not per session, so using them to filter would change what
   *every* connected client sees.
3. **`tools/list` is delivered as a streamed SSE response.** Measured directly:
   the endpoint answers `text/event-stream`, framed as `id:<uuid>` followed by
   a `data:` payload, written through async dispatch.

That leaves exactly one implementation: a servlet filter that buffers the
request body to identify `tools/list`, wraps the response, waits for async
completion, parses the SSE framing, rewrites the JSON, and re-emits it.

It was rejected because the costs land in the wrong places:

- **It taxes the hot path to fix the cold one.** Identifying a `tools/list`
  request means buffering and replaying the request body of *every* MCP call,
  including large tool responses, to filter a call that happens once per
  session.
- **It couples security behaviour to transport framing.** An SDK change to SSE
  framing or content type would make the filter quietly stop matching. It
  would not throw; the list would simply stop being filtered.
- **That failure mode is the one this project argues against.** ADR-002 and
  ADR-003 are both about security measures that appear installed and silently
  do nothing. Shipping a third instance of that pattern to satisfy a P2 item
  would contradict the point of the project.
- **It is not a data boundary.** A caller who sees `issue_refund` in the list
  still cannot call it: `GovernanceAspect` refuses, records the denial, and
  returns a message naming the required role. Twenty-six RBAC cases assert
  this. What is lost is the concealment of tool *names*, not access to data.

## Consequences

- A low-privilege caller can see that tools exist which they cannot use. This
  is capability-surface disclosure and it is listed in the README's known
  limitations rather than glossed over.
- Enforcement and visibility are single-sourced:
  `ToolAuthorizationVoter.vote(...)` is expressed in terms of
  `isVisibleTo(...)`, so the policy that would drive filtering is the same
  policy that refuses calls. If the framework later exposes a per-request hook,
  wiring it up is a small change with no policy duplication.
- Revisit when Spring AI exposes a per-exchange tool list supplier, or when the
  MCP SDK adds a request-handler interception point.

## What was done instead

The three benefits are addressed where they can be addressed robustly:

- **Wasted turns**: every tool description states which situations it is for,
  and denial messages name the role required and the next step, so a refused
  model gets an actionable answer rather than an opaque error.
- **Context**: the response row cap and the 8-tool surface keep the listing
  small in absolute terms.
- **Capability disclosure**: accepted, documented, and bounded by the fact that
  names carry no data.
