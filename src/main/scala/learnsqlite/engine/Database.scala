package learnsqlite.engine

import learnsqlite.core.*
import learnsqlite.sql.*

sealed trait DatabaseError derives CanEqual:
  /** Stable diagnostic suitable for a CLI or API response. */
  def message: String
final case class SyntaxFailure(error: ParseError) extends DatabaseError:
  def message = error.toString
final case class ExecutionFailure(message: String) extends DatabaseError

enum Result:
  /** A table became available, including a successful `IF NOT EXISTS`. */
  case Created(table: String)

  /** Number of rows inserted or deleted. */
  case Modified(rows: Int)

  /** Ordered column labels and result rows. */
  case Query(columns: Vector[String], rows: Vector[Vector[Value]])

/**
 * Mutable database handle with immutable rows at its boundaries.
 *
 * Mutation is intentionally confined here. Parsing, schemas, values, and expression evaluation
 * remain pure and independently testable. Supplying a [[Backend]] selects memory or file durability
 * without changing SQL semantics.
 */
final class Database(private val backend: Backend = MemoryBackend())
    extends AutoCloseable:

  /** Parses and executes one SQL statement. */
  def execute(sql: String): Either[DatabaseError, Result] =
    Parser.parse(sql).left.map(SyntaxFailure.apply).flatMap(execute)

  /** Executes an already parsed statement. */
  def execute(statement: Statement): Either[DatabaseError, Result] =
    statement match
      case value: Statement.CreateTable => create(value)
      case value: Statement.Insert      => insert(value)
      case value: Statement.Select      => select(value)
      case value: Statement.Delete      => delete(value)
      case value: Statement.Update      => update(value)

  /** Forces preceding writes through the backend durability boundary. */
  def flush(): Either[DatabaseError, Unit] =
    backend.flush().left.map(ExecutionFailure.apply)

  /** Closes the backend owned by this handle. */
  override def close(): Unit = backend.close()

  private def create(statement: Statement.CreateTable) =
    backend.table(statement.name) match
      case Right(_) if statement.ifNotExists =>
        Right(Result.Created(statement.name.value))
      case Right(_) => failure(s"table already exists: ${statement.name.value}")
      case Left(_) =>
        backend
          .create(statement.name, statement.columns)
          .left
          .map(ExecutionFailure.apply)
          .map(_ => Result.Created(statement.name.value))

  private def insert(statement: Statement.Insert) =
    table(statement.table).flatMap: target =>
      val indexes =
        if statement.columns.isEmpty then
          Right(target.schema.columns.map(_.index))
        else
          traverse(statement.columns)(name =>
            target.schema
              .resolve(name)
              .map(_.index)
              .toRight(s"no such column: ${name.value}")
          )
      val prepared =
        for
          positions <- indexes
          rows <- traverse(statement.rows)(expressions =>
            prepareRow(target.schema, positions, expressions)
          )
        yield rows
      prepared.left
        .map(ExecutionFailure.apply)
        .flatMap: rows =>
          target
            .append(rows)
            .left
            .map(ExecutionFailure.apply)
            .map(_ => Result.Modified(rows.size))

  private def prepareRow(
    schema: Schema,
    positions: Vector[Int],
    expressions: Vector[Expr]
  ) =
    if positions.size != expressions.size then
      Left(s"expected ${positions.size} values, got ${expressions.size}")
    else
      traverse(expressions)(Evaluator.literal).flatMap: supplied =>
        val values = Array.fill[Value](schema.size)(Value.Null)
        positions
          .zip(supplied)
          .foreach((position, value) => values(position) = value)
        Row.checked(schema, values.toVector)

  private def select(statement: Statement.Select) =
    table(statement.from).flatMap: target =>
      val checked = statement.projection
        .collect { case SelectItem.Expression(expr, _) => expr }
        .foldLeft(Right(()): Either[String, Unit])((result, expr) =>
          result.flatMap(_ => validate(expr, target.schema))
        )
      val selected = checked
        .flatMap(_ => target.rows)
        .flatMap(
          _.filterEither(row => matches(statement.where, target.schema, row))
        )
      selected.left
        .map(ExecutionFailure.apply)
        .flatMap: rows =>
          val columns = projectionNames(statement.projection, target.schema)
          traverse(rows)(row =>
            project(statement.projection, target.schema, row)
          ).left
            .map(ExecutionFailure.apply)
            .map(Result.Query(columns, _))

  private def validate(expression: Expr, schema: Schema): Either[String, Unit] =
    expression match
      case Expr.Column(name) =>
        schema
          .resolve(name)
          .toRight(s"no such column: ${name.value}")
          .map(_ => ())
      case Expr.Unary(_, operand) => validate(operand, schema)
      case Expr.Binary(left, _, right) =>
        validate(left, schema).flatMap(_ => validate(right, schema))
      case Expr.Value(_) => Right(())

  private def delete(statement: Statement.Delete) =
    table(statement.table).flatMap: target =>
      target.rows.left
        .map(ExecutionFailure.apply)
        .flatMap: rows =>
          traverse(rows)(row =>
            matches(statement.where, target.schema, row).map(row -> _)
          ).left
            .map(ExecutionFailure.apply)
            .flatMap { decisions =>
              val deleted = decisions.count(_._2)
              target
                .replace(decisions.collect { case (row, false) => row })
                .left
                .map(ExecutionFailure.apply)
                .map(_ => Result.Modified(deleted))
            }

  private def update(statement: Statement.Update) =
    table(statement.table).flatMap: target =>
      val resolved = traverse(statement.assignments): (name, expression) =>
        target.schema
          .resolve(name)
          .toRight(s"no such column: ${name.value}")
          .flatMap(column => validate(expression, target.schema).map(_ => column -> expression))
      val duplicate =
        statement.assignments.map(_._1.normalized).groupBy(identity).exists(_._2.size > 1)
      if duplicate then failure("a column may only be assigned once")
      else
        resolved.left.map(ExecutionFailure.apply).flatMap: assignments =>
          target.rows.left.map(ExecutionFailure.apply).flatMap: rows =>
            traverse(rows): row =>
              matches(statement.where, target.schema, row).flatMap:
                case false => Right(row -> false)
                case true =>
                  traverse(assignments): (column, expression) =>
                    Evaluator(expression, target.schema, row).map(column -> _)
                  .flatMap: values =>
                    val updated = row.values.toArray
                    values.foreach((column, value) => updated(column.index) = value)
                    Row.checked(target.schema, updated.toVector).map(_ -> true)
            .left.map(ExecutionFailure.apply).flatMap: updates =>
              val changed = updates.count(_._2)
              target.replace(updates.map(_._1)).left
                .map(ExecutionFailure.apply).map(_ => Result.Modified(changed))

  private def matches(
    predicate: Option[Expr],
    schema: Schema,
    row: Row
  ): Either[String, Boolean] = predicate match
    case None => Right(true)
    case Some(expression) =>
      Evaluator(expression, schema, row)
        .flatMap(Evaluator.truth)
        .map(_ == Truth.True)

  private def project(
    items: Vector[SelectItem],
    schema: Schema,
    row: Row
  ): Either[String, Vector[Value]] =
    traverse(items):
      case SelectItem.All => Right(row.values)
      case SelectItem.Expression(expression, _) =>
        Evaluator(expression, schema, row).map(Vector(_))
    .map(_.flatten)

  private def projectionNames(
    items: Vector[SelectItem],
    schema: Schema
  ): Vector[String] = items.flatMap:
    case SelectItem.All => schema.columns.map(_.definition.name.value)
    case SelectItem.Expression(Expr.Column(name), alias) =>
      Vector(alias.getOrElse(name).value)
    case SelectItem.Expression(_, alias) =>
      Vector(alias.fold("expression")(_.value))

  private def table(name: Identifier): Either[DatabaseError, StoredTable] =
    backend.table(name).left.map(ExecutionFailure.apply)

  private def failure[A](message: String): Left[DatabaseError, A] = Left(
    ExecutionFailure(message)
  )

  private def traverse[A, B](values: Vector[A])(
    f: A => Either[String, B]
  ): Either[String, Vector[B]] =
    values.foldLeft(Right(Vector.empty): Either[String, Vector[B]]):
      case (result, value) => result.flatMap(acc => f(value).map(acc :+ _))

extension [A](values: Vector[A])
  private def filterEither(
    predicate: A => Either[String, Boolean]
  ): Either[String, Vector[A]] =
    values.foldLeft(Right(Vector.empty): Either[String, Vector[A]]):
      case (result, value) =>
        result.flatMap: acc =>
          predicate(value).map(if _ then acc :+ value else acc)
