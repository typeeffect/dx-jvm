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
- Surface binding syntax uses `val` and `var`; dx does not expose `let` as user syntax.
- dx keeps the source-language surface small and stable. New syntax is accepted
  only when library functions plus trailing lambdas, typed effects, and
  capabilities cannot provide good semantics, diagnostics, lowering, or Java
  ABI behavior.

## Small Surface Spec Policy

dx should be efficient for humans, tools, and LLMs by minimizing syntax and
maximizing typed library expressiveness.

Core surface constructs are reserved for features that need compiler-known
scope, control flow, resource safety, effects, async lowering, diagnostics, or
Java ABI integration:

- `val`, `var`, assignment.
- `fun`, calls, trailing lambdas, blocks.
- `if`, `while`, `break`, `continue`, `return`.
- `match` and data/variant declarations.
- `effect`, `handle`, capability parameters.
- `use`, `defer`.
- Package/import/export forms.
- Java interop annotations and ABI controls.

Prefer library APIs plus trailing lambdas for higher-level constructs:

- `retry { ... }`
- `withTimeout { ... }`
- `parallel { ... }`
- `transaction { ... }`
- configuration/build/test DSLs
- JSON/YAML builders

Design rules:

- Avoid multiple spellings for the same construct.
- Avoid syntax that can be represented as a typed function without losing
  safety, diagnostics, or performance.
- Keep desugaring explicit in the spec and source-spanned in the compiler.
- The formatter should normalize all optional layout choices to a small set of
  canonical forms.
- Diagnostics should suggest canonical dx idioms so generated code converges
  toward the small surface language.
- Internal IR complexity belongs in CBPV, effect/capability elaboration, and
  JVM lowering, not in user syntax.

## Surface Binding Policy

- `val name = expr` introduces an immutable local binding.
- `var name = expr` introduces a mutable local binding. Assignment and shared
  mutable capture must be explicit in the type/effect and safety model once
  `var` is implemented.
- `let` is reserved for specifications, CBPV pseudocode, and diagnostics that
  explain internal lowering. If a user writes `let`, diagnostics should suggest
  `val`.
- The executable frontend currently implements `val`; `var` is a planned
  source feature, not implemented in the current parser slice.

## Trailing Lambda / Tail Block Syntax

dx supports Kotlin-style trailing lambda syntax as a first-class source feature
for DSLs and control abstractions:

```text
retry(times = 3) {
  await(http.get(url))
}

build {
  dependencies {
    implementation("org.slf4j:slf4j-api:2.0.13")
  }
}
```

Rules for v1:

- If the last parameter of a function expects a function/block type, a block
  following the call is parsed as that final argument.
- The trailing block attaches to the immediately preceding call or recognized
  control form.
- V1 supports one trailing lambda per call. Multiple trailing lambda labels are
  postponed unless DSL ergonomics prove they are necessary.
- The block is statically typed. It is not Groovy-style dynamic delegate
  mutation.
- DSL receiver blocks are represented as typed receivers and/or lexical
  capabilities, not as implicit global dynamic lookup.

Current implementation status: trailing lambdas are specified here but not yet
implemented in the executable frontend.

## Control-Flow Surface Plan

The surface language should allow ordinary control flow and library-defined
control abstractions to share the same low-ceremony block shape:

```text
while { i < 10 } {
  if i == 5 {
    break
  }
  i = i + 1
  continue
}

retry(times = 3, backoff = 200.ms) {
  await(http.get(url))
}
```

Policy:

- `while`, `break`, and `continue` are planned language control forms, not
  standard-library functions.
- `break` and `continue` are valid only inside the nearest enclosing loop.
- `retry` should be a standard-library tail-lambda function, not a macro.
- The core `while` has compiler-known syntax and semantics. It may be
  elaborated internally to a lexical control capability/effect, but that is an
  implementation model, not the public definition of `while`.
- Users may build custom loop-like control abstractions with tail lambdas and
  effects, but those abstractions do not replace the primitive loop forms and
  do not automatically inherit core `break`/`continue`.
- The frontend must keep source spans for the condition block, body block,
  `break`, and `continue` so diagnostics can name the exact control target.

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
- Typed lambdas lower through CBPV as thunked computation-level lambdas, then to JVM closure objects implementing the backend `DxFunction` ABI in the pure backend subset.
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
