package dx.cbpv

import java.util.concurrent.atomic.AtomicReference

sealed interface Value {
    data object UnitValue : Value
    data class BoolValue(val value: Boolean) : Value
    data class IntValue(val value: Long) : Value
    data class StringValue(val value: String) : Value
    data class PairValue(val first: Value, val second: Value) : Value
    data class ThunkValue(val computation: Computation) : Value
    data class FunctionValue(val apply: (Value) -> Computation) : Value
}

sealed interface Computation {
    data class Return(val value: Value) : Computation
    data class Bind(val first: Computation, val next: (Value) -> Computation) : Computation
    data class Force(val thunk: Value) : Computation
    data class Apply(val function: Value, val argument: Value) : Computation
    data class Perform(
        val effect: String,
        val operation: String,
        val arguments: List<Value> = emptyList(),
    ) : Computation

    data class Handle(val body: Computation, val handler: Handler) : Computation
}

data class Handler(
    val effect: String,
    val operations: Map<String, OperationClause>,
)

fun interface OperationClause {
    fun handle(arguments: List<Value>, resumption: Resumption): EvalResult
}

sealed interface EvalResult {
    data class Done(val value: Value) : EvalResult
    data class Failed(val error: EvalError) : EvalResult
}

sealed interface EvalError {
    data class TypeMismatch(val expected: String, val actual: Value) : EvalError
    data class UnhandledEffect(val effect: String, val operation: String) : EvalError
    data class MissingOperation(val effect: String, val operation: String) : EvalError
    data object ContinuationAlreadyResumed : EvalError
    data object ContinuationEscaped : EvalError
}

private enum class ScopeState {
    Active,
    Closed,
}

private class HandlerScope {
    private val state = AtomicReference(ScopeState.Active)

    fun close() {
        state.compareAndSet(ScopeState.Active, ScopeState.Closed)
    }

    fun isActive(): Boolean = state.get() == ScopeState.Active
}

private data class HandlerFrame(
    val handler: Handler,
    val scope: HandlerScope,
)

private enum class ResumptionState {
    Fresh,
    Resumed,
}

class Resumption internal constructor(
    private val scope: HandlerScope,
    private val continueWith: (Value) -> EvalResult,
) {
    private val state = AtomicReference(ResumptionState.Fresh)

    fun resume(value: Value): EvalResult {
        if (!state.compareAndSet(ResumptionState.Fresh, ResumptionState.Resumed)) {
            return EvalResult.Failed(EvalError.ContinuationAlreadyResumed)
        }
        if (!scope.isActive()) {
            return EvalResult.Failed(EvalError.ContinuationEscaped)
        }
        return continueWith(value)
    }
}

class Evaluator {
    fun eval(computation: Computation): EvalResult =
        eval(computation, emptyList()) { value -> EvalResult.Done(value) }

    private fun eval(
        computation: Computation,
        handlers: List<HandlerFrame>,
        continuation: (Value) -> EvalResult,
    ): EvalResult =
        when (computation) {
            is Computation.Return -> continuation(computation.value)
            is Computation.Bind ->
                eval(computation.first, handlers) { value ->
                    eval(computation.next(value), handlers, continuation)
                }
            is Computation.Force ->
                when (val thunk = computation.thunk) {
                    is Value.ThunkValue -> eval(thunk.computation, handlers, continuation)
                    else -> EvalResult.Failed(EvalError.TypeMismatch("thunk", thunk))
                }
            is Computation.Apply ->
                when (val function = computation.function) {
                    is Value.FunctionValue -> eval(function.apply(computation.argument), handlers, continuation)
                    else -> EvalResult.Failed(EvalError.TypeMismatch("function", function))
                }
            is Computation.Perform ->
                perform(computation, handlers, continuation)
            is Computation.Handle ->
                handle(computation, handlers, continuation)
        }

    private fun perform(
        computation: Computation.Perform,
        handlers: List<HandlerFrame>,
        continuation: (Value) -> EvalResult,
    ): EvalResult {
        val frame = handlers.asReversed().firstOrNull {
            it.handler.effect == computation.effect
        } ?: return EvalResult.Failed(
            EvalError.UnhandledEffect(computation.effect, computation.operation),
        )

        val operation = frame.handler.operations[computation.operation]
            ?: return EvalResult.Failed(
                EvalError.MissingOperation(computation.effect, computation.operation),
            )

        val resumption = Resumption(frame.scope, continuation)
        return operation.handle(computation.arguments, resumption)
    }

    private fun handle(
        computation: Computation.Handle,
        handlers: List<HandlerFrame>,
        continuation: (Value) -> EvalResult,
    ): EvalResult {
        val scope = HandlerScope()
        val frame = HandlerFrame(computation.handler, scope)
        var closed = false

        fun closeScope() {
            if (!closed) {
                closed = true
                scope.close()
            }
        }

        val result = eval(computation.body, handlers + frame) { value ->
            closeScope()
            continuation(value)
        }
        closeScope()
        return result
    }
}

fun ret(value: Value): Computation = Computation.Return(value)

fun bind(first: Computation, next: (Value) -> Computation): Computation =
    Computation.Bind(first, next)

fun thunk(computation: Computation): Value = Value.ThunkValue(computation)

fun force(value: Value): Computation = Computation.Force(value)

fun perform(effect: String, operation: String, arguments: List<Value> = emptyList()): Computation =
    Computation.Perform(effect, operation, arguments)

fun handle(body: Computation, handler: Handler): Computation =
    Computation.Handle(body, handler)

fun int(value: Long): Value = Value.IntValue(value)

fun bool(value: Boolean): Value = Value.BoolValue(value)

fun str(value: String): Value = Value.StringValue(value)

val unit: Value = Value.UnitValue
