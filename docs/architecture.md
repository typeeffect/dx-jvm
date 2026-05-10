# Production JVM Language With Typed Effects And Colorless Async

Language name: **dx**.

Status: architecture draft and execution plan. Decisions are provisional until validated by compiler/runtime spikes.

Last reviewed: 2026-05-10.

## 1. Concise Architecture Recommendation

Build a new JVM-first compiler and runtime, implemented in Kotlin, inspired by Effekt, Koka, Flix, Kotlin, and Groovy, rather than forking any existing language. Start with an interpreter plus JVM bytecode backend, then harden bytecode generation.

The language should be:

- Statically typed by default, with local type inference and explicit public API boundaries.
- Groovy-like at the surface for scripts, DSLs, named arguments, closures, string interpolation, safe navigation, and optional semicolons.
- Kotlin-like for Java interop, nullability, data classes, sealed variants, extension methods, and tooling expectations.
- Effekt-like for lexical capabilities and direct-style user effects.
- Koka-like for effect rows and selective lowering.
- OCaml 5-like in production caution: continuations are one-shot in v1.

Key decisions:

| Area | Recommendation |
|---|---|
| Compiler | New compiler in Kotlin, using ANTLR or a hand-written Pratt/recursive descent parser initially; ASM for bytecode. |
| Core IR | Call-by-push-value-inspired IR, separating values from computations before effect, handler, async, and continuation lowering. |
| JVM baseline | Java 21 minimum. Virtual threads are final in JDK 21. Structured concurrency is still preview as of JDK 26, so wrap it behind our runtime API. |
| Effects | Nominal effect declarations with structural row-like effect sets. Public signatures include effects. |
| Capabilities | Lexical capabilities, compiler tracked. Capabilities cannot escape their region unless declared safe/exportable. |
| Async | Built-in `Async` effect with compiler-recognized operations and library runtime. Source-level colorless, type/effect-visible. |
| Lowering | Hybrid: selective CPS/state-machine for `Async` and resumable handlers; virtual threads for blocking Java interop. |
| Continuations | One-shot affine resumptions in v1. Runtime detects double resume and resume after scope close. |
| Multi-shot | Not in v1. Future selective multi-shot only for explicitly pure handlers. |
| Interop | Java types are visible. Nullability follows Kotlin-style nullable/platform types. Checked exceptions become effects at boundaries. |
| Tooling | CLI, formatter, diagnostics, Gradle plugin, and LSP are early deliverables, not afterthoughts. |
| Standard library | Small. Prefer Java libraries. Provide effect/async/resource wrappers and ergonomic collection/JSON/file utilities. |

Do not start as a Kotlin-hosted DSL. It would dodge parser/tooling work but would fail the central product promise: Groovy-like scripts with first-class typed effects and source-level colorless async.

Do not extend Flix. Flix gives useful lessons for JVM plus effects, but its language identity, functional bias, and existing effect design are not a clean base for a Groovy replacement.

Do not fork Effekt. Effekt is the closest semantic inspiration, but production JVM interop, bytecode, IDE, Gradle, and Java binary compatibility require a different engineering center.

## 2. Prior Art Lessons

| System | Copy | Avoid | Production lesson |
|---|---|---|---|
| Groovy | Scripts, closures, trailing blocks, builders, named args, interpolation, safe navigation, fast glue code feel. | MOP-driven unsafety, surprising dynamic dispatch, late errors, hard-to-optimize dynamism. | Users expect very low ceremony. Static safety must feel helpful, not bureaucratic. |
| Kotlin | Java interop, nullability, data classes, sealed classes, extension functions, pragmatic inference, Gradle/IDE quality. | `suspend` coloring, compiler complexity, some inference edge opacity. | JVM language adoption is tooling-led. Interop friction kills. |
| Scala 3 | Contextual parameters, enums, pattern matching, typeclass expressiveness. | Implicit complexity, compile-time cost, onboarding cliff, HKT-first ecosystem. | Powerful type features must be rationed. |
| Effekt | Lexical handlers, capabilities, direct style, lightweight effect polymorphism. | Research maturity assumptions, non-JVM backend assumptions. | Capabilities are the cleanest way to make effects ergonomic and safe. |
| Koka | Row effects, selective CPS, handler compilation ideas. | Too much inference magic for enterprise diagnostics. | Use rows internally, but present readable effect names and good errors. |
| Flix | JVM backend, type/effect discipline, Java interop focus, purity tracking. | Too functional/academic for Groovy replacement; effect limitations around some polymorphic operations. | Full type/effect checking on JVM is viable but must be ergonomic. |
| Levy/CBPV | Separate values from computations, make sequencing explicit, model thunks/forcing, and give continuations/stacks a clean semantic place. | Exposing academic CBPV syntax to users. | Use CBPV as compiler core discipline, not surface language design. |
| OCaml 5 | One-shot effect continuations, deep/shallow distinction, runtime double-resume detection. | Untyped effects for this product. | One-shot continuations are the sane production default for concurrency and resources. |
| Java | Virtual threads, records, sealed classes, annotations, reflection, classpath, `CompletableFuture`. | Checked exception pain, raw types, erasure limitations. | Java compatibility is not optional; wrap unstable preview APIs. |
| ZIO/Cats Effect/Kyo | Fibers, cancellation, scopes, resources, tracing, production runtime ideas. | Monadic coloring and library-only effect tracking. | Runtime semantics are mature; surface syntax should remain direct style. |

Current facts used:

- OpenJDK JEP 444 delivered virtual threads in Java 21.
- OpenJDK JEP 525 describes structured concurrency as a JDK 26 preview API.
- Kotlin docs/spec describe suspending functions as explicitly marked and compiler-transformed around continuations/state machines.
- OCaml manuals document one-shot/linear continuations and runtime double-resume errors.
- Flix docs describe a direct-style type/effect system on the JVM.
- Effekt docs describe lexical effect handlers and capabilities.
- Levy's call-by-push-value notes motivate the compiler's separation of values, computations, explicit sequencing, thunks, and continuation/stack-sensitive semantics.

## 3. Product Definition

Target users:

- Groovy users, Gradle/build authors, CI/CD engineers.
- JVM backend and enterprise engineers.
- Platform/infra teams writing glue code.
- Java/Kotlin teams needing safer scripts.
- Library and DSL authors.

Primary use cases:

- Script files that call Java libraries.
- Build/config/test DSLs.
- HTTP services and integration workflows.
- Async IO orchestration.
- Resource-safe file and network workflows.
- Migration from Groovy scripts into compiled modules.

Success criteria:

| Metric | MVP target | v1 target |
|---|---:|---:|
| Cold script hello world | under 800 ms with daemon/cache | under 250 ms cached |
| Compile small module | under 2 s | under 1 s incremental |
| Java method call overhead | near Kotlin | near Java/Kotlin |
| Await overhead | within 2-3x Kotlin coroutine MVP | within 1.5x Kotlin coroutine for common paths |
| Handler overhead | measured, acceptable for DSL/control flow | optimized selectively |
| Diagnostics | explain type/effect/capability errors | IDE-quality fix hints |
| Stack traces | source lines through await | async stack trace stitching |
| Interop | common Java libraries usable | Gradle/Maven production quality |

## 4. Language Semantics

Recommended core:

