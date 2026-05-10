# Initial Issues

These are local issue drafts until a hosted tracker exists.

## DX-001: Define syntax subset and parser strategy

Goal: freeze the first parseable dx subset and decide ANTLR vs hand-written parser for the prototype.

Deliverables:

- `spec/syntax.md` first real draft.
- Parser ambiguity notes for DSL blocks, trailing lambdas, named arguments, and `await`.
- Golden parse tests for the day-30 examples.

Exit criteria:

- Parser accepts the MVP syntax subset.
- Parser emits stable source spans usable by diagnostics and formatter.

## DX-002: Define CBPV Core executable semantics

Goal: define and implement a tiny executable semantics for CBPV Core before backend work hides semantic bugs.

Deliverables:

- `spec/cbpv-core.md` first real draft.
- Kotlin executable interpreter for values, computations, `return`, `bind`, `thunk`, `force`, `perform`, `handle`, and one-shot resumptions.
- At least 20 semantic tests.

Exit criteria:

- Tests cover pure computation, sequencing, handler resume, double resume rejection, and continuation escape rejection.

## DX-003: Spike ASM hello world with source line metadata

Goal: validate the JVM backend baseline and debug metadata strategy.

Deliverables:

- Minimal ASM bytecode generation spike.
- Generated class with `main`, `println`, local variable table, and line number table.
- Bytecode verifier test.

Exit criteria:

- Generated class runs on JDK 21.
- Stack trace/source line metadata points back to the dx source fixture.
