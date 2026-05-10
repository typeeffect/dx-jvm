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
- Conditionals: `if condition then thenExpr else elseExpr`.
- Blocks: `{ stmt; body }`.
- Pairs: `pair(a, b)`.
- Thunks: `thunk { expr }`.
- Forcing: `force expr`.
- Typed lambdas: `fun name: Type -> expr`.
- Function application: `f(arg)`.

Initial value types in source:

- `Unit`
- `Bool`
- `Int`
- `Str`

Current backend note:

- `if` lowers through CBPV to JVM conditional branches in the pure subset.
- Typed lambdas lower to JVM closure objects implementing the backend `DxFunction` ABI.
- Direct lambda application, lambda values stored in variables, and lexical capture are supported in the current pure JVM subset.

Frontend pipeline:

```text
.dx source
  -> lexer with source spans
  -> parser AST
  -> CBPV lowering
  -> typed CBPV checker / evaluator / JVM backend
```

Current module: `compiler/frontend`.