- Values: primitives, strings, records, sealed variants, arrays, collections, functions, classes wrapping Java classes.
- Functions: direct style, first-class, lexical closures, named/default parameters.
- Blocks: expressions by default; last expression returns value.
- Control flow: `if`, `while`, `for`, `match`, `return`, `break`, `continue`.
- Data: `data` records, `sealed` variants, Java-compatible `class` for exported APIs.
- Modules: Java-like packages plus top-level definitions.
- Scripts: top-level statements compiled to a generated class with stable naming.
- Nullability: `T` non-null, `T?` nullable, Java platform type `T!` internally and in diagnostics.
- Generics: invariant by default, declaration-site variance for simple producer/consumer cases, wildcard interop.
- Subtyping: nominal classes/interfaces plus sealed ADTs. Avoid structural object types in v1.
- Inference: local/bidirectional. Public functions require explicit parameter and return types after MVP.
- Errors: checked Java exceptions are represented as `throws E` effect at interop boundary; unchecked exceptions are `Panic`/`Unsafe`.
- Resources: `use`/`defer` built into language/runtime and enforced across cancellation.
- Operators: limited overload through named methods, no global ad hoc implicit conversions.
- Dynamic mode: explicit `dynamic` type and `unsafe.dynamic` interop only, warning by default.

Postpone beyond v1:

- Higher-kinded types.
- Full macro system.
- Unrestricted dynamic metaprogramming.
- Unrestricted multi-shot handlers.
- Global type inference.
- Compile-time reflection-heavy DSL expansion.

## 5. Effect System Design

Use **nominal effects with row-like effect sets**.

Example public type:

```text
fun fetchUser(id: UserId): User / { Async, Http, Throws[HttpError] }
```

Semantics:

- Effects are nominal declarations: `effect Log`, `effect Amb`, `effect Http`.
- Effect rows are unordered sets with optional row variables: `{ Log, e }`.
- Effect aliases improve API readability.
- Primitive effects: `IO`, `Async`, `Resource`, `Throws[E]`, `Unsafe`, `JavaMutation`, `Lock`.
- `Throws[E]` is a built-in parameterized primitive effect in v1. General user-defined parameterized effects are postponed.
- User effects are declared as operation interfaces.
- Capabilities are values introduced lexically by handlers or runtime scopes.
- Performing an operation requires the capability in scope.
- Closures capture capabilities explicitly in their type.
- Capability escape is rejected unless the capability is declared `static`, `shareable`, or region-safe.

Effect/capability distinction:

- An effect in a type says what kind of operation may occur.
- A capability is the lexical evidence that permits performing that operation.
- A handler introduces, transforms, or eliminates capabilities.
- The backend elaborates effect operations into explicit capability/evidence passing.

Handlers are language syntax in v1, not only library functions.

Reason: handlers affect typing, capability scope, continuation lifetime, and async lowering. Library-level encodings may be provided later as ergonomic wrappers over the core syntax, but the core semantics must be compiler-visible.

Effect safety classes:

| Class | Examples | Replayable? | Allowed in async? | Allowed in grad? | Allowed in future multi-shot? |
|---|---|---:|---:|---:|---:|
| Pure | Math, local immutable computation | yes | yes | yes | yes |
| Deterministic effect | Shape, deterministic Random | maybe | yes | maybe | maybe |
| Async | `Async`, `await`, `sleep` | no | yes | no by default | no |
| Resource | `use`, `defer`, `AutoCloseable` | no | yes with cleanup | no by default | no |
| IO | Files, network, console | no | yes | no | no |
| JavaMutation | Unknown Java calls, mutable objects | no | yes with caution | no | no |
| Unsafe | Reflection, dynamic, native | no | explicit only | no | no |

Recommended async modeling: **B/C hybrid**.

`Async` is a built-in effect with compiler-recognized operations, exposed as normal library functions:

```text
effect Async {
  await[A](future: CompletableFuture[A]): A
  sleep(duration: Duration): Unit
  fork[A](body: () -> A / { Async, e }): Task[A] / { Async, e }
}
```

`await` is parsed as a normal-looking function call and resolved as a compiler-known stdlib intrinsic. It should not be a hard reserved keyword unless grammar ambiguity or diagnostics force that decision later.

Why not ordinary handlers only:

- Production async needs special lowering, stack traces, cancellation, `CompletableFuture` bridging, and structured scope integration.
- Treating it as just another handler makes simple programs elegant but runtime behavior too hard to guarantee on the JVM.

Why not virtual threads only:

- Virtual threads solve blocking scalability and interop well, but do not give typed async effects, non-blocking await over callback/future APIs, or resumable user effects.

Effect inference:

- Bidirectional constraint collection over expressions.
- Rows are normalized and simplified.
- Local functions infer effects.
- Public ABI emits effect metadata annotation.
- Recursive functions require declared effects initially.
- If Java call nullability/exception/effect is unknown, infer conservative effects and warn.

Diagnostics:

- Report missing capability, not abstract row unification.
- Show "this block may suspend because `await` is called here".
- Show "capability `File` cannot escape `use file` region".
- Show "public function must declare `{ Async }` because callers need scheduling semantics".

## 6. Continuation Cardinality And Multi-Shot Policy

Recommendation: **Option A for v1: no multi-shot continuations**.

All resumptions are affine one-shot:

- A captured continuation may be resumed at most once.
- It must be resumed or discontinued before handler/scope completion unless explicitly abandoned by a cancellation path.
- Double resume is rejected statically when the resumption is linearly consumed in a simple branch; otherwise runtime throws `ContinuationAlreadyResumed`.
- Resume after handler scope exit throws `ContinuationEscaped`.
- Resumptions may cross threads only if marked `sendable` by the compiler/runtime and only inside an active structured scope.
- Raw continuations are not exposed in v1 user APIs.

Deep handlers are the default in v1. Shallow handlers are postponed; they are powerful for protocols but complicate diagnostics.

Future multi-shot:

- Only selective multi-shot, never unrestricted.
- Only for effects annotated `multi pure`.
- Captured continuation must be effect-pure: no `IO`, `Async`, `Resource`, `Lock`, `JavaMutation`, `Unsafe`, Java calls with unknown mutation, or captured mutable Java objects.
- Explicit annotation required at handler site.
- Runtime may copy/reify continuation only after purity proof. If proof depends on erased Java state, reject.

Rejected in v1:

```text
handle chooseProgram() with Amb.multi { ... } // no multi-shot support in v1
```

Valid one-shot async:

```text
script {
  val a = await(api.user("42"))
  val b = await(api.orders(a.id))
  println("${a.name}: ${b.size}")
}
```

Invalid double resume:

```text
handle work() with effect Ask {
  ask(k) -> {
    resume k with 1
    resume k with 2   // error: continuation already consumed
  }
}
```

Invalid resume after scope:

```text
var saved
handle askName() with effect Ask {
  ask(k) -> saved = k
}
resume saved with "Ada" // error: continuation escaped handler scope
```

Invalid future multi-shot duplicating IO:

```text
multi handle program() with Amb {
  choose(k) -> [resume k true, resume k false]
}

fun program(): Int / { Amb, IO } = {
  println("charged card")
  if choose() then 1 else 2
}
// rejected: multi-shot continuation captures IO
```

Invalid duplicating await:

