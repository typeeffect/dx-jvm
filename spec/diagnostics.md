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

Current CLI behavior:

- `dx check <file.dx>` returns exit code `0` after successful frontend lowering, typechecking, pure-effect validation, and JVM bytecode generation.
- `dx compile <file.dx> -d <output-dir>` additionally writes generated classfiles and prints each written path.
- `dx run <file.dx>` additionally loads generated classes, invokes `eval`, and prints the result.
- Frontend diagnostics render as `file:line:column: error: ...` followed by the source line and caret marker.
- Type diagnostics render with the same source-line format when frontend lowering produced a `TypedSourceMap`.
- Structural type diagnostics remain available through `TypeCheckResult.diagnostics` for tests and programmatic tooling.
- JVM backend diagnostics are grouped under `jvm backend diagnostics:` and remain structural for now.
- Invalid usage and missing files return exit code `2`.
- Compile/runtime rejection returns exit code `1`.
- Source-spanned JVM backend diagnostics are future work.
