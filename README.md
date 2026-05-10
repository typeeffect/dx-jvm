# dx-jvm

Independent JVM-focused research and prototype line for **dx**.

This workspace is intentionally isolated from any previous `dx` repository state.
The first implementation target is an executable CBPV Core semantics that can
validate typed effects, lexical handlers, and one-shot resumptions before JVM
bytecode lowering begins.

Current focus:

1. CBPV Core executable semantics.
2. Effect/capability model.
3. One-shot continuation safety.
4. Later: JVM 21 bytecode backend and Java interop.