```text
fun program(): Int / { Amb, Async } = {
  val x = await(remote())
  if choose() then x else x + 1
}
// rejected for future multi-shot: captured continuation includes Async
```

## 7. Async/Await And Color Elimination

Source-level rule:

- Users do not write `async fun`, `suspend fun`, `Future<T>` plumbing, or monadic `IO<T>` in business logic.
- The compiler tracks `Async` in the effect row.
- API authors may expose the effect row; ordinary call chains simply compose.

Colorless async is a source-language property, not an ABI illusion. Inside the language, async is tracked by effects. At Java boundaries, async must be made explicit as `CompletableFuture`, `Task`, callback, or blocking wrapper.

Typechecking:

```text
await(expr: CompletableFuture[A]) : A / { Async }
await(expr: Task[A])              : A / { Async }
```

A function containing `await` has effect `{ Async }` unless handled by an async runtime boundary.

Lowering:

- Functions with no suspension/effect handling compile as ordinary JVM methods.
- Functions with `Async` suspension lower to a state-machine class or method with hidden continuation parameter.
- The source signature remains direct in language metadata; Java-facing wrappers expose `Task<T>` or `CompletableFuture<T>`.
- The compiler performs selective CPS only for functions on paths that may suspend.
- User-defined effects that may suspend use the same internal continuation protocol but not the same public API.

Recommended execution architecture:

| Workload | Execution |
|---|---|
| Pure sync code | Plain JVM bytecode. |
| Blocking Java call | Virtual thread bridge by default if inside `Async`. |
| `CompletableFuture` await | Register callback, suspend one-shot continuation, resume in scheduler/scope. |
| Callback API | Adapter creates `CompletableFuture` or `Task`. |
| Structured fork/join | Runtime `Scope` with cancellation token and child task registry. |
| CPU parallelism | Configured executor or virtual threads, explicit. |

Cancellation:

- Every async scope has a cancellation token.
- `await` observes cancellation before parking and on callback.
- `fork` registers child in current scope.
- Leaving a scope cancels unfinished children and awaits cleanup.
- `defer`/`use` run on cancellation through `discontinue` semantics.
- Java blocking calls are interrupted when possible; if not interruptible, diagnostics can mark them as "cancellation delayed".

Errors:

- Child failure cancels siblings by default in `parallel`.
- `race` cancels losers.
- Java exceptions map into `Throws[E]` or unchecked `Panic`.
- Async runtime preserves source stack frames through debug metadata and optional async stack stitching.

Public API:

```text
export fun load(id: String): User / { Async, Throws[IOException] }
```

Java ABI modes:

| Mode | Java surface | Use case |
|---|---|---|
| `export async` | `CompletableFuture<T>` or `Task<T>` | Non-blocking Java callers. |
| `export blocking` | `T throws E` | Java frameworks expecting synchronous methods. |
| `export dx-only` | JVM descriptor plus dx effect metadata | dx-to-dx APIs where Java source compatibility is not the goal. |

For example, Java sees one of:

```java
CompletableFuture<User> loadAsync(String id);
User loadBlocking(String id) throws IOException; // generated only if requested
```

## 8. JVM Backend Architecture

JVM baseline: **Java 21**.

Justification:

- Virtual threads are final in Java 21.
- Records, sealed classes, pattern matching support, and modern bytecode tooling are available.
- Enterprises can adopt Java 21 LTS.
- Do not require preview structured concurrency in the language ABI.

Backend strategy:

- Use ASM directly for bytecode.
- Maintain a JVM IR to avoid emitting from typed AST.
- Generate line number tables, local variable tables, source debug extension metadata, and effect metadata annotations.
- Use `invokedynamic` only after MVP for lambda/metafactory and dynamic call sites.
- Scripts compile to generated classes under `__script.<hash>.<Name>`.
- REPL/script runner uses classloader caching plus compiler daemon.

Representations:

| Feature | Representation |
|---|---|
| Top-level functions | Static methods on module class. |
| Closures | JVM classes or `invokedynamic` lambdas when non-suspend. |
| Suspend closures | State-machine object implementing runtime `ContinuationFunction`. |
| Capabilities | Final lexical objects/parameters, region tagged in compiler metadata. |
| Handlers | Runtime handler frames plus optimized direct calls for known handlers. |
| One-shot resumption | Object with atomic state: fresh/resumed/discontinued/closed. |
| Generics | Erased JVM generics first; specialize primitives later. |
| Data records | Java records where ABI-stable, otherwise final classes. |
| Sealed variants | Sealed interface plus final implementation classes. |

Tail calls:

- Self-tail recursion to loop where possible.
- General TCO not promised on JVM.

Exception strategy:

- Use JVM exceptions for Java interop and fatal errors.
- User `Throws[E]` can lower to exceptions at Java boundary, but internally can use structured result paths where profitable.
- `defer`/`use` must compile through `try/finally` or runtime finalization on discontinuation.

## 9. Java Interop

Calling Java:

- Import Java packages directly.
- Overload resolution follows Java applicability plus language conversions.
- Constructors use `Type(...)`.
- Static methods/fields through type name.
- JavaBeans properties are sugar over getters/setters when unambiguous.
- SAM conversion for lambdas.
- Varargs map from spread/list syntax.
- Arrays and primitive arrays are first-class interop types.
- Records expose constructor and component properties.
- Sealed Java classes participate in pattern matching with exhaustiveness when visible.
- Annotations can be used and generated.

Nullability:

- Recognized annotations: JSpecify, JetBrains, Checker Framework, AndroidX, Jakarta where practical.
- Unknown Java reference type is platform `T!`.
- Assigning `T!` to non-null `T` inserts a runtime null check unless suppressed.
- `T?` requires safe navigation or explicit check.

Exceptions:

- Checked Java exceptions become `Throws[E]` effects.
- Unchecked Java exceptions become `Panic` unless declared by wrapper.
- `throws` declarations can be emitted for Java-facing APIs.

Java mutation:

- Unknown Java calls are conservatively `{ IO, JavaMutation }` unless classified pure through annotations or built-in whitelist.
- For MVP, unknown Java calls inside top-level scripts are allowed with warnings.
- Inside `grad`, future `multi`, and `pure` declarations, unknown Java calls are rejected unless annotated/imported as pure.
- Pure Java methods can be annotated/imported as pure.

Reverse interop:

- `export` controls Java ABI.
- Exported async functions generate `CompletableFuture<T>` and optional blocking wrappers.
- Exported effectful functions require handlers/capabilities supplied explicitly or are not exportable.
- Generated names are stable and annotated with source metadata.
- Binary compatibility follows JVM descriptors plus effect metadata; changing effects is source/binary compatibility relevant for this language, but Java binary still links.

Kotlin/Scala libraries:

- Kotlin nullability metadata should be read.
- Kotlin suspend functions can be called through adapter only after MVP.
- Scala libraries are Java bytecode from this language's perspective; no attempt to understand Scala type-level encodings in v1.

## 10. Groovy-Compatible Ergonomics

Copy:

- `.dx` script files.
- Top-level statements.
- Optional semicolons.
- Closures with trailing block syntax.
- Named args and map/list literals.
- String interpolation.
- Safe navigation `?.`, Elvis `?:`.
- Builder syntax for DSLs.
- Regex literal if it proves worth the parser complexity.

