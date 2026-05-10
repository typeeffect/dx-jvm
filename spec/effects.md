# dx Effects Spec

Status: draft, aligned with the current paper audit in
`docs/paper-alignment.md`.

Scope:

- Effect rows.
- Capability evidence.
- Handler typing.
- One-shot resumptions.
- Effect safety classes.
- Primitive effects: `IO`, `Async`, `Resource`, `Throws[E]`, `JavaMutation`, `Lock`, `Unsafe`.

Initial decision:

- `Throws[E]` is a built-in parameterized primitive effect in v1.
- General user-defined parameterized effects are postponed.

Effect safety classes:

| Class | Examples |
|---|---|
| `Pure` | Local immutable computation and math with no observable operation. |
| `Deterministic` | `Shape`, deterministic/seeded `Random`, `TensorOps`, `Diff`. |
| `Async` | `Async`, `await`, `sleep`, task operations. |
| `Resource` | `use`, `defer`, `AutoCloseable` scopes. |
| `IO` | Files, network, console, `Throws[E]`. |
| `JavaMutation` | Unknown Java calls and mutable Java object operations. |
| `Lock` | Locks, monitor/synchronized regions. |
| `Unsafe` | Reflection, dynamic interop, native escape hatches. |

Initial effect policies:

| Policy | Purpose |
|---|---|
| `PureDeclaration` | Only pure computation is accepted. |
| `GradRegion` | Allows pure/deterministic tensor and differentiation effects; rejects async, resource, IO, Java mutation, locks, and unsafe effects. |
| `FutureMultiShot` | Future-only policy; accepts pure computation only. |
| `AsyncRegion` | Allows async/resource/IO/Java mutation/lock effects but rejects unsafe by default. |
| `TopLevelScript` | Permissive script mode; allows Java mutation with diagnostics elsewhere, rejects unsafe by default. |
| `UnsafeBlock` | Explicit escape hatch accepting known safety classes. |

The policy checker operates on typed CBPV `ComputationType` effect sets. Unknown effect safety is diagnostic until the effect is registered with a safety class.

## Paper-Aligned Design Rules

The v1 effect system follows these rules:

- Source-level effects are nominal declarations. Internal effect rows are
  structural and normalized.
- Row solving is scoped-label-inspired: multiple lexical instances of the same
  nominal effect can exist internally, but source diagnostics present the
  lexical capability that matters.
- Capabilities are the evidence that an operation may be performed. An effect
  row says what may happen; a capability says why it is permitted here.
- Handlers are language syntax, not only library functions.
- A handler may only intercept operations for capabilities/effects made visible
  by its lexical surface. Secret interception is rejected.
- The innermost matching handler handles an operation.
- Deep handlers are the v1 default: resumed computation is handled again by the
  same handler.
- Shallow handlers, multihandlers, and Frank-style operators are postponed.
- Raw continuations are not surface values. Handler clauses receive `resume`,
  which is typed and one-shot.
- Handler correctness against arbitrary algebraic equations is not a v1 feature.
  dx only checks type/effect/capability safety and selected runtime invariants.

## Operation And Handler Typing

Each effect declaration introduces an operation signature:

```text
effect E {
  op[A1, ..., An](arg: P): R
}
```

Typing obligations:

- Performing `op` requires a lexical capability for `E`.
- The operation argument must typecheck as `P`.
- The operation result is `R`.
- The enclosing computation effect row includes `E`, unless a handler eliminates
  it before the public boundary.
- A handler clause for `op` receives:

  ```text
  arg: P
  resume: R -> HandledResult / HandlerEffects
  ```

- The handler return clause transforms the handled computation's final result.
- All clauses of a handled nominal effect must be present unless the handler
  explicitly forwards the unhandled operations.

## Resumption Policy

Resumptions are affine one-shot in v1:

- `resume` may be called at most once.
- Double resume is a static error where the checker can prove it; otherwise it
  is a runtime error.
- A resumption may not escape its handler scope.
- Resume-after-scope-exit is a runtime error if static checking misses it.
- A captured resumption must be resumed, returned through a structured
  cancellation/discontinue path, or deliberately abandoned by a runtime path
  that performs cleanup.
- Resumptions may cross Java threads only through compiler/runtime-approved
  async bridges.

Optimization classes:

| Clause form | Lowering implication |
|---|---|
| Never resumes | Lower like exception/abort; no continuation allocation needed. |
| Tail-resumes exactly once | Eligible for direct call/state-threading optimization. |
| Non-tail one-shot resume | Requires continuation-aware lowering. |
| Multiple resume sites | Rejected in v1 unless statically mutually exclusive; otherwise runtime one-shot guard required. |
| Multi-shot resume | Rejected in v1. |

## Selective Lowering Classification

Effects are also classified by lowering need:

| Lowering class | Examples | Compiler action |
|---|---|---|
| `Direct` | Pure, simple `Throws[E]`, non-resumptive validation | No CPS/state machine. |
| `DirectWithHandlerFrame` | Logging, reader-like handlers that tail-resume | Direct handler frame or inlined handler. |
| `OneShotCapture` | User handlers with non-tail resume | Selective CPS/continuation IR for affected region. |
| `AsyncSuspend` | `Async.await`, `sleep`, callbacks | State-machine/continuation lowering plus runtime cancellation. |
| `FuturePureMultiShot` | Pure `Amb` only, post-v1 | Not implemented in v1. |

The selective lowering decision is type-directed. A function is transformed only
if its checked effect/capability usage requires continuation capture or async
suspension. Polymorphic effect functions are conservatively transformed until
specialization proves a direct version is enough.

## Diagnostics Requirements

Effect diagnostics should report:

- Missing capability, including the lexical operation site.
- Effect escaping a public API without an annotation.
- Handler missing an operation clause or explicit forwarding rule.
- Illegal capability escape from a handler/resource/async scope.
- Non-replayable effect captured by a future multi-shot or differentiable region.
- One-shot violation when a handler clause may call `resume` more than once.

Diagnostics must not expose raw row-unification variables unless the user asks
for an expert/debug mode.
