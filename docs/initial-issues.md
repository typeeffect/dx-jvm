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

Status:

- Initial hand-written lexer/parser implemented in `compiler/frontend`.
- Supports literals, variables, `val` sequences, `if`, blocks, pairs, thunks, force, typed lambdas, application, and closure capture in the JVM vertical slice.
- Includes frontend-to-CBPV lowering and an end-to-end interpreter/JVM equivalence test for the supported pure subset.

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

Status:

- Implemented in `compiler/backend-jvm`.
- Covered by bytecode verification, execution, generated class naming, and stack trace line tests.

## DX-004: Add CLI Script Runner

Goal: make the prototype executable outside tests while keeping the same compiler path.

Deliverables:

- `cli` Gradle module.
- `dx check <file.dx>` command.
- `dx compile <file.dx> -d <output-dir>` command.
- `dx run <file.dx>` command.
- Script execution through frontend lowering, CBPV typechecking, pure JVM backend, generated class loading, and `eval`.
- Disk classfile emission for the generated main class and support classes.
- Source-rendered frontend and type diagnostics.
- Checked-in example script.

Exit criteria:

- `gradle :cli:run --args="check examples/cli/branch_closure.dx"` prints an `ok` line.
- `gradle :cli:run --args="compile examples/cli/branch_closure.dx -d build/dx-cli-example"` writes `.class` files.
- CLI tests load emitted classfiles back from disk and invoke `eval()`.
- `gradle :cli:run --args="run examples/cli/branch_closure.dx"` prints `pair(ok, cli)`.
- CLI tests cover success, source-rendered frontend/type diagnostics, and usage errors.

Status:

- Implemented in `cli`.

## DX-005: Align Core IR With Levy CBPV F/U And Computation Functions

Goal: remove the Stage -1 function/lambda shortcut and align the executable
core with Levy-style CBPV before async and handler lowering depend on the
current value-closure representation.

Deliverables:

- Update `spec/cbpv-core.md` grammar to make `F` and `U` explicit.
- Move function types from value types to computation types.
- Represent lambda as a computation, not a value.
- Lower source lambdas to thunked computation values.
- Lower source calls through explicit bind sequencing and `force`.
- Preserve source spans through the new lowering path.
- Update interpreter, type checker, frontend lowering, JVM pure backend, and
  CLI tests.

Exit criteria:

- Existing pure frontend/JVM vertical-slice tests pass through the new
  representation.
- New semantic tests cover `return`/`bind` laws, `force(thunk M)`, lambda
  application, and call evaluation order.
- Negative tests reject computation-only terms in value positions.

Status:

- Identified by Levy CBPV alignment audit.

## DX-006: Implement Effect Row Solver And Capability Evidence Model

Goal: implement the paper-aligned effect checker model: nominal source effects,
structural normalized rows internally, lexical capability evidence, and
diagnostics that report missing evidence rather than row-unifier internals.

Deliverables:

- Effect declaration parser/AST.
- Operation signature table.
- Scoped-label-inspired internal row representation.
- Capability evidence representation in typed AST/CBPV lowering.
- Diagnostics for missing capability, missing handler clause, and public effect
  escape.

Exit criteria:

- A `Log`/`Ask` user effect can be declared, performed, handled, and rejected
  when unhandled.
- Multiple lexical capabilities for the same nominal effect produce clear
  diagnostics.

Status:

- Specified in `spec/effects.md` after paper audit.

## DX-007: Spike Type-Directed Selective CPS/State-Machine Lowering

Goal: validate the Leijen/Koka-style selective lowering strategy on the JVM
before async and handlers grow around an unsuitable representation.

Deliverables:

- Lowering classifier for direct, tail-resume, one-shot capture, and async
  suspension effects.
- Minimal continuation IR.
- One handler example with tail resume compiled without general continuation
  allocation.
- One handler or await example lowered through explicit continuation state.

Exit criteria:

- Pure code remains direct bytecode.
- Effectful code transforms only the region that needs continuation capture.
- Source spans survive into lowered states.

Status:

- Specified in `spec/effects.md`, `spec/async.md`, and `spec/jvm-backend.md`
  after paper audit.

## DX-008: Define One-Shot Runtime Cleanup And Debug Model

Goal: turn the OCaml handlers engineering lessons into JVM-specific runtime
requirements for continuations, cancellation, cleanup, and stack traces.

Deliverables:

- Runtime `Resumption` state machine: fresh, resumed, discontinued, escaped.
- Double-resume and resume-after-scope-exit diagnostics.
- Cleanup/discontinue path for abandoned continuations.
- Async logical stack trace format.
- Tests for cancellation through `use`/`defer`.

Exit criteria:

- A cancelled async task runs cleanup exactly once.
- A double-resumed continuation is detected.
- A leaked continuation produces a source-spanned runtime diagnostic in debug
  mode.

Status:

- Specified in `spec/async.md` and `spec/jvm-backend.md` after paper audit.