Avoid:

- Global MOP.
- Unchecked dynamic method missing/property missing by default.
- Runtime-only builder errors for statically declared DSLs.
- Silent truthiness for arbitrary objects in v1; keep boolean contexts explicit except common nullable/collection checks if well-specified.

Dynamic escape hatch:

```text
val x: dynamic = javaObject
x.someRuntimeMethod(1, "a") // warning unless in unsafe block
```

DSL blocks are typed:

```text
build {
  plugin("java")
  dependencies {
    implementation("org.slf4j:slf4j-api:2.0.13")
  }
}
```

The block has a receiver capability, not dynamic `delegate` mutation.

## 11. Type System

Recommendation: bidirectional, constraint-based local inference with nominal subtyping.

Features:

- Primitive and reference types.
- Non-null by default.
- `Nothing` bottom, `Any` top, `Any?` nullable top.
- `dynamic` explicit escape hatch.
- Generics with simple bounds.
- Declaration-site variance: `out T`, `in T`.
- Union types only for nullability and sealed match narrowing in v1; no general public union types.
- Intersection types internal for Java bounds and smart casts, not user-facing initially.
- Contextual capabilities, not general implicits.
- No HKT in v1.
- No typeclasses in v1; extension methods and interfaces cover most product needs.

Why:

- Java interop needs subtyping and overload resolution.
- Effects need predictable public signatures.
- DSL ergonomics need receiver blocks and named/default args more than HKT.
- Compile times and diagnostics matter more than maximal abstraction.

## 12. Compiler Pipeline

Text diagram:

```text
Source
  -> Lexer
  -> Parser
  -> CST
  -> AST
  -> Name resolution/imports/modules
  -> Java symbol resolution/classpath model
  -> Type/effect/capability checking
  -> Typed AST
  -> Desugaring
  -> CBPV Core IR
  -> Effect/capability elaboration
  -> Handler elaboration
  -> Async analysis
  -> Selective CPS/state-machine lowering
  -> Closure conversion
  -> JVM IR
  -> Bytecode generation
  -> Verification/debug metadata
  -> Class/JAR/script cache
```

Stage responsibilities:

| Stage | Input | Output | Key invariant |
|---|---|---|---|
| Parser | source | CST/AST | Lossless enough for formatter/LSP. |
| Resolver | AST + classpath | resolved AST | Every name has a symbol or diagnostic. |
| Type/effect checker | resolved AST | typed AST | Every expression has type/effect/captures. |
| Desugar | typed AST | CBPV core IR | Surface sugar removed, source spans retained; value/computation boundary explicit. |
| Effect elaboration | CBPV core IR | capability IR | Operation calls have handler/capability evidence. |
| Async lowering | capability IR | lowered IR | Suspension points explicit, only affected funcs transformed. |
| Closure conversion | lowered IR | JVM IR | Captures explicit and representable. |
| Bytecode | JVM IR | `.class` | Verifier passes; debug metadata maps to source. |

Testing:

- Golden parser trees.
- Type/effect negative tests.
- Java interop fixture jars.
- Bytecode verifier tests.
- Runtime async cancellation tests.
- Diagnostic snapshot tests.
- IDE incremental parse/type tests.

## 13. IR Design

The compiler core should follow Levy's call-by-push-value discipline: values and computations are distinct, effectful sequencing is explicit, thunks are explicit, and continuation/stack-sensitive lowering happens from a representation that already names computations rather than pretending all expressions are pure values.

This is an internal compiler architecture decision, not a surface syntax decision. dx users still write direct-style code; the compiler elaborates it into CBPV-shaped IR before effect and async lowering.

CBPV core sketch:

```text
Value types A ::=
  Unit | Bool | Int | String | C<A*> | A? | A * A | A + A
  | U C                         // thunked computation
  | Cap[E] | Task[A]

Computation types C ::=
  F A ! Eff                     // computation returning a value
  | A -> C                      // function from value to computation
  | C & C                       // computation product / handler table shape

Effects Eff ::=
  {} | { e1, e2, r } | Async | IO | Resource | Throws[A] | UserEffect

Values V ::=
  x | literal | data(V*) | inl V | inr V | thunk M | cap

Computations M ::=
  return V
  bind M as x in N              // explicit sequencing
  force V
  lambda x. M
  M V
  match V { p -> M* }
  perform V.op(V*)              // capability operation
  handle M with H
  await V
  use x = M in N
```

Judgment sketch:

```text
Gamma; Caps |-v V : A
Gamma; Caps |-c M : F A ! Eff
Gamma; Caps, c:Cap[E@r] |-c perform c.op(args) : F R ! { E }
Gamma; Caps |-c handle M with H : F A ! (Eff - E + Eff_H)
Gamma; Caps |-c await future : F A ! { Async }
```

Compiler invariants:

- Every effectful operation appears in a computation, never as a raw value.
- Evaluation order is represented by `bind`, not recovered later from source syntax.
- Closure conversion knows whether a closure captures values, thunks, capabilities, or suspended computations.
- Handler lowering receives an explicit computation boundary where one-shot resumptions can be allocated.
- Async lowering sees suspension points as computations and can generate state labels from `bind` structure.
- Pure optimizations may rewrite values freely, but computation rewrites must preserve effect order.

Surface-to-CBPV example:

```text
// source
val body = await(fetch()).trim()
println(body)

// CBPV-shaped core
bind await fetchFuture as tmp in
bind return tmp.trim() as body in
perform Console.println(body)
```

Before async lowering:

```text
fun load(): User / { Async, Http } = {
  val id = await(fetchId())
  await(fetchUser(id))
}
```

State-machine shape:

```text
class LoadSM : Continuation<Object> {
  int label
  Object id

  Object resume(Object result, Throwable error) {
    switch(label) {
      case 0:
        label = 1
        return Runtime.await(fetchId(), this)
      case 1:
        id = result
        label = 2
        return Runtime.await(fetchUser((String) id), this)
      case 2:
        return result
    }
  }
}
```

Canonical internal form:

- Use an explicit continuation IR after effect elaboration.
- Generate state machines from continuation IR.
- Keep high-level source spans attached to continuation states.

## 14. Runtime Design

Runtime architecture diagram:

```text
Program
  -> Runtime.run
      -> RootScope
          -> Scheduler
              -> JVM executor / virtual thread bridge
          -> CancellationToken tree
          -> Task registry
          -> Handler stack
          -> Resource stack
          -> Async stack trace recorder
```

Runtime components:

- `Task<T>`: language task abstraction, Java-compatible.
- `Scope`: structured child task owner.
- `CancellationToken`: hierarchical cancellation.
- `Resumption<T>`: one-shot continuation wrapper.
- `HandlerFrame`: lexical handler runtime representation.
- `ResourceFrame`: `defer`/`use` cleanup.
- `AwaitBridge`: `CompletableFuture`, callback, virtual thread blocking bridge.
- `DebugProbe`: async stack traces, metrics, tracing hooks.
- `Context`: scoped values and selected ThreadLocal propagation.

Scheduler decision:

- MVP uses a small runtime over Java executors and virtual threads.
- Do not build a full ZIO/Cats runtime initially.
- Expose `Task` publicly, but keep scheduler APIs minimal.
- Non-async scripts should not require async runtime initialization.

ThreadLocal:

