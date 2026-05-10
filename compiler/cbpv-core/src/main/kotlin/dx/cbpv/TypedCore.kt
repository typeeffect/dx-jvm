package dx.cbpv

typealias EffectName = String
typealias OperationName = String

data class CoreSourceSpan(
    val fileName: String,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val column: Int,
)

class TypedSourceMap(
    private val computationSpans: Map<TypedComputation, CoreSourceSpan>,
    private val valueSpans: Map<TypedValue, CoreSourceSpan>,
) {
    fun spanOf(computation: TypedComputation): CoreSourceSpan? = computationSpans[computation]

    fun spanOf(value: TypedValue): CoreSourceSpan? = valueSpans[value]

    companion object {
        val Empty: TypedSourceMap = TypedSourceMap(emptyMap(), emptyMap())
    }
}

class TypedSourceMapBuilder {
    private val computationSpans = java.util.IdentityHashMap<TypedComputation, CoreSourceSpan>()
    private val valueSpans = java.util.IdentityHashMap<TypedValue, CoreSourceSpan>()

    fun put(computation: TypedComputation, span: CoreSourceSpan): TypedComputation {
        computationSpans[computation] = span
        return computation
    }

    fun put(value: TypedValue, span: CoreSourceSpan): TypedValue {
        valueSpans[value] = span
        return value
    }

    fun build(): TypedSourceMap =
        TypedSourceMap(
            computationSpans = java.util.IdentityHashMap(computationSpans),
            valueSpans = java.util.IdentityHashMap(valueSpans),
        )
}

sealed interface ValueType {
    data object UnitType : ValueType
    data object BoolType : ValueType
    data object IntType : ValueType
    data object StringType : ValueType
    data class PairType(val first: ValueType, val second: ValueType) : ValueType
    data class ThunkType(val computation: ComputationType) : ValueType
    data class FunctionType(val parameter: ValueType, val result: ComputationType) : ValueType
}

data class ComputationType(
    val result: ValueType,
    val effects: Set<EffectName> = emptySet(),
)

data class OperationSignature(
    val arguments: List<ValueType>,
    val result: ValueType,
)

data class EffectSignature(
    val name: EffectName,
    val operations: Map<OperationName, OperationSignature>,
)

data class TypeEnvironment(
    val variables: Map<String, ValueType> = emptyMap(),
    val effects: Map<EffectName, EffectSignature> = emptyMap(),
) {
    fun withVariable(name: String, type: ValueType): TypeEnvironment =
        copy(variables = variables + (name to type))

    fun withEffect(signature: EffectSignature): TypeEnvironment =
        copy(effects = effects + (signature.name to signature))
}

sealed interface TypedValue {
    data object UnitValue : TypedValue
    data class BoolValue(val value: Boolean) : TypedValue
    data class IntValue(val value: Long) : TypedValue
    data class StringValue(val value: String) : TypedValue
    data class PairValue(val first: TypedValue, val second: TypedValue) : TypedValue
    data class Variable(val name: String) : TypedValue
    data class ThunkValue(val computation: TypedComputation) : TypedValue
    data class Lambda(
        val parameter: String,
        val parameterType: ValueType,
        val body: TypedComputation,
    ) : TypedValue
}

sealed interface TypedComputation {
    data class Return(val value: TypedValue) : TypedComputation
    data class Bind(
        val name: String,
        val first: TypedComputation,
        val next: TypedComputation,
    ) : TypedComputation

    data class If(
        val condition: TypedValue,
        val thenBranch: TypedComputation,
        val elseBranch: TypedComputation,
    ) : TypedComputation

    data class Force(val thunk: TypedValue) : TypedComputation
    data class Apply(val function: TypedValue, val argument: TypedValue) : TypedComputation
    data class Perform(
        val effect: EffectName,
        val operation: OperationName,
        val arguments: List<TypedValue> = emptyList(),
    ) : TypedComputation

    data class Handle(
        val body: TypedComputation,
        val handler: TypedHandler,
    ) : TypedComputation

