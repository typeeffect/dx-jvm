# dx-jvm

Independent JVM-focused research and prototype line for **dx**.

This workspace is intentionally isolated from any previous `dx` repository state.
The first implementation target is an executable CBPV Core semantics that can
validate typed effects, lexical handlers, and one-shot resumptions before JVM
bytecode lowering begins.

CBPV status: the current vertical slice is CBPV-inspired and executable. The
normative core direction follows Levy-style `F`/`U`, explicit sequencing, and
computation-level functions. The current lambda-as-value closure model is a
documented Stage -1 shortcut; see `docs/levy-cbpv-alignment.md`.

Current focus:

1. CBPV Core executable semantics.
2. Effect/capability model.
3. One-shot continuation safety.
4. JVM 21 bytecode backend spike.
5. CLI/script runner vertical slice.
6. Later: Java interop and colorless async lowering.

## Run The Current Prototype

The CLI currently supports the pure executable frontend subset and runs scripts
through the typed CBPV checker and JVM bytecode backend.

```bash
gradle :cli:run --args="check examples/cli/branch_closure.dx"
gradle :cli:run --args="compile examples/cli/branch_closure.dx -d build/dx-cli-example"
gradle :cli:run --args="run examples/cli/branch_closure.dx"
```

Expected `run` output:

```text
pair(ok, cli)
```

`compile` writes JVM `.class` files under the requested output directory using
the generated JVM internal names, including closure support classes.