- Default: do not blindly copy all ThreadLocals.
- Provide explicit context propagation.
- Java framework adapters can bridge common contexts.

Fatal error policy:

- Fatal JVM errors are not caught.
- Cancellation is cooperative but cleanup is mandatory.
- Double resume, leaked continuation, and structure violation are runtime errors with source spans.

## 15. Standard Library Scope

In v1:

- Core collections wrappers and Java collection interop.
- `Option`, `Result`, `Either`.
- Strings, regex wrapper if syntax is included.
- Files/path helpers over `java.nio.file`.
- Resource management: `use`, `defer`, `bracket`.
- Async: `Task`, `Scope`, `await`, `sleep`, `timeout`, `race`, `parallel`.
- JSON minimal wrapper over Jackson or kotlinx.serialization equivalent decision.
- Testing DSL.
- Logging facade.
- Effect utilities for handlers/capabilities.
- HTTP client convenience over Java `HttpClient`.

External:

- YAML/TOML beyond simple config.
- Full serialization framework.
- Database clients.
- Web framework.
- Build system implementation.
- Large immutable collections library unless adopted from Java-compatible dependency.

## 16. Differentiable Programming And Automatic Differentiation

The language should keep Automatic Differentiation as a strategic post-MVP capability.

Typed effects and lexical capabilities make AD a strong fit because differentiation requires controlled interpretation of numeric/tensor operations, tracing, tape management, randomness, resource safety, and purity boundaries.

Design direction:

- AD should not be implemented as global runtime magic.
- AD should be modeled through typed effects/capabilities and handlers.
- Scalar forward-mode AD can be an early library/runtime experiment.
- Reverse-mode AD should use a tape/graph representation, not unrestricted continuations.
- Tensor AD requires a dedicated Tensor IR before claiming production-grade ML performance.
- Differentiable regions should reject unsafe effects by default.

Example policy:

```text
grad { ... } accepts:
  Pure
  TensorOps
  Diff
  Random if deterministic/seeded
  Shape

grad { ... } rejects by default:
  IO
  Async
  Resource
  Lock
  JavaMutation
  Unsafe
  unknown Java calls
```

Example:

```text
fun loss(x: Tensor, y: Tensor): Tensor / { TensorOps, Diff } =
  mean(square(model(x) - y))

val g = grad(loss)
```

Async should stay outside differentiable regions in v1:

```text
val batch = await(loadBatch())
val gradients = grad {
  loss(model(batch.x), batch.y)
}
```

Rejected in v1:

```text
grad {
  val batch = await(loadBatch())
  loss(model(batch.x), batch.y)
}
```

Rationale:

- Async suspension is one-shot and scope/cancellation-sensitive.
- Reverse-mode AD may replay, trace, checkpoint, or reorder pure computations.
- Mixing async/resource effects inside differentiable regions complicates cancellation, tape validity, and cleanup semantics.

Possible AD roadmap:

| Stage | Goal |
|---|---|
| AD-0 | Scalar forward-mode AD library experiment. |
| AD-1 | Scalar reverse-mode with tape. |
| AD-2 | Tensor type and `TensorOps` effect. |
| AD-3 | Reverse-mode tensor AD with `grad` and `valueAndGrad`. |
| AD-4 | `noGrad`, `detach`, gradient checking, diagnostics. |
| AD-5 | Tensor IR, shape inference, graph tracing. |
| AD-6 | Fusion, memory planning, Vector API/BLAS backend. |
| AD-7 | Optional StableHLO/ONNX/GPU backend exploration. |

Architecture validation spike:

| Spike | Purpose | Scope | Success | Failure |
|---|---|---|---|---|
| Scalar AD spike | Test effects as interpretation mechanism. | Forward-mode `Dual` and reverse-mode scalar tape over pure math subset. | `grad(x -> x * x + sin(x))` works. | Effects are too heavy for transformation-style APIs. |

Non-goals for v1:

- GPU training framework.
- XLA/JAX-level compiler.
- Distributed training.
- Multi-shot continuation-based AD.
- AD through arbitrary Java mutation.

## 17. Tooling Plan

| Tool | MVP | Production |
|---|---|---|
| CLI | `run`, `compile`, `repl`, `test` basics. | Daemon, cache, diagnostics explanations, profiling. |
| REPL | Line/block eval with classloader cache. | IDE-backed completions and history. |
| Formatter | Stable parse/pretty for subset. | Full style config, LSP integration. |
| Linter | Unused imports, dynamic/unsafe warnings. | Effect/cancellation/resource linting. |
| LSP | Diagnostics, go-to symbol for local code. | Completion, rename, refactor, Java classpath intelligence. |
| IntelliJ | Defer to LSP initially. | Native plugin if adoption warrants. |
| Gradle plugin | Compile source set, run scripts. | Incremental compile, build cache, DSL support. |
| Maven plugin | Compile/test lifecycle. | Multi-module and annotation metadata. |
| Test runner | Golden/runtime tests. | JUnit integration and coverage. |
| Docs | Markdown site. | API docs, examples, migration guide. |

Priority:

1. CLI and diagnostics.
2. Formatter.
3. LSP parse/type diagnostics.
4. Gradle plugin.
5. REPL.
6. Maven plugin.
7. IDE refinements.

## 18. Build And Ecosystem Integration

Repository layout:

```text
/
  compiler/
    parser/
    ast/
    resolve/
    typecheck/
    effects/
    ir/
    backend-jvm/
    diagnostics/
  runtime/
  stdlib/
  cli/
  spec/
    syntax.md
    type-system.md
    effects.md
    cbpv-core.md
    async.md
    java-interop.md
    diagnostics.md
  tools/
    formatter/
    lsp/
    gradle-plugin/
    maven-plugin/
  tests/
    parser/
    typecheck/
    effects/
    interop/
    bytecode/
    runtime/
    golden/
  examples/
  benchmarks/
  docs/
```

Implementation language: **Kotlin**.

Why:

- Strong JVM interop and concise compiler implementation.
- Good Gradle ecosystem.
- Familiar to target contributors.
- Easier than Scala for keeping compile times and type-system complexity down.
- Java can be used for runtime hot paths if profiling justifies it.

Parser:

- Start with ANTLR for speed of iteration or hand-written parser if DSL syntax gets ambiguous.
- Long term, hand-written parser may improve diagnostics and formatter/LSP integration.

CI:

- JDK 21 baseline; test on JDK 21, 25, 26.
- Bytecode verification.
- Windows/macOS/Linux.
- Golden diagnostics.
- JMH benchmarks.
- Dependency vulnerability checks.

Versioning:

- `0.x` unstable.
- Stabilize source and ABI before `1.0`.
- Effect signature changes are breaking for language callers.

## 19. Milestone Roadmap

