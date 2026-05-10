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

- `dx run <file.dx>` returns exit code `0` only after successful frontend lowering, typechecking, pure-effect validation, JVM bytecode generation, class loading, and `eval` execution.
- Frontend diagnostics are grouped under `frontend diagnostics:`.
- Type diagnostics are grouped under `type diagnostics:`.
- JVM backend diagnostics are grouped under `jvm backend diagnostics:`.
- Invalid usage and missing files return exit code `2`.
- Compile/runtime rejection returns exit code `1`.
- Diagnostic rendering is currently structural Kotlin data output; source-spanned, user-facing messages are still future work.
