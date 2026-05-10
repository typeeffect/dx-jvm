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