| Phase | Goals | Deliverables | Risks | Exit criteria | Complexity |
|---|---|---|---|---|---|
| -1 Executable spec | Validate semantics before backend. | CBPV Core interpreter, effect/handler semantics tests, one-shot resumption tests. | Overfitting interpreter semantics. | 20 semantic tests pass before bytecode semantics depend on them. | M |
| 0 Research/spike | Validate hard bets. | Spike reports, decision records. | Wrong lowering choice. | Async + handler toy works. | M |
| 1 Parser/AST/type checker | Basic syntax and types. | Parser, AST, local inference. | DSL ambiguity. | Golden parse/type tests pass. | M |
| 2 JVM simple programs | Bytecode for sync code. | ASM backend, verifier tests. | Debug metadata. | Hello/data/functions run. | M |
| 3 Java interop MVP | Call Java libs. | Classpath model, overloads subset. | Edge cases. | HTTP/file Java examples pass. | H |
| 4 Script mode | Groovy-like scripts. | Runner, generated classes, cache. | Startup. | Script calls Java quickly. | M |
| 5 Effects MVP | Effect rows and operations. | Effect checker, declarations. | Diagnostics. | Missing handler errors good. | H |
| 6 Handlers/capabilities | Lexical handlers. | One-shot handler interpreter/lowering. | Escape safety. | User handler demo passes. | H |
| 7 Async lowering MVP | Await over CF. | State-machine lowering. | Stack traces. | Direct-style await demo. | H |
| 8 Structured runtime | Scopes/cancel/resources. | Runtime scope API. | Leaks. | Parallel/cancel cleanup tests. | H |
| 9 Gradle/Maven | Build integration. | Plugins MVP. | Incrementality. | Multi-module sample builds. | M |
| 10 LSP/formatter | Developer loop. | LSP diagnostics, formatter. | AST fidelity. | Editor diagnostics demo. | M |
| 11 Stdlib stabilization | Usable libraries. | Core stdlib. | Scope creep. | Examples stop using internals. | M |
| 12 Hardening | Perf, debug, compat. | Benchmarks, docs, compat suite. | Slow runtime. | Beta criteria met. | H |
| 13 v1 | Stable release. | Spec, tools, artifacts. | Adoption/support. | Production readiness checklist. | H |

## 20. MVP Definition

MVP must demonstrate:

- Script calls Java libraries.
- Statically typed function.
- DSL block.
- User effect declaration and lexical handler.
- Direct-style `await` over two `CompletableFuture`s of different types.
- Structured concurrency with two parallel tasks.
- Cancellation and resource cleanup.
- Generated JVM bytecode.
- CLI execution.
- Basic diagnostics.
- Negative continuation test.

MVP source examples:

```text
// examples/mvp/http_script.dx
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.URI

val client = HttpClient.newHttpClient()
val req = HttpRequest.newBuilder(URI("https://example.com")).GET().build()
val body = await(client.sendAsync(req, BodyHandlers.ofString())).body()
println(body.length)
```

File extension: `.dx`.

```text
fun add(a: Int, b: Int): Int = a + b
println(add(2, 40))
```

```text
build {
  plugin("java")
  dependencies {
    implementation("org.slf4j:slf4j-api:2.0.13")
  }
}
```

```text
effect AskName {
  askName(): String
}

fun greet(): String / { AskName } =
  "Hello, ${askName()}"

val msg = handle greet() with AskName {
  askName(k) -> resume k with "Ada"
}
println(msg)
```

```text
fun workflow(): String / { Async, Resource } = scope {
  use file = openTempFile()
  val user = async { await(fetchUser()) }
  val count = async { await(fetchCount()) }
  "${await(user).name}: ${await(count)}"
}
```

Negative:

```text
handle greet() with AskName {
  askName(k) -> {
    resume k with "Ada"
    resume k with "Grace"
  }
}
// expected: continuation `k` already resumed
```

## 21. Tentative Syntax Sketch

```text
println("hello")
```

```text
import java.time.LocalDate
val today = LocalDate.now()
println(today.year)
```

```text
val name: String? = System.getProperty("user.name")
println(name?.uppercase() ?: "unknown")
```

```text
fun twice(x) = x * 2
```

```text
data User(id: String, name: String)
```

```text
sealed Shape
data Circle(radius: Double) : Shape
data Rect(w: Double, h: Double) : Shape

fun area(s: Shape): Double = match s {
  Circle(r) -> Math.PI * r * r
  Rect(w, h) -> w * h
}
```

```text
json {
  "name" to "Ada"
  "roles" to array("admin", "builder")
}
```

```text
effect Log {
  info(message: String): Unit
}

fun runJob(): Unit / { Log } =
  info("started")

handle runJob() with Log {
  info(message, k) -> {
    println("[info] $message")
    resume k with Unit
  }
}
```

```text
fun save(user: User)(using db: Db): Unit / { IO } =
  db.insert(user)
```

```text
val user = await(fetchUser("42"))
```

```text
scope {
  val a = async { await(fetchA()) }
  val b = async { await(fetchB()) }
  await(a) + await(b)
}
```

```text
use file = File.open("data.txt")
println(file.readText())
```

```text
try {
  risky()
} catch e: IOException {
  println(e.message)
}
```

```text
export fun greet(name: String): String = "Hello, $name"
```

Future pure multi-shot, not v1:

```text
multi effect Amb {
  choose(): Bool
  fail(): Nothing
}
```

## 22. Hard Technical Questions: Answers

| Question | Answer |
|---|---|
| Should handlers be one-shot or multi-shot? | One-shot in v1. |
| Should async continuations be one-shot only? | Yes, always. |
| Can user handlers capture continuations? | Internally yes; surface exposes `resume`, not raw continuation objects. |
| Can continuations escape handler scope? | No in v1. Runtime detects if static check misses. |
| How do lexical capabilities prevent unsafe escape? | Capabilities carry region identity; closure capture types include regions; escaping region-bound caps is rejected. |
| Should async use same continuation mechanism as general effects? | Same internal protocol, special lowering/runtime for production performance. |
| Should async be special-cased? | Yes. |
| Expose raw continuations? | No. |
| Row or set polymorphic effects? | Row-like internally; set-like syntax for users. |
| Are effects binary signatures? | Yes in metadata for language callers; Java descriptor unchanged. |
| How does Java call effectful code? | Via generated wrappers requiring capabilities or returning `CompletableFuture`/blocking wrapper. |
| Checked exceptions? | `Throws[E]` effect at language boundary; emitted `throws` where exporting. |
| Blocking calls identified? | Annotations, known JDK model, explicit `blocking {}` wrapper. Unknown Java IO is conservative. |
| Are Java methods `IO` by default? | Unknown calls are conservative unless classified pure. |
| Reflection? | `unsafe.reflect`, effect `{ Unsafe, JavaMutation }` unless read-only proven. |
| Debugging after lowering? | Source spans per state, line tables, async stack stitching, debug probes. |
| Cancellation/finally? | Cancellation resumes/discontinues through cleanup stack; `defer` and `use` are mandatory. |
| Can handlers implement backtracking? | Not in v1; future pure multi-shot only. |
| Can user-defined effects suspend? | Yes, if handler operation captures/resumes continuation; still one-shot. |
| Are all handlers same mechanism? | Semantically yes; backend optimizes known non-suspending handlers. |
| Multi-shot requires purity? | Yes, future only. |
| Continuation with Java mutable state multi-resume? | No. |
| Continuation with `AutoCloseable` multi-resume? | No. |
| Partial IO multi-resume? | No. |
| Resume from another Java thread? | Only runtime-mediated, scope-active, sendable one-shot resumptions. |
| Resume after scope exit? | No. |
| Double resume detection? | Atomic state in resumption plus static affine checks. |
| Leaked continuations? | Region tracking plus runtime scope close checks. |
| Multi-shot and locks/ThreadLocal? | Future multi-shot forbids `Lock`, synchronized capture, unknown ThreadLocal state. |
| Is colorless async v1? | Yes. Central requirement. |

