# dx Async Spec

Status: draft, aligned with the current paper audit in
`docs/paper-alignment.md`.

Scope:

- `Async` effect.
- `await` intrinsic.
- `Task`.
- Structured scopes.
- Cancellation.
- `CompletableFuture` interop.
- Java export ABI modes.

Initial decision:

- Colorless async is a source-language property, not an ABI illusion.
- Java boundaries must expose async explicitly as `CompletableFuture`, `Task`, callback, or blocking wrapper.

## Semantic Model

`Async` is a built-in effect with compiler-recognized stdlib intrinsics.

```text
await(cf: CompletableFuture[A]): A / { Async }
await(task: Task[A]): A / { Async }
sleep(duration: Duration): Unit / { Async }
fork(body: () -> A / { Async, e }): Task[A] / { Async, e }
```

`await` is parsed as a normal-looking call and resolved as a stdlib intrinsic.
It is not a hard keyword unless the grammar later forces that choice.

Colorless async means:

- Business code is written in direct style.
- There is no source-level contagious `async`, `suspend`, `Promise<T>`, or
  `Future<T>` return type in dx call chains.
- The effect row still records `{ Async }`.
- Public dx metadata includes the async effect.
- Java ABI exports must choose an explicit mode.

## Java Export ABI Modes

| Mode | Java shape | Use |
|---|---|---|
| `export async` | `CompletableFuture<T>` or `Task<T>` | Non-blocking Java callers. |
| `export blocking` | `T` plus declared Java throws where applicable | Legacy Java callers and scripts. |
| `export callback` | Callback/listener adapter | Java libraries with callback conventions. |
| `export dx-only` | dx metadata only | Internal dx APIs requiring handlers/capabilities. |

No Java caller sees "colorless" async by illusion. The boundary is explicit.

## Lowering Policy

Async lowering is type/effect directed:

- Pure functions are not transformed.
- Functions with `{ Async }` are lowered to continuation/state-machine form only
  across suspension-reachable regions.
- Already-completed futures may use a fast direct path.
- Delayed futures register a one-shot continuation with the runtime.
- Cancellation resumes the continuation with a cancellation path or discontinues
  it so cleanup/finalizers run.
- `finally`, `defer`, and `use` scopes must be represented in the lowered state
  machine before any suspension point may cross them.

This is deliberately not full-program CPS. Full CPS would simplify the theory
but would damage JVM stack traces, allocation profiles, and Java interop for code
that never suspends.

## Continuation Rules

- Async continuations are one-shot.
- Double completion of a Java future bridge must not double-resume dx code.
- A continuation cannot be resumed after its structured scope has exited.
- A continuation can cross Java threads only through runtime-owned bridges.
- `ThreadLocal` propagation is explicit through dx runtime context; it is not a
  blind copy of all Java thread locals.

## Structured Concurrency

Structured scopes own child tasks:

- Child tasks are cancelled when the parent scope fails or exits early.
- Parent waits for children unless explicitly detached through an unsafe API.
- Resource cleanup runs on all cancellation paths.
- Exceptions from children are aggregated or selected by a documented policy.

The runtime may use Java virtual threads for blocking Java calls, but virtual
threads are an implementation option, not the semantic definition of `Async`.

## CompletableFuture Bridge

Bridge requirements:

- `await(cf)` registers a completion callback.
- Completion races are guarded by an atomic one-shot state.
- Java cancellation of the `CompletableFuture` maps to dx cancellation where
  possible.
- dx cancellation attempts to cancel the Java future where safe, but must still
  run dx cleanup if Java cancellation is ignored.
- Exceptions complete as `Throws[E]` if typed at the boundary or as unchecked
  failure/panic if the Java API gives no usable checked type.

## Debugging

Async lowering must preserve:

- dx source file and line metadata.
- Logical async stack frames at suspension points.
- Runtime diagnostics for leaked, double-resumed, or late-resumed continuations.
- A mode to print both JVM stack frames and dx logical frames.
