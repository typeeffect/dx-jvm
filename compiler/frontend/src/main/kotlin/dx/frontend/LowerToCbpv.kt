package dx.frontend

import dx.cbpv.CoreSourceSpan
import dx.cbpv.TypedComputation
import dx.cbpv.TypedSourceMap
import dx.cbpv.TypedSourceMapBuilder
import dx.cbpv.TypedValue

sealed interface LowerDiagnostic {
    data class UnsupportedExpression(val expression: String, val span: SourceSpan) : LowerDiagnostic
}

data class LowerResult(
    val computation: TypedComputation?,
    val diagnostics: List<LowerDiagnostic>,
    val sourceMap: TypedSourceMap = TypedSourceMap.Empty,
)

class CbpvLowerer {
    fun lower(module: DxModule): LowerResult {
        val diagnostics = mutableListOf<LowerDiagnostic>()
        val sourceMap = TypedSourceMapBuilder()
        val computation = lowerComputation(module.expression, diagnostics, sourceMap)
        return LowerResult(computation, diagnostics, sourceMap.build())
    }

    private fun lowerComputation(
        expr: DxExpr,
        diagnostics: MutableList<LowerDiagnostic>,
        sourceMap: TypedSourceMapBuilder,
    ): TypedComputation? =
        when (expr) {
            is DxExpr.Val -> {
                val value = lowerComputation(expr.value, diagnostics, sourceMap)
                val body = lowerComputation(expr.body, diagnostics, sourceMap)
                if (value != null && body != null) {
                    sourceMap.put(TypedComputation.Bind(expr.name, value, body), expr.span.toCoreSourceSpan())
                } else {
                    null
                }
            }
            is DxExpr.If -> {
                val condition = lowerValue(expr.condition, diagnostics, sourceMap)
                val thenBranch = lowerComputation(expr.thenBranch, diagnostics, sourceMap)
                val elseBranch = lowerComputation(expr.elseBranch, diagnostics, sourceMap)
                if (condition != null && thenBranch != null && elseBranch != null) {
                    sourceMap.put(TypedComputation.If(condition, thenBranch, elseBranch), expr.span.toCoreSourceSpan())
                } else {
                    null
                }
            }
            is DxExpr.Force -> {
                val value = lowerValue(expr.value, diagnostics, sourceMap)
                value?.let { sourceMap.put(TypedComputation.Force(it), expr.span.toCoreSourceSpan()) }
            }
            is DxExpr.Apply -> {
                val function = lowerValue(expr.function, diagnostics, sourceMap)
                val argument = lowerValue(expr.argument, diagnostics, sourceMap)
                if (function != null && argument != null) {
                    sourceMap.put(TypedComputation.Apply(function, argument), expr.span.toCoreSourceSpan())
                } else {
                    null
                }
            }
            else -> {
                val value = lowerValue(expr, diagnostics, sourceMap)
                value?.let { sourceMap.put(TypedComputation.Return(it), expr.span.toCoreSourceSpan()) }
            }
        }

    private fun lowerValue(
        expr: DxExpr,
        diagnostics: MutableList<LowerDiagnostic>,
        sourceMap: TypedSourceMapBuilder,
    ): TypedValue? =
        when (expr) {
            is DxExpr.UnitLiteral -> sourceMap.put(TypedValue.UnitValue, expr.span.toCoreSourceSpan())
            is DxExpr.BoolLiteral -> sourceMap.put(TypedValue.BoolValue(expr.value), expr.span.toCoreSourceSpan())
            is DxExpr.IntLiteral -> sourceMap.put(TypedValue.IntValue(expr.value), expr.span.toCoreSourceSpan())
            is DxExpr.StringLiteral -> sourceMap.put(TypedValue.StringValue(expr.value), expr.span.toCoreSourceSpan())
            is DxExpr.Variable -> sourceMap.put(TypedValue.Variable(expr.name), expr.span.toCoreSourceSpan())
            is DxExpr.PairExpr -> {
                val first = lowerValue(expr.first, diagnostics, sourceMap)
                val second = lowerValue(expr.second, diagnostics, sourceMap)
                if (first != null && second != null) {
                    sourceMap.put(TypedValue.PairValue(first, second), expr.span.toCoreSourceSpan())
                } else {
                    null
                }
            }
            is DxExpr.Thunk -> {
                val body = lowerComputation(expr.body, diagnostics, sourceMap)
                body?.let { sourceMap.put(TypedValue.ThunkValue(it), expr.span.toCoreSourceSpan()) }
            }
            is DxExpr.Lambda -> {
                val body = lowerComputation(expr.body, diagnostics, sourceMap)
                body?.let {
                    sourceMap.put(
                        TypedValue.Lambda(expr.parameter, expr.parameterType, it),
                        expr.span.toCoreSourceSpan(),
                    )
                }
            }
            is DxExpr.Apply,
            is DxExpr.Force,
            is DxExpr.If,
            is DxExpr.Val -> {
                diagnostics += LowerDiagnostic.UnsupportedExpression(
                    "effectful expression is not a value in the initial frontend subset",
                    expr.span,
                )
                null
            }
            else -> {
                diagnostics += LowerDiagnostic.UnsupportedExpression(
                    "effectful expression is not a value in the initial frontend subset",
                    expr.span,
                )
                null
            }
        }
}

data class FrontendResult(
    val module: DxModule?,
    val computation: TypedComputation?,
    val lexDiagnostics: List<LexDiagnostic>,
    val parseDiagnostics: List<ParseDiagnostic>,
    val lowerDiagnostics: List<LowerDiagnostic>,
    val sourceMap: TypedSourceMap = TypedSourceMap.Empty,
) {
    val isSuccess: Boolean get() =
        module != null &&
            computation != null &&
            lexDiagnostics.isEmpty() &&
            parseDiagnostics.isEmpty() &&
            lowerDiagnostics.isEmpty()
}

class FrontendPipeline {
    fun compile(source: SourceId, text: String): FrontendResult {
        val lexed = Lexer(source, text).lex()
        if (lexed.diagnostics.isNotEmpty()) {
            return FrontendResult(null, null, lexed.diagnostics, emptyList(), emptyList())
        }

        val parsed = Parser(lexed.tokens).parseModule()
        val module = parsed.module
        if (module == null || parsed.diagnostics.isNotEmpty()) {
            return FrontendResult(module, null, emptyList(), parsed.diagnostics, emptyList())
        }

        val lowered = CbpvLowerer().lower(module)
        return FrontendResult(
            module = module,
            computation = lowered.computation,
            lexDiagnostics = emptyList(),
            parseDiagnostics = emptyList(),
            lowerDiagnostics = lowered.diagnostics,
            sourceMap = lowered.sourceMap,
        )
    }
}

private fun SourceSpan.toCoreSourceSpan(): CoreSourceSpan =
    CoreSourceSpan(
        fileName = source.fileName,
        startOffset = startOffset,
        endOffset = endOffset,
        line = line,
        column = column,
    )
