package learnsqlite.sql

final case class ParseError(message: String, position: SourcePosition):
  override def toString: String =
    s"$message at ${position.line}:${position.column}"

/** Recursive-descent parser for the documented SQL subset. */
object Parser:
  def parse(sql: String): Either[ParseError, Statement] =
    Lexer
      .tokenize(sql)
      .left
      .map(error => ParseError(error.message, error.position))
      .flatMap: tokens =>
        val parser = Implementation(tokens)
        parser
          .statement()
          .flatMap: value =>
            val _ = parser.accept(TokenKind.Semicolon)
            parser.expect(TokenKind.End, "end of input").map(_ => value)

  final private class Implementation(tokens: Vector[Token]):
    private var index = 0
    private def current = tokens(index)

    def statement(): Either[ParseError, Statement] =
      if keyword("CREATE") then createTable()
      else if keyword("INSERT") then insert()
      else if keyword("SELECT") then select()
      else if keyword("DELETE") then delete()
      else if keyword("UPDATE") then update()
      else fail("expected CREATE, INSERT, SELECT, DELETE, or UPDATE")

    private def createTable(): Either[ParseError, Statement] =
      for
        _ <- requireKeyword("TABLE")
        ifNotExists = optionalKeywords("IF", "NOT", "EXISTS")
        name <- identifier()
        _ <- expect(TokenKind.LeftParen, "'('")
        columns <- commaSeparated(columnDefinition())
        _ <- expect(TokenKind.RightParen, "')'")
      yield Statement.CreateTable(name, columns, ifNotExists)

    private def columnDefinition(): Either[ParseError, ColumnDefinition] =
      for
        name <- identifier()
        declaredType = current.kind match
          case TokenKind.Word(value)
              if !Set("PRIMARY", "NOT", "NULL").contains(upper(value)) =>
            index += 1; Some(value)
          case _ => None
        primary = optionalKeywords("PRIMARY", "KEY")
        notNull = optionalKeywords("NOT", "NULL")
      yield ColumnDefinition(name, declaredType, primary, !notNull)

    private def insert(): Either[ParseError, Statement] =
      for
        _ <- requireKeyword("INTO")
        table <- identifier()
        columns <-
          if accept(TokenKind.LeftParen) then
            commaSeparated(identifier()).flatMap(values =>
              expect(TokenKind.RightParen, "')'").map(_ => values)
            )
          else Right(Vector.empty)
        _ <- requireKeyword("VALUES")
        rows <- commaSeparated(parenthesizedExpressions())
      yield Statement.Insert(table, columns, rows)

    private def parenthesizedExpressions(): Either[ParseError, Vector[Expr]] =
      for
        _ <- expect(TokenKind.LeftParen, "'('")
        values <- commaSeparated(expression())
        _ <- expect(TokenKind.RightParen, "')'")
      yield values

    private def select(): Either[ParseError, Statement] =
      for
        projection <- commaSeparated(selectItem())
        _ <- requireKeyword("FROM")
        table <- identifier()
        predicate <- optionalWhere()
        ordering <- optionalOrderBy()
        limit <- optionalLimit()
      yield Statement.Select(projection, table, predicate, ordering, limit)

    private def optionalOrderBy(): Either[ParseError, Vector[OrderingTerm]] =
      if optionalKeywords("ORDER", "BY") then commaSeparated(orderingTerm())
      else Right(Vector.empty)

    private def orderingTerm(): Either[ParseError, OrderingTerm] =
      expression().map: expression =>
        val direction =
          if keyword("DESC") then SortDirection.Descending
          else
            val _ = keyword("ASC")
            SortDirection.Ascending
        OrderingTerm(expression, direction)

    private def optionalLimit(): Either[ParseError, Option[Int]] =
      if keyword("LIMIT") then
        current.kind match
          case TokenKind.Integer(value) if value >= 0 && value <= Int.MaxValue =>
            index += 1
            Right(Some(value.toInt))
          case _ => fail("expected a non-negative LIMIT integer")
      else Right(None)

    private def selectItem(): Either[ParseError, SelectItem] =
      if accept(TokenKind.Star) then Right(SelectItem.All)
      else
        expression().flatMap: expr =>
          val alias =
            if keyword("AS") then identifier().map(Some(_)) else Right(None)
          alias.map(SelectItem.Expression(expr, _))

    private def delete(): Either[ParseError, Statement] =
      for
        _ <- requireKeyword("FROM")
        table <- identifier()
        predicate <- optionalWhere()
      yield Statement.Delete(table, predicate)

    private def update(): Either[ParseError, Statement] =
      for
        table <- identifier()
        _ <- requireKeyword("SET")
        assignments <- commaSeparated(assignment())
        predicate <- optionalWhere()
      yield Statement.Update(table, assignments, predicate)

    private def assignment(): Either[ParseError, (Identifier, Expr)] =
      for
        column <- identifier()
        _ <- expect(TokenKind.Equal, "'='")
        value <- expression()
      yield column -> value

    private def optionalWhere(): Either[ParseError, Option[Expr]] =
      if keyword("WHERE") then expression().map(Some(_)) else Right(None)

    private def expression(): Either[ParseError, Expr] = or()
    private def or(): Either[ParseError, Expr] =
      chain(and(), Set("OR"), _ => BinaryOperator.Or)
    private def and(): Either[ParseError, Expr] =
      chain(comparison(), Set("AND"), _ => BinaryOperator.And)

    private def comparison(): Either[ParseError, Expr] =
      additive().flatMap: left =>
        val operator = current.kind match
          case TokenKind.Equal          => Some(BinaryOperator.Equal)
          case TokenKind.NotEqual       => Some(BinaryOperator.NotEqual)
          case TokenKind.Less           => Some(BinaryOperator.Less)
          case TokenKind.LessOrEqual    => Some(BinaryOperator.LessOrEqual)
          case TokenKind.Greater        => Some(BinaryOperator.Greater)
          case TokenKind.GreaterOrEqual => Some(BinaryOperator.GreaterOrEqual)
          case _                        => None
        operator match
          case None     => Right(left)
          case Some(op) => index += 1; additive().map(Expr.Binary(left, op, _))

    private def additive(): Either[ParseError, Expr] =
      binaryLoop(
        multiplicative(),
        Map(
          TokenKind.Plus -> BinaryOperator.Add,
          TokenKind.Minus -> BinaryOperator.Subtract
        ),
        () => multiplicative()
      )

    private def multiplicative(): Either[ParseError, Expr] =
      binaryLoop(
        unary(),
        Map(
          TokenKind.Star -> BinaryOperator.Multiply,
          TokenKind.Slash -> BinaryOperator.Divide
        ),
        () => unary()
      )

    private def unary(): Either[ParseError, Expr] =
      if accept(TokenKind.Minus) then
        unary().map(Expr.Unary(UnaryOperator.Negate, _))
      else if keyword("NOT") then unary().map(Expr.Unary(UnaryOperator.Not, _))
      else primary()

    private def primary(): Either[ParseError, Expr] = current.kind match
      case TokenKind.Integer(value) =>
        index += 1; Right(Expr.Value(Literal.Integer(value)))
      case TokenKind.Real(value) =>
        index += 1; Right(Expr.Value(Literal.Real(value)))
      case TokenKind.Text(value) =>
        index += 1; Right(Expr.Value(Literal.Text(value)))
      case TokenKind.Word(value) if upper(value) == "NULL" =>
        index += 1; Right(Expr.Value(Literal.Null))
      case TokenKind.Word(_) => identifier().map(Expr.Column(_))
      case TokenKind.LeftParen =>
        index += 1
        expression().flatMap(value =>
          expect(TokenKind.RightParen, "')'").map(_ => value)
        )
      case _ => fail("expected expression")

    private def binaryLoop(
      initial: Either[ParseError, Expr],
      operators: Map[TokenKind, BinaryOperator],
      next: () => Either[ParseError, Expr]
    ): Either[ParseError, Expr] =
      initial.flatMap: first =>
        var result: Either[ParseError, Expr] = Right(first)
        while operators.contains(current.kind) && result.isRight do
          val op = operators(current.kind); index += 1
          result = result.flatMap(left => next().map(Expr.Binary(left, op, _)))
        result

    private def chain(
      initial: Either[ParseError, Expr],
      words: Set[String],
      operator: String => BinaryOperator
    ): Either[ParseError, Expr] = initial.flatMap: first =>
      var result: Either[ParseError, Expr] = Right(first)
      while current.kind match
          case TokenKind.Word(value) => words.contains(upper(value))
          case _                     => false
      do
        val word = current.kind.asInstanceOf[TokenKind.Word].value
        index += 1
        result = result.flatMap(left =>
          comparison().map(Expr.Binary(left, operator(word), _))
        )
      result

    private def commaSeparated[A](
      first: => Either[ParseError, A]
    ): Either[ParseError, Vector[A]] =
      first.flatMap: head =>
        val values = Vector.newBuilder[A]; values += head
        var failure: Option[ParseError] = None
        while accept(TokenKind.Comma) && failure.isEmpty do
          first match
            case Right(value) => values += value
            case Left(error)  => failure = Some(error)
        failure.toLeft(values.result())

    private def identifier(): Either[ParseError, Identifier] =
      current.kind match
        case TokenKind.Word(value) => index += 1; Right(Identifier(value))
        case _                     => fail("expected identifier")

    private def requireKeyword(value: String): Either[ParseError, Unit] =
      if keyword(value) then Right(()) else fail(s"expected $value")

    private def optionalKeywords(values: String*): Boolean =
      val saved = index
      if values.forall(keyword) then true
      else
        index = saved
        false

    private def keyword(value: String): Boolean = current.kind match
      case TokenKind.Word(actual) if upper(actual) == value => index += 1; true
      case _                                                => false

    def accept(kind: TokenKind): Boolean =
      if current.kind == kind then
        index += 1
        true
      else false

    def expect(kind: TokenKind, description: String): Either[ParseError, Unit] =
      if accept(kind) then Right(()) else fail(s"expected $description")

    private def fail[A](message: String): Left[ParseError, A] = Left(
      ParseError(message, current.position)
    )
    private def upper(value: String) = value.toUpperCase(java.util.Locale.ROOT)
