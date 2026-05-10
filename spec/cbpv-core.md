# dx CBPV Core Spec

Status: draft. The current implementation is a CBPV-inspired executable
semantics with one known prototype shortcut: functions/lambdas are currently
represented as value closures. Full Levy-style CBPV treats function types and
lambdas as computation forms; that is the normative direction for the next core
revision.

Scope:

- Value types and computation types.
- `return` and `bind`.
- `thunk` and `force`.
- Capability operations.
- Handler core forms.
- One-shot resumption semantics.
- Executable semantics tests.

Initial goal:

- Define a tiny executable semantics for CBPV Core before relying on JVM bytecode lowering.

Stage -1 implementation:

- Module: `compiler/cbpv-core`.
- Implementation language: Kotlin/JVM.
- Current executable forms: `return`, `bind`, `if`, `thunk`, `force`, prototype value-closure function application, `perform`, `handle`.
- Current safety checks: unhandled effect, missing operation, type mismatch, one-shot double resume, resume after handler scope exit.
- Current semantic test target: at least 20 focused tests before backend lowering depends on these semantics.

Typed core implementation:

- Module: `compiler/cbpv-core`.
- Types are split into `ValueType` and `ComputationType`.
- `ComputationType` records both result type and effect set.
- `TypedSourceMap` optionally associates typed CBPV values/computations with frontend source spans without making CBPV depend on frontend AST classes.
- `TypeCheckResult` includes both legacy structural diagnostics and source-aware diagnostic reports.
- `if` is a computation form: the condition must be a `Bool` value, both branches must return the same value type, and branch effects are unioned.
- Effect signatures define operation argument types and operation result type.
- `perform` infers the operation result type and adds the effect to the computation row.
- `handle` eliminates the handled effect and keeps effects introduced by handler clauses.
- `resume` is only valid inside a handler clause and must resume with the operation result type.
- `resume` returns the handled computation result back to the handler clause; this makes double-resume programs expressible in the executable core and lets the one-shot runtime check reject them.
- `checkClosed` verifies that inferred effects are covered by an explicit allowed effect set.

## Levy CBPV Alignment

Normative source: Paul Blain Levy, "lambda-calculus, effects and
call-by-push-value", April 18, 2026. A local copy is kept under
`references/levy/mgsfastlam.pdf`, which is intentionally ignored by Git.

The SOTA prescription for dx is:

- Maintain two judgments: values are checked separately from computations.
- Keep source variables bound to value types.
- Keep effectful sequencing explicit through `bind`, not implicit in arbitrary
  expression nesting.
- Treat `F A` as the computation type of a computation that may perform effects
  and eventually return a value of type `A`.
- Treat `U C` as the value type of a thunked computation of computation type
  `C`.
- Treat `force` as a computation, not as a value-level projection.
- Keep complex effectful constructs out of the value grammar.
- Use an explicit stack/continuation discipline after effect elaboration for
  handlers, async lowering, and debugging.

Current prototype mapping:

| Levy CBPV concept | Current dx prototype | Status |
|---|---|---|
| Separate value/computation judgments | `TypedValue` and `TypedComputation` | Aligned |
| `return` and sequencing | `TypedComputation.Return` and `Bind` | Aligned |
| `F A` | `ComputationType(resultType, effects)` | Partially aligned; spelling also carries effect set |
| `U C` | `ValueType.ThunkType(ComputationType)` | Aligned |
| `thunk`/`force` | `TypedValue.ThunkValue` and `TypedComputation.Force` | Aligned |
| Function types as computation types | `ValueType.FunctionType` closure values | Gap |
| Lambda as computation | `TypedValue.Lambda` | Gap |
| CBV source function translation | Direct closure value | Gap |
| Explicit stack/continuation IR | Host interpreter plus one-shot resumption checks | Stage -1 only |

Required next-core changes:

1. Introduce explicit type constructors for `F` and `U` in the spec and align
   Kotlin names with them. The effect set may remain attached to `F A`.
2. Move function type from value types to computation types:

   ```text
   Computation types C ::= F A ! Eff | A -> C | ...
   ```

3. Represent lambda as a computation:

   ```text
   Gamma, x : A |-c M : C
   -----------------------
   Gamma |-c lambda x. M : A -> C
   ```

4. Lower source-level CBV functions to thunked computation values:

   ```text
   fun x -> body
     ~~> return thunk (lambda x. lower(body))
   ```

5. Lower source application through sequencing of the function expression and
   argument expression before forcing the function thunk:

   ```text
   f(a)
     ~~> bind lower(f) as fv in
         bind lower(a) as av in
         (force fv) av
   ```

6. Add executable semantic tests for the sequencing laws:

   ```text
   bind (return V) as x in M == M[V/x]
   M == bind M as x in return x
   bind (bind M as x in N) as y in P == bind M as x in bind N as y in P
   force (thunk M) == M
   ```

7. Add negative tests that reject effectful computations in value-only
   positions, instead of silently compiling them through hidden sequencing.

This keeps the current vertical slice useful while preventing it from becoming
the final semantic model by accident.
