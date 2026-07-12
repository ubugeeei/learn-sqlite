package learnsqlite.core

/**
 * SQLite's five storage classes.
 *
 * See [[https://www.sqlite.org/datatype3.html SQLite Datatypes]]. BLOB equality is content based
 * even though the underlying JVM array is mutable.
 */
enum Value:
  case Null
  case Integer(value: Long)
  case Real(value: Double)
  case Text(value: String)
  case Blob(bytes: Array[Byte])

  def render: String = this match
    case Null           => "NULL"
    case Integer(value) => value.toString
    case Real(value)    => value.toString
    case Text(value)    => value
    case Blob(bytes)    => bytes.map("%02x".format(_)).mkString("x'", "", "'")

object Value:
  def blob(bytes: Array[Byte]): Value = Blob(bytes.clone())

  /** Content-based key used by table constraints, including BLOB byte equality. */
  private[core] def constraintKey(value: Value): Any = value match
    case Blob(bytes) => ("blob", bytes.toVector)
    case other       => other

  /** SQLite storage-class ordering used by ORDER BY with binary text collation. */
  def compare(left: Value, right: Value): Int = (left, right) match
    case (Null, Null)              => 0
    case (Null, _)                 => -1
    case (_, Null)                 => 1
    case (Integer(a), Integer(b))  => a.compare(b)
    case (Integer(a), Real(b))     => a.toDouble.compare(b)
    case (Real(a), Integer(b))     => a.compare(b.toDouble)
    case (Real(a), Real(b))        => a.compare(b)
    case (Integer(_) | Real(_), _) => -1
    case (_, Integer(_) | Real(_)) => 1
    case (Text(a), Text(b))        => a.compareTo(b)
    case (Text(_), Blob(_))        => -1
    case (Blob(_), Text(_))        => 1
    case (Blob(a), Blob(b))        => compareBytes(a, b)

  private def compareBytes(left: Array[Byte], right: Array[Byte]): Int =
    val common = math.min(left.length, right.length)
    var index = 0
    while index < common do
      val comparison = (left(index) & 0xff).compare(right(index) & 0xff)
      if comparison != 0 then return comparison
      index += 1
    left.length.compare(right.length)

/** SQLite truth values include unknown, represented by NULL in SQL. */
enum Truth:
  case True, False, Unknown

  infix def and(other: => Truth): Truth = this match
    case False   => False
    case True    => other
    case Unknown => if other == False then False else Unknown

  infix def or(other: => Truth): Truth = this match
    case True    => True
    case False   => other
    case Unknown => if other == True then True else Unknown

  def unary_! : Truth = this match
    case True    => False
    case False   => True
    case Unknown => Unknown
