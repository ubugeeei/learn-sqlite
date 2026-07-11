package learnsqlite.core

/** SQLite's five storage classes.
  *
  * See [[https://www.sqlite.org/datatype3.html SQLite Datatypes]]. BLOB
  * equality is content based even though the underlying JVM array is mutable.
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
