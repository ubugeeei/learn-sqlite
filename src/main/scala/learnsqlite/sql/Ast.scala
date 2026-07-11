package learnsqlite.sql

/** A case-insensitive SQL identifier preserving its spelling.
  *
  * SQLite's identifier rules are documented in the
  * [[https://www.sqlite.org/lang_keywords.html keyword reference]].
  */
final case class Identifier(value: String):
  require(value.nonEmpty, "an identifier cannot be empty")
  def normalized: String = value.toLowerCase(java.util.Locale.ROOT)

/** Literal values accepted by the SQL parser.
  *
  * The runtime representation is separate; this AST records source intent.
  * See [[https://www.sqlite.org/lang_expr.html literal-value]].
  */
enum Literal:
  case Null
  case Integer(value: Long)
  case Real(value: Double)
  case Text(value: String)

/** An SQL expression supported by the first execution milestone. */
enum Expr:
  case Value(value: Literal)
  case Column(name: Identifier)
  case Unary(operator: UnaryOperator, operand: Expr)
  case Binary(left: Expr, operator: BinaryOperator, right: Expr)

enum UnaryOperator:
  case Negate, Not

enum BinaryOperator:
  case Or, And, Equal, NotEqual, Less, LessOrEqual, Greater, GreaterOrEqual
  case Add, Subtract, Multiply, Divide

/** A declared column. SQLite type names determine affinity rather than a fixed
  * type; see [[https://www.sqlite.org/datatype3.html#determination_of_column_affinity]].
  */
final case class ColumnDefinition(
    name: Identifier,
    declaredType: Option[String],
    primaryKey: Boolean,
    nullable: Boolean
)

enum SelectItem:
  case All
  case Expression(expression: Expr, alias: Option[Identifier])

/** The supported SQL statement subset.
  *
  * Grammar links:
  *   - [[https://www.sqlite.org/lang_createtable.html CREATE TABLE]]
  *   - [[https://www.sqlite.org/lang_insert.html INSERT]]
  *   - [[https://www.sqlite.org/lang_select.html SELECT]]
  *   - [[https://www.sqlite.org/lang_delete.html DELETE]]
  */
enum Statement:
  case CreateTable(
      name: Identifier,
      columns: Vector[ColumnDefinition],
      ifNotExists: Boolean
  )
  case Insert(
      table: Identifier,
      columns: Vector[Identifier],
      rows: Vector[Vector[Expr]]
  )
  case Select(
      projection: Vector[SelectItem],
      from: Identifier,
      where: Option[Expr]
  )
  case Delete(table: Identifier, where: Option[Expr])

