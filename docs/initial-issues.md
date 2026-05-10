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
