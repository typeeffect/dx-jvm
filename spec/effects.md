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
