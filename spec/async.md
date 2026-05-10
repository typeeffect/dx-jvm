# dx Async Spec

Status: draft placeholder.

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