## 23. Production Readiness Criteria

| Level | Criteria |
|---|---|
| Research prototype | Parser, interpreter, toy effects, one Java call, docs honest about unsoundness. |
| Alpha | Bytecode backend for subset, effect checker, async await MVP, CLI, golden tests. |
| Beta | Gradle plugin, LSP diagnostics, structured runtime, interop suite, cancellation/resource tests, benchmarks. |
| Production v1 | Stable source subset, binary metadata policy, docs, migration guide, support matrix, useful stack traces. |
| Enterprise-ready | Incremental compilation, long-term compatibility, security review, IDE quality, wide framework interop, performance SLAs. |

Supported JDKs:

- MVP: JDK 21.
- Beta/v1: JDK 21 LTS plus newer LTS/non-LTS validation.
- Preview Java APIs are optional runtime adapters only.

## 24. Benchmarks

Compare against Java, Kotlin, Groovy, Scala, Kotlin coroutines, ZIO, Cats Effect, Kyo, Flix where feasible, Effekt where feasible.

Benchmarks:

- Cold and warm script startup.
- Compile time for small/medium modules.
- Java interop call overhead.
- Closure allocation and invocation.
- Data class allocation/access.
- Async await over already-complete and delayed `CompletableFuture`.
- Structured fork/join overhead.
- Cancellation propagation.
- Resource cleanup under cancellation.
- Handler operation overhead.
- DSL builder execution.
- Memory use per suspended task.
- Stack trace readability scored by fixture.

Use JMH for runtime and custom harness for compiler/startup.

## 25. Risk Register

| Risk | Prob. | Impact | Mitigation | Early validation | Kill criteria |
|---|---:|---:|---|---|---|
| Effect system too complex | H | H | Restrict v1, good diagnostics, explicit API effects. | Typecheck 30 examples. | Users need annotations everywhere. |
| Async lowering too hard | H | H | Start with `CompletableFuture` state-machine spike. | Day 30 await demo. | Cannot debug or handle cancellation. |
| JVM backend slow | M | H | ASM, verifier tests, benchmark from start. | Compare with Kotlin/Java microbenchmarks. | 10x overhead on simple calls. |
| Tooling delayed | H | H | Parser/diagnostics built for LSP early. | LSP by phase 10. | No editor diagnostics by beta. |
| Java interop edge explosion | H | H | Limit MVP, build fixture jars. | Overload/nullability suite. | Common Java libs unusable. |
| Syntax too magical | M | M | Keep typed DSL receivers, avoid MOP. | User tests with Groovy users. | Static errors feel arbitrary. |
| Compile times high | M | H | Local inference, no HKT/macros. | Compiler benchmark. | Small module > Kotlin by large margin. |
| Debugging poor | H | H | Source spans, async stacks, debug probes. | Stack trace benchmark. | Await traces unusable. |
| Runtime uncompetitive | M | H | Use virtual threads where fit, avoid full custom runtime. | Await/fork JMH. | >5x Kotlin/ZIO for core paths. |
| Kotlin good enough | H | H | Focus on Groovy scripts + typed effects + colorless async. | Product interviews. | Users prefer Kotlin scripts. |
| Groovy users reject static constraints | M | H | Low ceremony, dynamic escape hatch, great errors. | Migration examples. | Scripts become verbose. |
| Enterprises distrust effects | M | M | Frame as checked capabilities/resources, not theory. | Docs and examples. | Teams disable effects. |
| Binary compatibility | M | H | Metadata versioning, ABI rules. | Compatibility tests. | Minor effect changes break too much. |
| Multi-shot unsafe | H | H | No v1 multi-shot. | Optional pure spike. | Any impurity duplication possible. |
| Cancellation/resource unsound | M | H | Structured scopes, cleanup tests. | Leak/cancel stress suite. | Resources leak under cancellation. |
| Stack traces unusable | H | H | Invest early. | Debug fixture. | Production errors cannot be traced. |

## 26. Recommended First Spikes

| Spike | Purpose | Scope | Success | Failure |
|---|---|---|---|---|
| Executable semantics | Validate typing/effects before backend. | Tiny interpreter for CBPV Core with effects, handlers, one-shot resumptions. | 20 semantic tests pass. | Backend bugs hide semantic bugs. |
| Bytecode hello world | Validate ASM/JDK 21 pipeline. | Function, println, line numbers. | Runs and verifier passes. | Debug lines wrong. |
| Java interop call | Validate classpath/overload/nullability model. | `HttpClient`, `LocalDate`, overload fixture. | Correct calls and errors. | Overload design too complex. |
| Script runner cache | Validate Groovy-like loop. | Compile/run script, classloader cache. | Re-run is fast. | Startup unacceptable. |
| Minimal type checker | Validate inference boundaries. | Literals, funcs, data. | Good errors. | Annotation burden too high. |
| Minimal effect checker | Validate rows/capabilities. | `Log`, missing handler. | Clear diagnostics. | Row errors unreadable. |
| Lexical handler interpreter | Validate semantics before bytecode. | One-shot resume. | Handler examples pass. | Escape/double-resume unclear. |
| Await state machine | Validate colorless async. | `CompletableFuture` await. | Direct source, state machine works. | Stack/cancel impossible. |
| Structured scope | Validate cancellation model. | Two tasks, fail one, cleanup. | Sibling cancelled, resource closed. | Cleanup leaks. |
| DSL parser | Validate Groovy-like syntax. | Trailing blocks, named args. | Ambiguities manageable. | Parser conflicts severe. |
| LSP diagnostics prototype | Validate tooling architecture. | Parse/type errors in editor. | Incremental enough. | AST not reusable. |

## 27. 90-Day Execution Plan

Assume 1-2 engineers.

Week plan:

| Week | Work |
|---:|---|
| 1 | Decision records, repo scaffold, spec skeleton, JDK/Kotlin/Gradle setup, CBPV executable semantics skeleton. |
| 2 | Parser subset: literals, vals, funcs, calls, blocks, imports. Golden tests. |
| 3 | Resolver, basic type checker, and executable semantics tests for CBPV `return`/`bind`/`force`. |
| 4 | ASM hello world and bytecode for sync subset. Day 30 demo: script calls Java, typed function, simple DSL parse. |
| 5 | Data records, closures, trailing blocks, named args. Diagnostics harness. |
| 6 | Effect declarations, effect rows, missing handler diagnostics. Interpreter for handlers. |
| 7 | Capability regions and one-shot resumption runtime check. Negative tests. |
| 8 | Day 60 demo: user effect handler, Java call, script runner, bytecode for sync subset. |
| 9 | Async IR and `CompletableFuture` await state machine. |
| 10 | Runtime `Task`, `Scope`, cancellation token, `async`, `await`. |
| 11 | Resource cleanup across cancellation, `use`/`defer`. |
| 12 | Formatter MVP, CLI polish, benchmark harness, docs. |
| 13 | Day 90 demo: full MVP workflow, structured concurrency, cancellation, negative continuation test. |

Initial grammar subset:

- Imports, val/var, fun, block, if, match minimal, calls, property access, lambdas, trailing blocks, data, effect, handle, await, scope, use.

