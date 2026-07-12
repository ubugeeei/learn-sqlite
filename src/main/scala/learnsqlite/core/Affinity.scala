package learnsqlite.core

/**
 * SQLite's preferred storage class for a column.
 *
 * Affinity is a recommendation rather than a rigid type. Rules follow
 * [[https://www.sqlite.org/datatype3.html#type_affinity Type Affinity]] in their specified order.
 */
enum Affinity:
  case Integer, Text, Blob, Real, Numeric

  /** Applies this affinity before a value is stored in a table record. */
  def apply(value: Value): Value = (this, value) match
    case (_, Value.Null)          => Value.Null
    case (_, blob: Value.Blob)    => blob
    case (Blob, other)            => other
    case (Text, Value.Integer(n)) => Value.Text(n.toString)
    case (Text, Value.Real(n))    => Value.Text(renderReal(n))
    case (Text, text: Value.Text) => text
    case (Real, Value.Integer(n)) => Value.Real(n.toDouble)
    case (Real, real: Value.Real) => real
    case (Real, Value.Text(text)) =>
      numericText(text).fold[Value](Value.Text(text)):
        case Value.Integer(n) => Value.Real(n.toDouble)
        case converted        => converted
    case (Integer | Numeric, text @ Value.Text(raw)) => numericText(raw).getOrElse(text)
    case (Integer | Numeric, other)                  => other

  private def numericText(text: String): Option[Value] =
    val trimmed = text.trim
    if Affinity.IntegerLiteral.matches(trimmed) then trimmed.toLongOption.map(Value.Integer(_))
    else if Affinity.RealLiteral.matches(trimmed) then
      try
        val decimal = BigDecimal(trimmed)
        if decimal.isWhole && decimal.isValidLong then Some(Value.Integer(decimal.toLongExact))
        else Some(Value.Real(trimmed.toDouble))
      catch case _: NumberFormatException => None
    else None

  private def renderReal(value: Double): String =
    if value.isNaN || value.isInfinity then value.toString
    else BigDecimal.decimal(value).bigDecimal.stripTrailingZeros.toPlainString

object Affinity:
  private val IntegerLiteral = raw"[+-]?\d+".r
  private val RealLiteral = raw"[+-]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?".r

  /** Derives affinity from a declared type using SQLite's ordered substring rules. */
  def fromDeclaredType(declaredType: Option[String]): Affinity = declaredType match
    case None => Blob
    case Some(raw) =>
      val name = raw.toUpperCase(java.util.Locale.ROOT)
      if name.contains("INT") then Integer
      else if Vector("CHAR", "CLOB", "TEXT").exists(name.contains) then Text
      else if name.contains("BLOB") then Blob
      else if Vector("REAL", "FLOA", "DOUB").exists(name.contains) then Real
      else Numeric
