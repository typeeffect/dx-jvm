# dx Effects Spec

Status: draft placeholder.

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