Initial type subset:

- `Unit`, `Bool`, `Int`, `Long`, `Double`, `String`, nullable, function types, data classes, Java reference types, simple generics.

Initial effect subset:

- `{}`, `{ Async }`, `{ IO }`, `{ Throws[E] }`, one user effect, row variables for local inference only.

Initial IR:

- Typed expression tree with explicit symbols and effect rows.
- CBPV core IR with `Value`, `Computation`, `Return`, `Bind`, `Thunk`, `Force`, `Perform`, `Handle`, `Await`.
- Lowered continuation IR after week 9.

Initial runtime API:

```kotlin
interface Task<T>
class Scope
class CancellationToken
class Resumption<T>
fun <T> run(block: suspend-runtime-shape): T
fun <T> await(cf: CompletableFuture<T>, k: Resumption<T>)
fun <T> scope(body: Scope.() -> T): T
```

Initial bytecode/interpreter strategy:

- Interpret handlers first.
- Compile sync code to bytecode early.
- Compile async state machines for a narrow subset by day 90.

Tests first:

- Parser golden for syntax examples.
- Type errors for null, overload, missing return.
- Effect errors for missing handler and public effect.
- Runtime double resume.
- Await over complete and delayed future.
- Cancellation cleanup.

Day 30 demo:

```text
import java.time.LocalDate
fun year(d: LocalDate): Int = d.year
println(year(LocalDate.now()))
```

Day 60 demo:

```text
effect Log { info(s: String): Unit }
fun job(): Unit / { Log } = info("ok")
handle job() with Log { info(s, k) -> { println(s); resume k with Unit } }
```

Day 90 demo:

```text
scope {
  use file = tempFile()
  val user = async { await(fetchUser()) }
  val count = async { await(fetchCount()) }
  println("${await(user).name}: ${await(count)}")
}
```

Scope cuts:

- No multi-shot.
- No full Groovy compatibility.
- No Kotlin suspend interop.
- No full Java overload parity.
- No IDE plugin beyond LSP diagnostics.
- No macros.
- No HKT/typeclasses.

Immediate decisions:

- Package syntax.
- Parser technology.
- Exact effect syntax.
- Java 21 baseline.
- Runtime wrapper API for structured concurrency.

Postpone:

- Multi-shot design.
- Primitive specialization.
- `invokedynamic`.
- Native image.
- Full Gradle build DSL replacement.
- Typeclasses/contextual abstraction beyond capabilities.

## 28. Brutal Critique

Kotlin compiler reviewer:

- Colorless async is not free. Kotlin uses `suspend` because the compiler and callers need to know where state machines are required. Hiding color at syntax level means effect metadata and recompilation dependencies must be excellent.
- Java-facing async cannot be colorless. Java callers must see `CompletableFuture`, blocking wrappers, or callback APIs.
- Nullability and overload resolution will consume more time than expected.

Effekt reviewer:

- Capabilities are the right idea, but adapting them to Java mutation, reflection, classloaders, and binary compatibility is not a small translation.
- If capabilities feel like implicit parameters with scary errors, users will reject them.

Koka reviewer:

- Row effects plus subtyping plus Java overloads can become a constraint-solving trap.
- Keep rows simple and error messages concrete. Avoid principal-type ambitions.

JVM performance engineer:

- State-machine lowering plus effect handlers plus closures risks allocation blowups.
- Async stack traces can be expensive.
- Virtual threads handle many enterprise workloads well; justify every CPS path with benchmarks.

Gradle/tooling engineer:

- A Groovy replacement without first-class Gradle/Maven and IDE support will not be considered.
- Script startup and classloader caching are product features, not optimizations.

Enterprise architect:

- "Research language with effects" is a red flag. Sell resource safety, checked async, and Java interop.
- Preview JDK APIs cannot be a required production foundation.
- Debugging and stack traces decide adoption.

Naive assumptions:

- That Groovy users want static safety enough to tolerate new syntax.
- That Java interop can be "Kotlin-grade" quickly.
- That handler lowering and async lowering can share machinery without leaking complexity.
- That effect signatures in public APIs will not create compatibility pain.
- That source-level colorlessness will not confuse API boundaries.

Smaller but still ambitious plan:

1. Build a statically typed Groovy-like JVM script language with Java interop first.
2. Add a minimal effect checker for `IO`, `Throws`, and one user effect.
3. Add one-shot lexical handlers in an interpreter/IR.
4. Add built-in `Async` with `CompletableFuture` await and structured scope.
5. Defer generalized handler optimization, multi-shot, advanced rows, Kotlin suspend interop, and Gradle replacement.

This preserves typed effects, direct style, colorless async, Java interop, scripting, and a production path without promising an entire ecosystem in the first prototype.

## 29. Unresolved Research Questions

- Can capability region errors be made simple enough for script authors?
- What is the lowest-allocation representation for one-shot handlers on JVM bytecode?
- How far can async stack stitching go without unacceptable overhead?
- How should Java mutation purity be classified without massive annotation burden?
- Can effect metadata evolve compatibly across module versions?
- Is hand-written parser required for DSL ergonomics and LSP quality?
- Should `Throws[E]` be a first-class row effect or separate exception channel?
- How should `ThreadLocal` and framework context propagation be specified?
- Is virtual-thread-first async good enough for v1 with CPS only for `CompletableFuture`?
- Can future pure multi-shot be added without syntax/ABI breakage?

## 30. Final Recommendation

Build a new Kotlin-implemented compiler and Java 21 runtime.

Do not fork/port Effekt: use its capability and lexical handler model as semantic inspiration.

Do not extend Flix: learn from its JVM type/effect engineering and Java interop, but keep product syntax and runtime goals different.

Do not start as a Kotlin/Java-hosted DSL: it would be useful for experiments but would not validate the actual language, parser, diagnostics, script mode, or colorless async surface.

The immediate next step is not "build the compiler". It is the spike sequence in section 26, with a hard 90-day prototype target and explicit kill criteria for async lowering, diagnostics, and Java interop.

## 31. References

- OpenJDK JEP 444, Virtual Threads: https://openjdk.org/jeps/444
- OpenJDK JEP 525, Structured Concurrency sixth preview: https://openjdk.org/jeps/8366891
- Kotlin coroutines docs: https://kotlinlang.org/docs/coroutines-overview.html
- Kotlin coroutine language specification: https://kotlinlang.org/spec/asynchronous-programming-with-coroutines.html
- Effekt effect handlers docs: https://effekt-lang.org/docs/concepts/effect-handlers
- Effekt captures docs: https://effekt-lang.org/tour/captures
- Flix effect system docs: https://doc.flix.dev/effect-system.html
- Flix Java interop docs: https://doc.flix.dev/interoperability.html
- OCaml effects manual: https://ocaml.org/manual/effects.html
- OCaml Effect API: https://ocaml.org/manual/5.3/api/Effect.html
- Groovy language docs: https://docs.groovy-lang.org/docs/latest/html/documentation/
- Scala 3 contextual abstractions: https://docs.scala-lang.org/scala3/reference/contextual/
- Koka repository and language notes: https://github.com/koka-lang/koka
- Paul Blain Levy, lambda-calculus, effects and call-by-push-value: https://pblevy.github.io/mgsfastlam.pdf
