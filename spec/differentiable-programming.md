# dx Differentiable Programming Spec

Status: post-MVP strategic track. This spec is informed by the AD paper copied
under `references/papers/sigal-automatic-differentiation-via-effects-and-handlers.pdf`.

## Position

Automatic Differentiation is not part of the MVP compiler. It is a strategic
validation track for dx's typed effects, lexical capabilities, handler
semantics, purity boundaries, and future tensor IR.

## Design Rules

- AD is not global runtime magic.
- AD is modeled by typed effects/capabilities and handlers.
- Numeric primitive operations may be exposed through a `Smooth`/`Diff`
  operation interface.
- Handlers can interpret the same program as evaluation, forward-mode AD,
  reverse-mode AD, taped reverse-mode AD, or checkpointed reverse mode.
- Reverse-mode production design uses a tape/graph representation rather than
  unrestricted continuation cloning.
- Tensor AD requires a dedicated tensor type, `TensorOps` effect, shape
  discipline, and Tensor IR before production ML claims.
- Differentiable regions reject unsafe/non-replayable effects by default.

## Initial Effects

```text
effect Smooth {
  const(x: Float64): Number
  unary(op: UnarySmoothOp, x: Number): Number
  binary(op: BinarySmoothOp, left: Number, right: Number): Number
}

effect Diff
effect TensorOps
effect Shape
effect DeterministicRandom
```

The exact numeric hierarchy is postponed. The first spike may use `Double`
only.

## Region Policy

```text
grad { ... } accepts:
  Pure
  Smooth
  TensorOps
  Diff
  Shape
  DeterministicRandom

grad { ... } rejects by default:
  Async
  Resource
  IO
  Lock
  JavaMutation
  Unsafe
  unknown Java calls
```

Async stays outside differentiable regions in v1:

```text
val batch = await(loadBatch())
val gradients = grad {
  loss(model(batch.x), batch.y)
}
```

Rejected:

```text
grad {
  val batch = await(loadBatch())
  loss(model(batch.x), batch.y)
}
```

Rationale: async suspension is one-shot and scope/cancellation-sensitive, while
reverse-mode AD may trace, replay, checkpoint, or reorder pure computation.

## Roadmap

| Stage | Goal |
|---|---|
| AD-0 | Scalar forward-mode AD library experiment over pure `Double` math. |
| AD-1 | Scalar reverse-mode with mutable derivative cells hidden behind a handler. |
| AD-2 | Taped scalar reverse-mode with explicit tape data structure. |
| AD-3 | `grad` and `valueAndGrad` APIs with effect-policy diagnostics. |
| AD-4 | Tensor type, `TensorOps` effect, shape diagnostics. |
| AD-5 | Reverse-mode tensor AD with tape/graph representation. |
| AD-6 | `noGrad`, `detach`, gradient checking, and AD diagnostics. |
| AD-7 | Tensor IR, fusion, memory planning, Vector API/BLAS backend. |
| AD-8 | Optional StableHLO/ONNX/GPU exploration. |

## Non-Goals Before v1

- GPU training framework.
- XLA/JAX-level compiler.
- Distributed training.
- AD through arbitrary Java mutation.
- AD through `Async`, `Resource`, locks, or unknown Java calls.
- Multi-shot continuation-based checkpointing.

## First Spike

Purpose: validate effects as an interpretation mechanism for numeric programs.

Scope:

- `Double` only.
- Pure scalar expressions.
- Forward-mode dual numbers.
- Reverse-mode scalar tape.
- No tensors.
- No async/resource effects inside `grad`.

Success:

```text
grad(x -> x * x + sin(x))(3.0)
```

returns the expected derivative within floating-point tolerance, and effect
policy diagnostics reject `await`, file IO, and unknown Java mutation inside
the differentiable region.
