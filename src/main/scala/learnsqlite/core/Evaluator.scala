package learnsqlite.core

import learnsqlite.sql.*

/** Evaluates scalar expressions using SQLite-like numeric and NULL semantics.
  *
  * See
  * [[https://www.sqlite.org/lang_expr.html Operators and Parse-Affecting Attributes]].
  */
object Evaluator:
  def apply(expression: Expr, schema: Schema, row: Row): Either[String, Value] =
    expression match
      case Expr.Value(literal) => Right(fromLiteral(literal))
      case Expr.Column(name)   =>
        schema
          .resolve(name)
          .map(row(_))
          .toRight(s"no such column: ${name.value}")
      case Expr.Unary(op, operand) =>
        apply(operand, schema, row).flatMap(unary(op, _))
      case Expr.Binary(left, op, right) =>
        for
          lhs <- apply(left, schema, row)
          rhs <- apply(right, schema, row)
          result <- binary(lhs, op, rhs)
        yield result

  def literal(expression: Expr): Either[String, Value] = expression match
    case Expr.Value(value)                         => Right(fromLiteral(value))
    case Expr.Unary(UnaryOperator.Negate, operand) =>
      literal(operand).flatMap(unary(UnaryOperator.Negate, _))
    case _ => Left("INSERT values must be constant literals")

  def truth(value: Value): Either[String, Truth] = value match
    case Value.Null       => Right(Truth.Unknown)
    case Value.Integer(0) => Right(Truth.False)
    case Value.Integer(_) => Right(Truth.True)
    case Value.Real(0.0)  => Right(Truth.False)
    case Value.Real(_)    => Right(Truth.True)
    case other            => Left(s"${other.render} is not a boolean value")

  private def unary(
      operator: UnaryOperator,
      value: Value
  ): Either[String, Value] = operator match
    case UnaryOperator.Not    => truth(value).map(result => fromTruth(!result))
    case UnaryOperator.Negate =>
      value match
        case Value.Null           => Right(Value.Null)
        case Value.Integer(value) => Right(Value.Integer(-value))
        case Value.Real(value)    => Right(Value.Real(-value))
        case _                    => Left("unary minus requires a number")

  private def binary(
      left: Value,
      operator: BinaryOperator,
      right: Value
  ): Either[String, Value] =
    operator match
      case BinaryOperator.And            => boolean(left, right, _ and _)
      case BinaryOperator.Or             => boolean(left, right, _ or _)
      case BinaryOperator.Equal          => compare(left, right, _ == 0)
      case BinaryOperator.NotEqual       => compare(left, right, _ != 0)
      case BinaryOperator.Less           => compare(left, right, _ < 0)
      case BinaryOperator.LessOrEqual    => compare(left, right, _ <= 0)
      case BinaryOperator.Greater        => compare(left, right, _ > 0)
      case BinaryOperator.GreaterOrEqual => compare(left, right, _ >= 0)
      case BinaryOperator.Add            => numeric(left, right, _ + _, _ + _)
      case BinaryOperator.Subtract       => numeric(left, right, _ - _, _ - _)
      case BinaryOperator.Multiply       => numeric(left, right, _ * _, _ * _)
      case BinaryOperator.Divide         =>
        (left, right) match
          case (_, Value.Integer(0) | Value.Real(0.0)) => Right(Value.Null)
          case _ => numeric(left, right, _ / _, _ / _)

  private def boolean(left: Value, right: Value, f: (Truth, Truth) => Truth) =
    for lhs <- truth(left); rhs <- truth(right) yield fromTruth(f(lhs, rhs))

  private def numeric(
      left: Value,
      right: Value,
      ints: (Long, Long) => Long,
      reals: (Double, Double) => Double
  ) =
    (left, right) match
      case (Value.Null, _) | (_, Value.Null)    => Right(Value.Null)
      case (Value.Integer(a), Value.Integer(b)) =>
        Right(Value.Integer(ints(a, b)))
      case (Value.Integer(a), Value.Real(b)) =>
        Right(Value.Real(reals(a.toDouble, b)))
      case (Value.Real(a), Value.Integer(b)) =>
        Right(Value.Real(reals(a, b.toDouble)))
      case (Value.Real(a), Value.Real(b)) => Right(Value.Real(reals(a, b)))
      case _ => Left("arithmetic requires numeric operands")

  private def compare(
      left: Value,
      right: Value,
      predicate: Int => Boolean
  ): Either[String, Value] =
    if left == Value.Null || right == Value.Null then Right(Value.Null)
    else
      val result = (left, right) match
        case (Value.Integer(a), Value.Integer(b)) => Some(a.compare(b))
        case (Value.Integer(a), Value.Real(b))    => Some(a.toDouble.compare(b))
        case (Value.Real(a), Value.Integer(b))    => Some(a.compare(b.toDouble))
        case (Value.Real(a), Value.Real(b))       => Some(a.compare(b))
        case (Value.Text(a), Value.Text(b))       => Some(a.compareTo(b))
        case _                                    => None
      result
        .map(value =>
          fromTruth(if predicate(value) then Truth.True else Truth.False)
        )
        .toRight("values are not comparable")

  private def fromTruth(value: Truth): Value = value match
    case Truth.True    => Value.Integer(1)
    case Truth.False   => Value.Integer(0)
    case Truth.Unknown => Value.Null

  private def fromLiteral(value: Literal): Value = value match
    case Literal.Null           => Value.Null
    case Literal.Integer(value) => Value.Integer(value)
    case Literal.Real(value)    => Value.Real(value)
    case Literal.Text(value)    => Value.Text(value)
