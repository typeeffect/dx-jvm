# Levy CBPV Alignment Audit

Status: project prescription. This audit verifies whether dx is following the
call-by-push-value guidance in Paul Blain Levy's April 18, 2026 slides,
"lambda-calculus, effects and call-by-push-value".

Source:

- Public PDF: https://pblevy.github.io/mgsfastlam.pdf
- Local ignored copy: `references/levy/mgsfastlam.pdf`
- Local ignored extracted text: `references/levy/mgsfastlam.txt`

## Verdict

dx is correctly moving in the CBPV direction, and the executable core now
implements the main function/lambda correction identified by the first audit.

Aligned:

- Values and computations are represented separately.
- `return` and `bind` make sequencing explicit.
- `thunk` and `force` are explicit.
- Effects live on computations, not on pure values.
- One-shot resumption checks fit the production policy for handlers and async.

Resolved since the first audit:

- Functions/lambdas are no longer core value forms. Function types are
  computation types, lambdas are typed computations, and source-level lambdas
  lower to thunked computation values.

Remaining gap:

- The JVM pure backend still represents pure thunked lambda computations with
  generated JVM closure objects. This is a backend representation shortcut, not
  a core semantic shortcut.

## Audit Table

| Levy prescription | Current dx status | Gap/risk | Required action |
|---|---|---|---|
| Distinguish value judgments from computation judgments. | `TypedValue` and `TypedComputation` are separate. | Low. | Keep this split as non-negotiable in all frontend lowering and backend IR. |
| Context variables bind values. | Type environments bind value types. | Low. | Continue rejecting computation-only terms in value positions. |
| Computations return values through an `F A`-like type. | `ComputationType.ReturnType(resultType, effects)` represents this. | Low: Kotlin name is explicit enough for now. | Keep `F` explicit in specs; effects remain attached to `F`. |
| Thunks are values of `U C`. | `ValueType.ThunkType(ComputationType)` exists. | Low. | Keep `U` explicit in the core. Do not erase thunks during early effect lowering. |
| `force` is a computation. | `TypedComputation.Force` exists. | Low. | Preserve this invariant in parser lowering and bytecode lowering. |
| Sequencing is explicit. | `Bind` is explicit; frontend lowering emits it for `val` and function application. | Low. | Keep general source calls lowered through bind sequencing of function and argument expressions. |
| Function types are computation types. | `ComputationType.FunctionType`. | Low. | Keep functions out of value types. |
| Lambda is a computation. | `TypedComputation.Lambda`. | Low. | Keep lambda out of value forms. |
| CBV functions translate to thunked computations. | Source lambdas lower to `return thunk (lambda ...)`. | Low. | Keep `U(A -> F B)` as the internal representation for source-level function values. |
| Stacks/continuations provide the right semantic place for control. | Interpreter uses host calls plus runtime one-shot checks. | Medium for async/handler lowering. | Add explicit continuation/stack IR after capability elaboration. |
| State/control effects must preserve evaluation order. | Effects are computation-level and safety classes exist. | Medium around future multi-shot and Java mutation. | Keep multi-shot out of v1; classify replayability before any continuation cloning. |

## Prescriptions For dx

1. Treat CBPV as a core IR discipline, not as surface syntax.
2. Keep source dx direct-style and low ceremony; lower into CBPV before effect
   checking needs backend commitments.
3. Make the value/computation boundary visible in compiler data structures and
   diagnostics. Do not recover it late from bytecode lowering.
4. Use explicit `F`/`U` terminology in specs even if Kotlin class names remain
   transitional for a few commits.
5. Do not implement async as hidden `CompletableFuture` plumbing in expression
   trees. Async must lower from computation boundaries and explicit bind order.
6. Do not model user handlers as ordinary library callbacks only. Handler
   typing, resumption lifetime, and continuation cardinality require core
   compiler forms.
7. Use the stack/continuation part of CBPV as the design guide for handler and
   async lowering, source spans, and async stack traces.
8. Keep multi-shot continuations out of v1. If added later, they must be limited
   to replayable/pure regions and must not capture `Async`, `Resource`, `IO`,
   locks, unknown Java mutation, or unsafe capabilities.

## Implementation Follow-Up

Remaining immediate issue:

- Add explicit continuation/stack IR after capability elaboration.

Acceptance criteria:

- Handler and async lowering stop relying on host interpreter continuations.
- Source spans survive continuation/state-machine lowering.
- One-shot resume and discontinue paths are explicit in lowered IR.

Until that work is complete, docs should describe the current compiler as a
"CBPV-inspired vertical slice", not as a finished handler/async lowering
implementation.
