# dx Syntax Spec

Status: draft placeholder.

Scope:

- Source file structure.
- Script mode.
- Declarations.
- Expressions.
- DSL block syntax.
- Reserved words and intrinsic names.

Initial decision:

- `await` is parsed as a normal-looking function call and resolved as a compiler-known stdlib intrinsic, not as a hard reserved keyword unless ambiguity forces that later.

Initial executable frontend subset:

- Literals: `unit`, booleans, integers, strings.
- Variables.
- `val name = expr; body` sequences.
- Blocks: `{ stmt; body }`.
- Pairs: `pair(a, b)`.
- Thunks: `thunk { expr }`.
- Forcing: `force expr`.
- Lambda/application parsing exists, but lambda lowering is blocked until parameter type syntax is decided.

Frontend pipeline:

```text
.dx source
  -> lexer with source spans
  -> parser AST
  -> CBPV lowering
  -> typed CBPV checker / evaluator / JVM backend
```

Current module: `compiler/frontend`.
