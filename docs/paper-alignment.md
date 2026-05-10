# Research Paper Alignment

Status: project prescription. The PDFs are copied locally under
`references/papers/` and extracted text is under `references/papers/text/`.
Both paths are ignored by Git through `references/`.

This document records what dx adopts from the papers and what it deliberately
does not adopt for the JVM production line.

## Priority

| Paper | Relevance | dx use |
|---|---:|---|
| Leijen 2016, "Type Directed Compilation of Row-typed Algebraic Effects" | Critical | Effect rows, scoped-label-inspired row solving, async as effect, type-directed selective CPS. |
| Sivaramakrishnan et al. 2021, "Retrofitting Effect Handlers onto OCaml" | Critical | One-shot continuations, runtime representation risks, stack/backtrace/debug constraints, resource cleanup for abandoned continuations. |
| Pretnar 2015, "An Introduction to Algebraic Effects and Handlers" | High | Pedagogical semantics, operation/resume typing, deep handler intuition, examples for docs/tests. |
| Plotkin and Pretnar 2013, "Handling Algebraic Effects" | High | Foundational handler semantics, handler correctness warnings, algebraic model discipline. |
| Lindley, McBride, McLaughlin 2017, "Do Be Do Be Do" | Medium-high | Bidirectional typing, ambient ability/capability intuition, no secret interception, CBPV influence. |
| Sigal, "Automatic Differentiation via Effects and Handlers" | Strategic post-MVP | AD via effect interpretation, forward/reverse/taped/checkpointed modes, asymptotic benchmarks. |

## Adopted Prescriptions

### Leijen 2016

Adopt:

- Use row-like effect typing internally, with readable nominal effect names at
  the source/API level.
- Use a scoped-label-inspired row solver so repeated/shadowed effect instances
  can be represented internally without confusing users.
- Compile handlers with a type-directed selective CPS/state-machine strategy.
  Full CPS is rejected as a default JVM strategy.
- Use effect information to decide which functions need continuation-aware
  lowering.
- Optimize operation clauses that do not resume and clauses that tail-resume.
- Treat async/await as an effectful direct-style abstraction, but special-case
  its lowering for production cancellation, debugging, and Java interop.

Do not adopt wholesale:

- Do not expose Koka-style row syntax directly as the primary user experience.
- Do not require every handled effect to use one generic runtime handler stack
  when a compiler-known direct lowering is available.

### OCaml Handlers 2021

Adopt:

- Continuations are one-shot in v1.
- Double resume is a runtime error where static checking cannot rule it out.
- A captured continuation must be either resumed or deliberately discontinued
  through cancellation/cleanup.
- Debuggability is a runtime design requirement, not a later tooling feature.
- Avoid whole-program CPS when it destroys useful JVM stack traces for code that
  does not need it.
- Keep foreign/JVM calls outside captured continuations unless explicitly
  bridged.

Do not adopt wholesale:

- Do not attempt OCaml-style native stack/fiber copying on the JVM.
- Do not make untyped effects the language model.

### Pretnar 2015 And Plotkin-Pretnar 2013

Adopt:

- Operation signatures specify parameter and result types.
- A handler clause receives the operation parameter and a typed resumption.
- Deep handlers are the default in v1.
- The innermost matching handler handles an operation.
- Handler return clauses are typed transformations of the handled computation's
  final result.
- Algebraic correctness is nontrivial; dx v1 must not promise arbitrary handler
  equation checking.

Do not adopt wholesale:

- Do not support unrestricted multi-shot continuations in v1.
- Do not expose raw continuation values as ordinary first-class values.

### Frank 2017

Adopt:

- Use bidirectional typing and explicit public boundaries to keep diagnostics
  readable.
- Treat effects/capabilities as permissions/evidence, not only as a global list
  of possible side effects.
- Prevent secret interception: a handler can only handle capabilities/effects
  that its lexical surface makes visible.
- Use the "ambient ability" idea as design inspiration for lexical capability
  propagation.

Do not adopt wholesale:

- Do not make Frank-style operators/multihandlers a v1 surface feature.
- Do not choose shallow handlers as default in v1.
- Do not chase maximal silent effect polymorphism before diagnostics are proven.

### Sigal AD

Adopt:

- AD is a strong validation track for effects and handlers.
- Numeric/tensor primitive operations can be exposed as effects and interpreted
  by handlers for evaluation, forward mode, reverse mode, taped reverse mode, and
  checkpointing.
- Reverse-mode production design should use tape/graph representations rather
  than unrestricted continuation cloning.
- AD regions must reject non-replayable effects by default.

Do not adopt for MVP:

- GPU backend, distributed training, tensor compiler, arbitrary Java mutation,
  and multi-shot continuation-based checkpointing.

## dx Design Consequences

1. The core compiler pipeline remains CBPV-first, then effect/capability
   elaboration, then selective continuation-aware lowering.
2. The effect checker must classify effects by runtime lowering needs:
   direct/no-capture, one-shot capture, async suspension, and future pure
   multi-shot.
3. The runtime must include explicit cancellation/discontinue paths for captured
   continuations.
4. Public API metadata must expose effects, but Java ABI must use explicit
   exports such as `CompletableFuture`, `Task`, callbacks, or blocking wrappers.
5. Diagnostics must speak in capability/effect terms and avoid raw row-unifier
   output.
6. AD remains a post-MVP strategic track with its own spec and benchmarks.
