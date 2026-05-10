package dx.cli

import dx.cbpv.CoreSourceSpan
import dx.cbpv.ComputationType
import dx.cbpv.TypeDiagnostic
import dx.cbpv.TypeDiagnosticReport
import dx.cbpv.ValueType
import dx.frontend.FrontendResult
import dx.frontend.LexDiagnostic
import dx.frontend.LowerDiagnostic
import dx.frontend.ParseDiagnostic
import dx.frontend.SourceSpan
import dx.frontend.Token
import dx.frontend.TokenKind

class DxDiagnosticRenderer(
    private val sourceText: String,
) {
    fun renderFrontend(result: FrontendResult): List<String> =
        result.lexDiagnostics.map(::renderLex) +
            result.parseDiagnostics.map(::renderParse) +
            result.lowerDiagnostics.map(::renderLower)

    fun renderType(reports: List<TypeDiagnosticReport>): List<String> =
        reports.map { report ->
            val message = report.diagnostic.toMessage()
            val source = report.source
            if (source == null) {
                "error: $message"
            } else {
                renderAt(source.toDiagnosticSpan(), message)
            }
        }

    private fun renderLex(diagnostic: LexDiagnostic): String =
        when (diagnostic) {
            is LexDiagnostic.UnexpectedCharacter ->
                renderAt(diagnostic.span.toDiagnosticSpan(), "unexpected character `${escape(diagnostic.character.toString())}`")
            is LexDiagnostic.UnterminatedString ->
                renderAt(diagnostic.span.toDiagnosticSpan(), "unterminated string literal")
        }

    private fun renderParse(diagnostic: ParseDiagnostic): String =
        when (diagnostic) {
            is ParseDiagnostic.Expected ->
                renderAt(
                    diagnostic.actual.span.toDiagnosticSpan(),
                    "expected ${diagnostic.expected}, found ${describe(diagnostic.actual)}",
                )
        }

    private fun renderLower(diagnostic: LowerDiagnostic): String =
        when (diagnostic) {
            is LowerDiagnostic.UnsupportedExpression ->
                renderAt(diagnostic.span.toDiagnosticSpan(), diagnostic.expression)
        }

    private fun renderAt(span: DiagnosticSpan, message: String): String {
        val lineText = sourceText.lineAt(span.line)
        val caretColumn = span.column.coerceAtLeast(1)
        val width = (span.endOffset - span.startOffset).coerceAtLeast(1)
        val caret = " ".repeat(caretColumn - 1) + "^".repeat(width.coerceAtMost(80))
        return buildString {
            appendLine("${span.fileName}:${span.line}:${span.column}: error: $message")
            appendLine(lineText)
            append(caret)
        }
    }

    private fun describe(token: Token): String =
        when (token.kind) {
            TokenKind.Eof -> "end of file"
            TokenKind.Identifier -> "identifier `${token.lexeme}`"
            TokenKind.Integer -> "integer literal"
            TokenKind.String -> "string literal"
            else -> "`${token.lexeme}`"
        }

    private fun TypeDiagnostic.toMessage(): String =
        when (this) {
            is TypeDiagnostic.UnknownVariable -> "unknown variable `$name`"
            is TypeDiagnostic.UnknownEffect -> "unknown effect `$effect`"
            is TypeDiagnostic.UnknownOperation -> "unknown operation `$effect.$operation`"
            is TypeDiagnostic.DuplicateHandlerParameter -> "duplicate handler parameter `$name`"
            is TypeDiagnostic.MissingHandlerClause -> "missing handler clause for `$effect.$operation`"
            is TypeDiagnostic.TypeMismatch -> "expected ${expected.render()}, found ${actual.render()}"
            is TypeDiagnostic.ArityMismatch -> "expected $expected argument(s), found $actual"
            TypeDiagnostic.ForceNonThunk -> "`force` expects a thunk"
            TypeDiagnostic.ApplyNonFunction -> "application expects a function"
            is TypeDiagnostic.ExpectedReturnComputation -> "expected computation returning a value, found ${actual.render()}"
            TypeDiagnostic.IfConditionNonBool -> "`if` condition must be Bool"
            TypeDiagnostic.ResumeOutsideHandlerClause -> "`resume` is only valid inside a handler clause"
            is TypeDiagnostic.ResumeTypeMismatch -> "resume expected ${expected.render()}, found ${actual.render()}"
            is TypeDiagnostic.UnhandledEffects -> "unhandled effects: ${effects.sorted().joinToString(", ")}"
        }

    private fun ValueType.render(): String =
        when (this) {
            ValueType.UnitType -> "Unit"
            ValueType.BoolType -> "Bool"
            ValueType.IntType -> "Int"
            ValueType.StringType -> "Str"
            is ValueType.PairType -> "Pair<${first.render()}, ${second.render()}>"
            is ValueType.ThunkType -> "Thunk<${computation.render()}>"
        }

    private fun ComputationType.render(): String =
        when (this) {
            is ComputationType.ReturnType -> "F ${result.render()}"
            is ComputationType.FunctionType -> "(${parameter.render()}) -> ${result.render()}"
        }

    private fun String.lineAt(lineNumber: Int): String =
        lineSequence().drop(lineNumber - 1).firstOrNull() ?: ""

    private fun escape(value: String): String =
        value.flatMap { char ->
            when (char) {
                '\n' -> listOf('\\', 'n')
                '\r' -> listOf('\\', 'r')
                '\t' -> listOf('\\', 't')
                else -> listOf(char)
            }
        }.joinToString("")
}

private data class DiagnosticSpan(
    val fileName: String,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val column: Int,
)

private fun SourceSpan.toDiagnosticSpan(): DiagnosticSpan =
    DiagnosticSpan(
        fileName = source.fileName,
        startOffset = startOffset,
        endOffset = endOffset,
        line = line,
        column = column,
    )

private fun CoreSourceSpan.toDiagnosticSpan(): DiagnosticSpan =
    DiagnosticSpan(
        fileName = fileName,
        startOffset = startOffset,
        endOffset = endOffset,
        line = line,
        column = column,
    )