    data class Resume(val value: TypedValue) : TypedComputation
}

data class TypedHandler(
    val effect: EffectName,
    val clauses: Map<OperationName, TypedHandlerClause>,
)

data class TypedHandlerClause(
    val parameters: List<String>,
    val body: TypedComputation,
)

sealed interface TypeDiagnostic {
    data class UnknownVariable(val name: String) : TypeDiagnostic
    data class UnknownEffect(val effect: EffectName) : TypeDiagnostic
    data class UnknownOperation(
        val effect: EffectName,
        val operation: OperationName,
    ) : TypeDiagnostic

    data class DuplicateHandlerParameter(val name: String) : TypeDiagnostic
    data class MissingHandlerClause(
        val effect: EffectName,
        val operation: OperationName,
    ) : TypeDiagnostic

    data class TypeMismatch(
        val expected: ValueType,
        val actual: ValueType,
    ) : TypeDiagnostic

    data class ArityMismatch(
        val expected: Int,
        val actual: Int,
    ) : TypeDiagnostic

    data object ForceNonThunk : TypeDiagnostic
    data object ApplyNonFunction : TypeDiagnostic
    data object IfConditionNonBool : TypeDiagnostic
    data object ResumeOutsideHandlerClause : TypeDiagnostic
    data class ResumeTypeMismatch(
        val expected: ValueType,
        val actual: ValueType,
    ) : TypeDiagnostic

    data class UnhandledEffects(
        val effects: Set<EffectName>,
    ) : TypeDiagnostic
}

data class TypeCheckResult(
    val type: ComputationType?,
    val diagnostics: List<TypeDiagnostic>,
    val reports: List<TypeDiagnosticReport> = diagnostics.map { TypeDiagnosticReport(it, null) },
) {
    val isSuccess: Boolean get() = diagnostics.isEmpty() && type != null
}

data class TypeDiagnosticReport(
    val diagnostic: TypeDiagnostic,
    val source: CoreSourceSpan?,
)

private data class HandlerContext(
    val resumeValue: ValueType,
    val handlerResult: ValueType,
)

