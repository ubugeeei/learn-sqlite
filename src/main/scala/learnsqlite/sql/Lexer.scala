package learnsqlite.sql

/** One-based source location suitable for diagnostics. */
final case class SourcePosition(offset: Int, line: Int, column: Int)

enum TokenKind:
  case Word(value: String)
  case Integer(value: Long)
  case Real(value: Double)
  case Text(value: String)
  case LeftParen, RightParen, Comma, Semicolon, Star
  case Plus, Minus, Slash
  case Equal, NotEqual, Less, LessOrEqual, Greater, GreaterOrEqual
  case End

final case class Token(kind: TokenKind, position: SourcePosition)
final case class LexError(message: String, position: SourcePosition)

/**
 * Converts SQL text to positioned tokens.
 *
 * The accepted lexical forms follow SQLite's
 * [[https://www.sqlite.org/lang_expr.html literal syntax]] where applicable.
 */
object Lexer:
  def tokenize(input: String): Either[LexError, Vector[Token]] =
    val scanner = Scanner(input)
    scanner.scanAll()

  final private class Scanner(input: String):
    private var offset = 0
    private var line = 1
    private var column = 1

    def scanAll(): Either[LexError, Vector[Token]] =
      val result = Vector.newBuilder[Token]
      var failure: Option[LexError] = None
      while offset < input.length && failure.isEmpty do
        skipTrivia()
        if offset < input.length then
          nextToken() match
            case Right(token) => result += token
            case Left(error)  => failure = Some(error)
      failure match
        case Some(error) => Left(error)
        case None        => Right(result.result() :+ Token(TokenKind.End, position))

    private def skipTrivia(): Unit =
      var continuing = true
      while continuing && offset < input.length do
        if current.isWhitespace then advance()
        else if current == '-' && peek(1).contains('-') then
          while offset < input.length && current != '\n' do advance()
        else if current == '/' && peek(1).contains('*') then
          advance(); advance()
          while offset < input.length && !(current == '*' && peek(1).contains(
              '/'
            ))
          do advance()
          if offset < input.length then
            advance(); advance()
        else continuing = false

    private def nextToken(): Either[LexError, Token] =
      val start = position
      current match
        case c if c.isLetter || c == '_' =>
          Right(
            Token(
              TokenKind.Word(takeWhile(ch => ch.isLetterOrDigit || ch == '_')),
              start
            )
          )
        case c if c.isDigit               => number(start)
        case '\''                         => string(start)
        case '"'                          => quotedIdentifier(start)
        case '('                          => one(TokenKind.LeftParen, start)
        case ')'                          => one(TokenKind.RightParen, start)
        case ','                          => one(TokenKind.Comma, start)
        case ';'                          => one(TokenKind.Semicolon, start)
        case '*'                          => one(TokenKind.Star, start)
        case '+'                          => one(TokenKind.Plus, start)
        case '-'                          => one(TokenKind.Minus, start)
        case '/'                          => one(TokenKind.Slash, start)
        case '='                          => one(TokenKind.Equal, start)
        case '!' if peek(1).contains('=') => two(TokenKind.NotEqual, start)
        case '<' if peek(1).contains('=') => two(TokenKind.LessOrEqual, start)
        case '<' if peek(1).contains('>') => two(TokenKind.NotEqual, start)
        case '<'                          => one(TokenKind.Less, start)
        case '>' if peek(1).contains('=') =>
          two(TokenKind.GreaterOrEqual, start)
        case '>'   => one(TokenKind.Greater, start)
        case other => Left(LexError(s"unexpected character '$other'", start))

    private def number(start: SourcePosition): Either[LexError, Token] =
      val whole = takeWhile(_.isDigit)
      if offset < input.length && current == '.' && peek(1).exists(_.isDigit)
      then
        advance()
        val fraction = takeWhile(_.isDigit)
        Right(Token(TokenKind.Real(s"$whole.$fraction".toDouble), start))
      else
        whole.toLongOption
          .map(value => Token(TokenKind.Integer(value), start))
          .toRight(LexError("integer literal is out of 64-bit range", start))

    private def string(start: SourcePosition): Either[LexError, Token] =
      advance()
      val value = StringBuilder()
      var closed = false
      while offset < input.length && !closed do
        if current == '\'' && peek(1).contains('\'') then
          value += '\''; advance(); advance()
        else if current == '\'' then
          advance()
          closed = true
        else
          value += current
          advance()
      if closed then Right(Token(TokenKind.Text(value.result()), start))
      else Left(LexError("unterminated string literal", start))

    private def quotedIdentifier(
      start: SourcePosition
    ): Either[LexError, Token] =
      advance()
      val value = StringBuilder()
      var closed = false
      while offset < input.length && !closed do
        if current == '"' && peek(1).contains('"') then
          value += '"'; advance(); advance()
        else if current == '"' then
          advance()
          closed = true
        else
          value += current
          advance()
      if closed then Right(Token(TokenKind.Word(value.result()), start))
      else Left(LexError("unterminated quoted identifier", start))

    private def takeWhile(predicate: Char => Boolean): String =
      val start = offset
      while offset < input.length && predicate(current) do advance()
      input.substring(start, offset)

    private def one(kind: TokenKind, start: SourcePosition) =
      advance(); Right(Token(kind, start))

    private def two(kind: TokenKind, start: SourcePosition) =
      advance(); advance(); Right(Token(kind, start))

    private def current: Char = input.charAt(offset)
    private def peek(distance: Int): Option[Char] =
      input.lift(offset + distance)
    private def position = SourcePosition(offset, line, column)
    private def advance(): Unit =
      if current == '\n' then
        line += 1
        column = 1
      else column += 1
      offset += 1
