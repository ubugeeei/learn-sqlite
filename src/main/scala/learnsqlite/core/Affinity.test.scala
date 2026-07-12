package learnsqlite.core

class AffinitySuite extends munit.FunSuite:
  private val declaredTypes = Vector(
    None -> Affinity.Blob,
    Some("INT") -> Affinity.Integer,
    Some("VARCHAR(20)") -> Affinity.Text,
    Some("CLOB") -> Affinity.Text,
    Some("BLOB") -> Affinity.Blob,
    Some("DOUBLE PRECISION") -> Affinity.Real,
    Some("FLOAT") -> Affinity.Real,
    Some("BOOLEAN") -> Affinity.Numeric,
    Some("DECIMAL(10,2)") -> Affinity.Numeric,
    Some("CHARINT") -> Affinity.Integer
  )

  declaredTypes.foreach: (declared, expected) =>
    test(s"derive $expected affinity from ${declared.getOrElse("no declared type")}"):
      assertEquals(Affinity.fromDeclaredType(declared), expected)

  private val conversions = Vector(
    (Affinity.Text, Value.Integer(500), Value.Text("500")),
    (Affinity.Text, Value.Real(500.5), Value.Text("500.5")),
    (Affinity.Numeric, Value.Text("500"), Value.Integer(500)),
    (Affinity.Numeric, Value.Text("500.0"), Value.Integer(500)),
    (Affinity.Numeric, Value.Text("3.0e+5"), Value.Integer(300_000)),
    (Affinity.Numeric, Value.Text("500.25"), Value.Real(500.25)),
    (Affinity.Integer, Value.Text("42"), Value.Integer(42)),
    (Affinity.Real, Value.Text("42"), Value.Real(42.0)),
    (Affinity.Real, Value.Integer(42), Value.Real(42.0)),
    (Affinity.Blob, Value.Text("42"), Value.Text("42")),
    (Affinity.Integer, Value.Text("not-a-number"), Value.Text("not-a-number")),
    (Affinity.Integer, Value.Text("0x10"), Value.Text("0x10")),
    (Affinity.Numeric, Value.Null, Value.Null)
  )

  conversions.foreach: (affinity, input, expected) =>
    test(s"$affinity affinity converts ${input.render} to ${expected.render}"):
      assertEquals(affinity(input), expected)