class TypeChecker(
    private val environment: TypeEnvironment,
    private val sourceMap: TypedSourceMap = TypedSourceMap.Empty,
) {
    fun infer(computation: TypedComputation): TypeCheckResult {
        val diagnostics = TypeDiagnosticSink(sourceMap)
        val type = inferComputation(computation, environment, handlerContext = null, diagnostics)
        return TypeCheckResult(type, diagnostics.diagnostics, diagnostics.reports)
    }

    fun checkClosed(
        computation: TypedComputation,
        expectedResult: ValueType,
        allowedEffects: Set<EffectName> = emptySet(),
    ): TypeCheckResult {
        val result = infer(computation)
        val diagnostics = result.reports.toMutableList()
        val type = result.type

        if (type != null) {
            if (type.result != expectedResult) {
                diagnostics += TypeDiagnosticReport(
                    TypeDiagnostic.TypeMismatch(expectedResult, type.result),
                    sourceMap.spanOf(computation),
                )
            }
            val unhandled = type.effects - allowedEffects
            if (unhandled.isNotEmpty()) {
                diagnostics += TypeDiagnosticReport(TypeDiagnostic.UnhandledEffects(unhandled), sourceMap.spanOf(computation))
            }
        }

        return TypeCheckResult(type, diagnostics.map { it.diagnostic }, diagnostics)
    }

    private fun inferComputation(
        computation: TypedComputation,
        env: TypeEnvironment,
        handlerContext: HandlerContext?,
        diagnostics: TypeDiagnosticSink,
    ): ComputationType? =
        when (computation) {
            is TypedComputation.Return -> {
                val type = inferValue(computation.value, env, diagnostics)
                type?.let { ComputationType(it) }
            }
            is TypedComputation.Bind -> {
                val firstType = inferComputation(computation.first, env, handlerContext, diagnostics)
                if (firstType == null) {
                    null
                } else {
                    val nextType = inferComputation(
                        computation.next,
                        env.withVariable(computation.name, firstType.result),
                        handlerContext,
                        diagnostics,
                    )
                    nextType?.let {
                        ComputationType(it.result, firstType.effects + it.effects)
                    }
                }
            }
            is TypedComputation.If -> {
                val conditionType = inferValue(computation.condition, env, diagnostics)
                if (conditionType != null && conditionType != ValueType.BoolType) {
                    diagnostics.add(TypeDiagnostic.IfConditionNonBool, computation.condition)
                }

                val thenType = inferComputation(computation.thenBranch, env, handlerContext, diagnostics)
                val elseType = inferComputation(computation.elseBranch, env, handlerContext, diagnostics)
                if (thenType != null && elseType != null) {
                    if (thenType.result != elseType.result) {
                        diagnostics.add(TypeDiagnostic.TypeMismatch(thenType.result, elseType.result), computation.elseBranch)
                    }
                    ComputationType(thenType.result, thenType.effects + elseType.effects)
                } else {
                    null
                }
            }
            is TypedComputation.Force -> {
                when (val thunkType = inferValue(computation.thunk, env, diagnostics)) {
                    is ValueType.ThunkType -> thunkType.computation
                    null -> null
                    else -> {
                        diagnostics.add(TypeDiagnostic.ForceNonThunk, computation.thunk)
                        null
                    }
                }
            }
            is TypedComputation.Apply -> {
                val functionType = inferValue(computation.function, env, diagnostics)
                val argumentType = inferValue(computation.argument, env, diagnostics)
                when (functionType) {
                    is ValueType.FunctionType -> {
                        if (argumentType != null && argumentType != functionType.parameter) {
                            diagnostics.add(TypeDiagnostic.TypeMismatch(functionType.parameter, argumentType), computation.argument)
                        }
                        functionType.result
                    }
                    null -> null
                    else -> {
                        diagnostics.add(TypeDiagnostic.ApplyNonFunction, computation.function)
                        null
                    }
                }
            }
            is TypedComputation.Perform -> inferPerform(computation, env, diagnostics)
            is TypedComputation.Handle -> inferHandle(computation, env, handlerContext, diagnostics)
            is TypedComputation.Resume -> {
                if (handlerContext == null) {
                    diagnostics.add(TypeDiagnostic.ResumeOutsideHandlerClause, computation)
                    null
                } else {
                    val valueType = inferValue(computation.value, env, diagnostics)
                    if (valueType != null && valueType != handlerContext.resumeValue) {
                        diagnostics.add(
                            TypeDiagnostic.ResumeTypeMismatch(handlerContext.resumeValue, valueType),
                            computation.value,
                        )
                    }
                    ComputationType(handlerContext.handlerResult)
                }
            }
        }

    private fun inferPerform(
        computation: TypedComputation.Perform,
        env: TypeEnvironment,
        diagnostics: TypeDiagnosticSink,
    ): ComputationType? {
        val effect = env.effects[computation.effect]
        if (effect == null) {
            diagnostics.add(TypeDiagnostic.UnknownEffect(computation.effect), computation)
            return null
        }

        val operation = effect.operations[computation.operation]
        if (operation == null) {
            diagnostics.add(TypeDiagnostic.UnknownOperation(computation.effect, computation.operation), computation)
            return null
        }

        if (operation.arguments.size != computation.arguments.size) {
            diagnostics.add(TypeDiagnostic.ArityMismatch(operation.arguments.size, computation.arguments.size), computation)
            return null
        }

        operation.arguments.zip(computation.arguments).forEach { (expected, value) ->
            val actual = inferValue(value, env, diagnostics)
            if (actual != null && actual != expected) {
                diagnostics.add(TypeDiagnostic.TypeMismatch(expected, actual), value)
            }
        }

        return ComputationType(operation.result, setOf(computation.effect))
    }

    private fun inferHandle(
        computation: TypedComputation.Handle,
        env: TypeEnvironment,
        outerHandlerContext: HandlerContext?,
        diagnostics: TypeDiagnosticSink,
    ): ComputationType? {
        val signature = env.effects[computation.handler.effect]
        if (signature == null) {
            diagnostics.add(TypeDiagnostic.UnknownEffect(computation.handler.effect), computation)
            return null
        }

        val bodyType = inferComputation(computation.body, env, outerHandlerContext, diagnostics)
            ?: return null

        for (operationName in signature.operations.keys) {
            if (operationName !in computation.handler.clauses) {
                diagnostics.add(TypeDiagnostic.MissingHandlerClause(signature.name, operationName), computation)
            }
        }

        var clauseEffects = emptySet<EffectName>()
        for ((operationName, clause) in computation.handler.clauses) {
            val operation = signature.operations[operationName]
            if (operation == null) {
                diagnostics.add(TypeDiagnostic.UnknownOperation(signature.name, operationName), clause.body)
                continue
            }
            if (operation.arguments.size != clause.parameters.size) {
                diagnostics.add(TypeDiagnostic.ArityMismatch(operation.arguments.size, clause.parameters.size), clause.body)
                continue
            }

            val duplicate = clause.parameters.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
            if (duplicate != null) {
                diagnostics.add(TypeDiagnostic.DuplicateHandlerParameter(duplicate.key), clause.body)
                continue
            }

            val clauseEnv = operation.arguments.zip(clause.parameters).fold(env) { acc, (type, name) ->
                acc.withVariable(name, type)
            }
            val clauseType = inferComputation(
                clause.body,
                clauseEnv,
                HandlerContext(operation.result, bodyType.result),
                diagnostics,
            )
            if (clauseType != null) {
                if (clauseType.result != bodyType.result) {
                    diagnostics.add(TypeDiagnostic.TypeMismatch(bodyType.result, clauseType.result), clause.body)
                }
                clauseEffects = clauseEffects + clauseType.effects
            }
        }

        return ComputationType(
            result = bodyType.result,
            effects = (bodyType.effects - signature.name) + clauseEffects,
        )
    }

    private fun inferValue(
        value: TypedValue,
        env: TypeEnvironment,
        diagnostics: TypeDiagnosticSink,
    ): ValueType? =
        when (value) {
            TypedValue.UnitValue -> ValueType.UnitType
            is TypedValue.BoolValue -> ValueType.BoolType
            is TypedValue.IntValue -> ValueType.IntType
            is TypedValue.StringValue -> ValueType.StringType
            is TypedValue.PairValue -> {
                val first = inferValue(value.first, env, diagnostics)
                val second = inferValue(value.second, env, diagnostics)
                if (first != null && second != null) {
                    ValueType.PairType(first, second)
                } else {
                    null
                }
            }
            is TypedValue.Variable -> {
                env.variables[value.name] ?: run {
                    diagnostics.add(TypeDiagnostic.UnknownVariable(value.name), value)
                    null
                }
            }
            is TypedValue.ThunkValue -> {
                val computationType = inferComputation(value.computation, env, handlerContext = null, diagnostics)
                computationType?.let { ValueType.ThunkType(it) }
            }
            is TypedValue.Lambda -> {
                val bodyType = inferComputation(
                    value.body,
                    env.withVariable(value.parameter, value.parameterType),
                    handlerContext = null,
                    diagnostics,
                )
                bodyType?.let { ValueType.FunctionType(value.parameterType, it) }
            }
        }
}

private class TypeDiagnosticSink(
    private val sourceMap: TypedSourceMap,
) {
    val reports = mutableListOf<TypeDiagnosticReport>()
    val diagnostics: List<TypeDiagnostic> get() = reports.map { it.diagnostic }

    fun add(diagnostic: TypeDiagnostic, computation: TypedComputation) {
        reports += TypeDiagnosticReport(diagnostic, sourceMap.spanOf(computation))
    }

    fun add(diagnostic: TypeDiagnostic, value: TypedValue) {
        reports += TypeDiagnosticReport(diagnostic, sourceMap.spanOf(value))
    }
}
