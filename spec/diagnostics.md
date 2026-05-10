# dx Diagnostics Spec

Status: draft placeholder.

Scope:

- Parser diagnostics.
- Type diagnostics.
- Effect/capability diagnostics.
- Continuation misuse diagnostics.
- Async lowering/debug diagnostics.
- Java interop diagnostics.

Initial goal:

- Diagnostics should report missing capabilities and unsafe escapes directly, not expose raw row-unification internals.
