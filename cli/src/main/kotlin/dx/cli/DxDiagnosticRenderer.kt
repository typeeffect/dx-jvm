package dx.cli

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

    private fun renderLex(diagnostic: LexDiagnostic): String =
        when (diagnostic) {
            is LexDiagnostic.UnexpectedCharacter ->
                renderAt(diagnostic.span, "unexpected character `${escape(diagnostic.character.toString())}`")
            is LexDiagnostic.UnterminatedString ->
                renderAt(diagnostic.span, "unterminated string literal")
        }

    private fun renderParse(diagnostic: ParseDiagnostic): String =
        when (diagnostic) {
            is ParseDiagnostic.Expected ->
                renderAt(diagnostic.actual.span, "expected ${diagnostic.expected}, found ${describe(diagnostic.actual)}")
        }

    private fun renderLower(diagnostic: LowerDiagnostic): String =
        when (diagnostic) {
            is LowerDiagnostic.UnsupportedExpression ->
                renderAt(diagnostic.span, diagnostic.expression)
        }

    private fun renderAt(span: SourceSpan, message: String): String {
        val lineText = sourceText.lineAt(span.line)
        val caretColumn = span.column.coerceAtLeast(1)
        val width = (span.endOffset - span.startOffset).coerceAtLeast(1)
        val caret = " ".repeat(caretColumn - 1) + "^".repeat(width.coerceAtMost(80))
        return buildString {
            appendLine("${span.source.fileName}:${span.line}:${span.column}: error: $message")
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
