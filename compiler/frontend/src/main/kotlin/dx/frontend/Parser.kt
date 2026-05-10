package dx.frontend

sealed interface ParseDiagnostic {
    data class Expected(val expected: String, val actual: Token) : ParseDiagnostic
}

data class ParseResult(
    val module: DxModule?,
    val diagnostics: List<ParseDiagnostic>,
)

class Parser(private val tokens: List<Token>) {
    private var current = 0
    private val diagnostics = mutableListOf<ParseDiagnostic>()

    fun parseModule(): ParseResult {
        val expression = parseSequenceUntil(TokenKind.Eof)
        consume(TokenKind.Eof, "end of file")
        return ParseResult(expression?.let(::DxModule), diagnostics)
    }

    private fun parseSequenceUntil(end: TokenKind): DxExpr? {
        val expressions = mutableListOf<DxExpr>()
        while (!check(end) && !isAtEnd()) {
            expressions += parseExpr() ?: return null
            if (match(TokenKind.Semicolon)) {
                while (match(TokenKind.Semicolon)) {
                    // allow repeated separators
                }
            } else if (!check(end)) {
                diagnostics += ParseDiagnostic.Expected("`;` or `${end.name}`", peek())
                return null
            }
        }

        if (expressions.isEmpty()) {
            diagnostics += ParseDiagnostic.Expected("expression", peek())
            return null
        }

        return expressions.asReversed().reduce { body, expr ->
            when (expr) {
                is PendingVal -> DxExpr.Val(expr.name, expr.value, body, merge(expr.span, body.span))
                else -> {
                    diagnostics += ParseDiagnostic.Expected("only `val` may precede a final expression", tokenAt(expr.span))
                    body
                }
            }
        }
    }

    private fun parseExpr(): DxExpr? =
        when {
            match(TokenKind.Val) -> parseVal(previous())
            match(TokenKind.Fun) -> parseLambda(previous())
            match(TokenKind.Thunk) -> parseThunk(previous())
            match(TokenKind.Force) -> parseForce(previous())
            match(TokenKind.Pair) -> parsePair(previous())
            else -> parseApplication()
        }

    private fun parseVal(keyword: Token): DxExpr? {
        val name = consume(TokenKind.Identifier, "identifier") ?: return null
        consume(TokenKind.Equal, "`=`") ?: return null
        val value = parseExpr() ?: return null
        return PendingVal(name.lexeme, value, merge(keyword.span, value.span))
    }

    private fun parseLambda(keyword: Token): DxExpr? {
        val name = consume(TokenKind.Identifier, "identifier") ?: return null
        consume(TokenKind.Arrow, "`->`") ?: return null
        val body = parseExpr() ?: return null
        return DxExpr.Lambda(name.lexeme, body, merge(keyword.span, body.span))
    }

    private fun parseThunk(keyword: Token): DxExpr? {
        val body = parseBlock() ?: return null
        return DxExpr.Thunk(body, merge(keyword.span, body.span))
    }

    private fun parseForce(keyword: Token): DxExpr? {
        val value = parseExpr() ?: return null
        return DxExpr.Force(value, merge(keyword.span, value.span))
    }

    private fun parsePair(keyword: Token): DxExpr? {
        consume(TokenKind.LParen, "`(`") ?: return null
        val first = parseExpr() ?: return null
        consume(TokenKind.Comma, "`,`") ?: return null
        val second = parseExpr() ?: return null
        val close = consume(TokenKind.RParen, "`)`") ?: return null
        return DxExpr.PairExpr(first, second, merge(keyword.span, close.span))
    }

    private fun parseApplication(): DxExpr? {
        var expr = parsePrimary() ?: return null
        while (match(TokenKind.LParen)) {
            val open = previous()
            val argument = if (check(TokenKind.RParen)) {
                DxExpr.UnitLiteral(open.span)
            } else {
                parseExpr() ?: return null
            }
            val close = consume(TokenKind.RParen, "`)`") ?: return null
            expr = DxExpr.Apply(expr, argument, merge(expr.span, close.span))
        }
        return expr
    }

    private fun parsePrimary(): DxExpr? =
        when {
            match(TokenKind.Unit) -> DxExpr.UnitLiteral(previous().span)
            match(TokenKind.True) -> DxExpr.BoolLiteral(true, previous().span)
            match(TokenKind.False) -> DxExpr.BoolLiteral(false, previous().span)
            match(TokenKind.Integer) -> DxExpr.IntLiteral(previous().lexeme.toLong(), previous().span)
            match(TokenKind.String) -> DxExpr.StringLiteral(previous().lexeme, previous().span)
            match(TokenKind.Identifier) -> DxExpr.Variable(previous().lexeme, previous().span)
            match(TokenKind.LParen) -> {
                val expr = parseExpr() ?: return null
                consume(TokenKind.RParen, "`)`")
                expr
            }
            match(TokenKind.LBrace) -> parseBlockAfterOpen(previous())
            else -> {
                diagnostics += ParseDiagnostic.Expected("expression", peek())
                null
            }
        }

    private fun parseBlock(): DxExpr? {
        val open = consume(TokenKind.LBrace, "`{`") ?: return null
        return parseBlockAfterOpen(open)
    }

    private fun parseBlockAfterOpen(open: Token): DxExpr? {
        val body = parseSequenceUntil(TokenKind.RBrace) ?: return null
        consume(TokenKind.RBrace, "`}`") ?: return null
        return body
    }

    private fun consume(kind: TokenKind, expected: String): Token? {
        if (check(kind)) {
            return advance()
        }
        diagnostics += ParseDiagnostic.Expected(expected, peek())
        return null
    }

    private fun match(kind: TokenKind): Boolean {
        if (!check(kind)) {
            return false
        }
        advance()
        return true
    }

    private fun check(kind: TokenKind): Boolean =
        current < tokens.size && peek().kind == kind

    private fun isAtEnd(): Boolean =
        current >= tokens.size || peek().kind == TokenKind.Eof

    private fun advance(): Token {
        val token = peek()
        if (current < tokens.lastIndex) {
            current += 1
        }
        return token
    }

    private fun peek(): Token = tokens[current]

    private fun previous(): Token = tokens[current - 1]

    private fun tokenAt(span: SourceSpan): Token =
        tokens.firstOrNull { it.span == span } ?: peek()

    private fun merge(first: SourceSpan, second: SourceSpan): SourceSpan =
        SourceSpan(first.source, first.startOffset, second.endOffset, first.line, first.column)

    private data class PendingVal(
        val name: String,
        val value: DxExpr,
        override val span: SourceSpan,
    ) : DxExpr
}
