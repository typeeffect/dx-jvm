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
- Implementation language: Kotlin/JVM.
- Current executable forms: `return`, `bind`, `if`, `thunk`, `force`, function application, `perform`, `handle`.
- Current safety checks: unhandled effect, missing operation, type mismatch, one-shot double resume, resume after handler scope exit.
- Current semantic test target: at least 20 focused tests before backend lowering depends on these semantics.

Typed core implementation:

- Module: `compiler/cbpv-core`.
- Types are split into `ValueType` and `ComputationType`.
- `ComputationType` records both result type and effect set.
- `if` is a computation form: the condition must be a `Bool` value, both branches must return the same value type, and branch effects are unioned.
- Effect signatures define operation argument types and operation result type.
- `perform` infers the operation result type and adds the effect to the computation row.
- `handle` eliminates the handled effect and keeps effects introduced by handler clauses.
- `resume` is only valid inside a handler clause and must resume with the operation result type.
- `resume` returns the handled computation result back to the handler clause; this makes double-resume programs expressible in the executable core and lets the one-shot runtime check reject them.
- `checkClosed` verifies that inferred effects are covered by an explicit allowed effect set.
