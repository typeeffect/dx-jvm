# Levy CBPV Alignment Audit

Status: project prescription. This audit verifies whether dx is following the
call-by-push-value guidance in Paul Blain Levy's April 18, 2026 slides,
"lambda-calculus, effects and call-by-push-value".

Source:

- Public PDF: https://pblevy.github.io/mgsfastlam.pdf
- Local ignored copy: `references/levy/mgsfastlam.pdf`
- Local ignored extracted text: `references/levy/mgsfastlam.txt`

## Verdict

dx is correctly moving in the CBPV direction, but the current executable core is
not yet full Levy-style CBPV.

Aligned:

- Values and computations are represented separately.
- `return` and `bind` make sequencing explicit.
- `thunk` and `force` are explicit.
- Effects live on computations, not on pure values.
- One-shot resumption checks fit the production policy for handlers and async.

Main gap:

- The prototype still represents functions/lambdas as value-level closures.
  Levy-style CBPV treats function types and lambdas as computation forms. For
  dx this is acceptable as a Stage -1 vertical-slice shortcut, but it must not
  become the final core semantics.

## Audit Table

| Levy prescription | Current dx status | Gap/risk | Required action |
|---|---|---|---|
| Distinguish value judgments from computation judgments. | `TypedValue` and `TypedComputation` are separate. | Low. | Keep this split as non-negotiable in all frontend lowering and backend IR. |
| Context variables bind values. | Type environments bind value types. | Low. | Continue rejecting computation-only terms in value positions. |
| Computations return values through an `F A`-like type. | `ComputationType(resultType, effects)` represents this. | Medium: the `F` constructor is implicit. | Make `F` explicit in the spec and eventually in core names. Effects may remain attached to `F`. |
| Thunks are values of `U C`. | `ValueType.ThunkType(ComputationType)` exists. | Low. | Keep `U` explicit in the core. Do not erase thunks during early effect lowering. |
| `force` is a computation. | `TypedComputation.Force` exists. | Low. | Preserve this invariant in parser lowering and bytecode lowering. |
| Sequencing is explicit. | `Bind` is explicit; frontend lowering emits it for `val`. | Medium: application still has a direct closure path. | Lower general source calls through bind sequencing of function and argument expressions. |
| Function types are computation types. | Current `FunctionType` is a value type. | High semantic mismatch. | Introduce computation-level function types `A -> C`. Source functions lower to thunked computation values. |
| Lambda is a computation. | Current `Lambda` is a value. | High semantic mismatch. | Move lambda to computation IR in the next core revision. |
| CBV functions translate to thunked computations. | Current closures are direct values. | Medium for pure code, high once async/effects enter calls. | Use `U(A -> F B)` as the internal representation for source-level function values. |
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

Immediate issue:

- Align the core IR with explicit `F`/`U` and computation-level functions.

Acceptance criteria:

- The spec grammar has value types and computation types matching the normative
  CBPV shape.
- Source lambdas lower to `return thunk (lambda ...)`.
- Source application lowers through two binds and a force.
- Existing pure closure JVM tests still pass through the new representation.
- Negative tests reject effectful computation terms in value-only positions.

Until that work is complete, docs should describe the current compiler as a
"CBPV-inspired vertical slice", not as a finished CBPV implementation.
