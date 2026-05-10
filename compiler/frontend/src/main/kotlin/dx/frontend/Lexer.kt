package dx.frontend

class Lexer(
    private val source: SourceId,
    private val text: String,
) {
    private var offset = 0
    private var line = 1
    private var column = 1
    private val tokens = mutableListOf<Token>()
    private val diagnostics = mutableListOf<LexDiagnostic>()

    fun lex(): LexResult {
        while (!isAtEnd()) {
            val startOffset = offset
            val startLine = line
            val startColumn = column
            when (val c = advance()) {
                ' ', '\r', '\t' -> {}
                '\n' -> {}
                '(' -> token(TokenKind.LParen, "(", startOffset, startLine, startColumn)
                ')' -> token(TokenKind.RParen, ")", startOffset, startLine, startColumn)
                '{' -> token(TokenKind.LBrace, "{", startOffset, startLine, startColumn)
                '}' -> token(TokenKind.RBrace, "}", startOffset, startLine, startColumn)
                ';' -> token(TokenKind.Semicolon, ";", startOffset, startLine, startColumn)
                ',' -> token(TokenKind.Comma, ",", startOffset, startLine, startColumn)
                '=' -> token(TokenKind.Equal, "=", startOffset, startLine, startColumn)
                '-' -> {
                    if (match('>')) {
                        token(TokenKind.Arrow, "->", startOffset, startLine, startColumn)
                    } else {
                        unexpected(c, startOffset, startLine, startColumn)
                    }
                }
                '"' -> string(startOffset, startLine, startColumn)
                else -> when {
                    c.isDigit() -> number(startOffset, startLine, startColumn)
                    c.isIdentifierStart() -> identifier(startOffset, startLine, startColumn)
                    else -> unexpected(c, startOffset, startLine, startColumn)
                }
            }
        }

        tokens += Token(TokenKind.Eof, "", span(offset, offset, line, column))
        return LexResult(tokens, diagnostics)
    }

    private fun identifier(startOffset: Int, startLine: Int, startColumn: Int) {
        while (!isAtEnd() && peek().isIdentifierPart()) {
            advance()
        }
        val lexeme = text.substring(startOffset, offset)
        val kind = when (lexeme) {
            "true" -> TokenKind.True
            "false" -> TokenKind.False
            "unit" -> TokenKind.Unit
            "val" -> TokenKind.Val
            "fun" -> TokenKind.Fun
            "thunk" -> TokenKind.Thunk
            "force" -> TokenKind.Force
            "pair" -> TokenKind.Pair
            else -> TokenKind.Identifier
        }
        token(kind, lexeme, startOffset, startLine, startColumn)
    }

    private fun number(startOffset: Int, startLine: Int, startColumn: Int) {
        while (!isAtEnd() && peek().isDigit()) {
            advance()
        }
        token(TokenKind.Integer, text.substring(startOffset, offset), startOffset, startLine, startColumn)
    }

    private fun string(startOffset: Int, startLine: Int, startColumn: Int) {
        val builder = StringBuilder()
        while (!isAtEnd() && peek() != '"') {
            val c = advance()
            if (c == '\\' && !isAtEnd()) {
                builder.append(
                    when (val escaped = advance()) {
                        'n' -> '\n'
                        't' -> '\t'
                        '"' -> '"'
                        '\\' -> '\\'
                        else -> escaped
                    },
                )
            } else {
                builder.append(c)
            }
        }
        if (isAtEnd()) {
            diagnostics += LexDiagnostic.UnterminatedString(span(startOffset, offset, startLine, startColumn))
            return
        }
        advance()
        tokens += Token(TokenKind.String, builder.toString(), span(startOffset, offset, startLine, startColumn))
    }

    private fun token(kind: TokenKind, lexeme: String, startOffset: Int, startLine: Int, startColumn: Int) {
        tokens += Token(kind, lexeme, span(startOffset, offset, startLine, startColumn))
    }

    private fun unexpected(character: Char, startOffset: Int, startLine: Int, startColumn: Int) {
        diagnostics += LexDiagnostic.UnexpectedCharacter(
            character,
            span(startOffset, offset, startLine, startColumn),
        )
    }

    private fun span(startOffset: Int, endOffset: Int, startLine: Int, startColumn: Int): SourceSpan =
        SourceSpan(source, startOffset, endOffset, startLine, startColumn)

    private fun isAtEnd(): Boolean = offset >= text.length

    private fun peek(): Char = text[offset]

    private fun match(expected: Char): Boolean {
        if (isAtEnd() || text[offset] != expected) {
            return false
        }
        advance()
        return true
    }

    private fun advance(): Char {
        val c = text[offset++]
        if (c == '\n') {
            line += 1
            column = 1
        } else {
            column += 1
        }
        return c
    }
}

private fun Char.isIdentifierStart(): Boolean =
    this == '_' || this.isLetter()

private fun Char.isIdentifierPart(): Boolean =
    this == '_' || this.isLetterOrDigit()
