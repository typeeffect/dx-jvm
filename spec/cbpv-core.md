# dx CBPV Core Spec

Status: draft placeholder.

Scope:

- Value types and computation types.
- `return` and `bind`.
- `thunk` and `force`.
- Capability operations.
- Handler core forms.
- One-shot resumption semantics.
- Executable semantics tests.

Initial goal:

- Define a tiny executable semantics for CBPV Core before relying on JVM bytecode lowering.

Stage -1 implementation:

- Module: `compiler/cbpv-core`.
- Language: Kotlin/JVM.
- Current executable forms: `return`, `bind`, `thunk`, `force`, function application, `perform`, `handle`.
- Current safety checks: unhandled effect, missing operation, type mismatch, one-shot double resume, resume after handler scope exit.
- Current semantic test target: at least 20 focused tests before backend lowering depends on these semantics.
