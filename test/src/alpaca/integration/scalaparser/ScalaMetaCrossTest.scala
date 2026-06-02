package alpaca
package integration.scalaparser

import org.scalatest.funsuite.AnyFunSuite

import scala.meta.*
import scala.meta.dialects.Scala3

/**
 * Cross-check: parse the same source with both ScalaParser (this project's
 * LALR implementation) and scala-meta (reference). Normalise each AST to a
 * common s-expression string and assert equality.
 *
 * The renderer is intentionally minimal — it only covers node shapes the
 * LALR parser can produce. Any input exercised here must be parseable by
 * both. Productions the LALR parser doesn't support (full match, lambdas,
 * typed patterns, ...) are simply absent from the renderer and their tests
 * live elsewhere.
 *
 * Simplifications worth knowing:
 *   - Modifiers other than `case` (on class/object) are NOT rendered. A
 *     `private val x = 1` renders identically to `val x = 1`. The LALR
 *     parser's ValDef/VarDef/DefDef/... nodes don't carry mods; they're
 *     stored in a separate `Modified` wrapper, which we unwrap.
 *   - TypeParam variance and bounds aren't rendered (LALR parser doesn't
 *     support them anyway).
 *   - Param default values aren't rendered in this pass.
 */
final class ScalaMetaCrossTest extends AnyFunSuite:

  private given Dialect = Scala3

  private def parseMine(src: String): ScalaTree =
    val (_, lexemes) = ScalaLexer.tokenize(src)
    val (_, result) = ScalaParser.parse(lexemes)
    result.nn

  private def parseMeta(src: String): Term = src.parse[Term].get

  // ---------- Renderer for Alpaca's ScalaTree ----------

  private def renderMine(t: ScalaTree): String = t match
    case ScalaTree.IntLit(v) => s"(Int $v)"
    case ScalaTree.FloatLit(v) => s"(Float $v)"
    case ScalaTree.BoolLit(v) => s"(Bool $v)"
    case ScalaTree.StringLit(v) => s"""(Str "$v")"""
    case ScalaTree.CharLit(v) => s"""(Char "$v")"""
    case ScalaTree.NullLit => "(Null)"
    case ScalaTree.Ident(n) => s"(Id $n)"
    case ScalaTree.Select(q, n) => s"(Select ${renderMine(q)} $n)"
    case ScalaTree.Apply(f, args) =>
      s"(Apply ${renderMine(f)} [${args.map(renderMine).mkString(",")}])"
    case ScalaTree.Prefix(op, e) => s"(Prefix $op ${renderMine(e)})"
    case ScalaTree.Infix(l, op, r) => s"(Infix ${renderMine(l)} $op ${renderMine(r)})"
    case ScalaTree.If(c, th, el) => s"(If ${renderMine(c)} ${renderMine(th)} ${renderMine(el)})"
    case ScalaTree.Block(ss, e) =>
      s"(Block [${ss.map(renderMine).mkString(",")}] ${renderMine(e)})"
    case ScalaTree.UnitBlock => "(Block [] (Unit))"

    case ScalaTree.ValDef(n, _, v) => s"(Val $n ${renderMine(v)})"
    case ScalaTree.VarDef(n, _, v) => s"(Var $n ${renderMine(v)})"
    case ScalaTree.DefDef(n, tp, ps, ret, body) =>
      s"(Def $n [${tp.mkString(",")}] [${ps.map(renderParam).mkString(",")}] ${renderType(ret)} ${renderMine(body)})"
    case ScalaTree.ClassDef(isCase, n, tp, ps, par, body) =>
      val prefix = if isCase then "CaseClass" else "Class"
      s"($prefix $n [${tp.mkString(",")}] [${ps.map(renderParam).mkString(",")}] [${par.mkString(",")}] ${renderMine(body)})"
    case ScalaTree.ObjectDef(isCase, n, par, body) =>
      val prefix = if isCase then "CaseObject" else "Object"
      s"($prefix $n [${par.mkString(",")}] ${renderMine(body)})"
    case ScalaTree.TraitDef(n, body) => s"(Trait $n ${renderMine(body)})"

    case ScalaTree.New(n, args) => s"(New $n [${args.map(renderMine).mkString(",")}])"
    case ScalaTree.Tuple(es) => s"(Tuple [${es.map(renderMine).mkString(",")}])"
    case ScalaTree.Import(path) => s"(Import ${path.mkString(".")})"

    // Unwrap: modifiers aren't rendered, so Modified(m, inner) ≡ inner.
    case ScalaTree.Modified(_, inner) => renderMine(inner)

    case other => s"(UNSUPPORTED ${other.getClass.getSimpleName})"

  private def renderParam(p: Param): String = s"(Param ${p.name} ${renderType(p.tpe)})"

  private def renderType(t: ScalaType): String = t match
    case ScalaType.TypeRef(n) => s"(TypeRef $n)"
    case ScalaType.AppliedType(b, args) =>
      s"(AppliedType $b [${args.map(renderType).mkString(",")}])"

  // ---------- Renderer for scala-meta's Tree ----------
  //
  // Uses method-based access on traits (fun, args, lhs, op, ...) so the
  // renderer is robust to the versioned `After_X_Y_0` / `Initial` case
  // classes scala-meta introduces as the AST evolves.

  private def renderMeta(t: Tree): String = t match
    case Lit.Int(v) => s"(Int $v)"
    case Lit.Long(v) => s"(Int $v)" // Alpaca stores all ints as Long
    case Lit.Double(v) => s"(Float $v)"
    case Lit.Float(v) => s"(Float ${v.toDouble})"
    case Lit.Boolean(v) => s"(Bool $v)"
    case Lit.String(v) => s"""(Str "$v")"""
    case Lit.Char(v) => s"""(Char "$v")"""
    case Lit.Null() => "(Null)"

    case n: Term.Name => s"(Id ${n.value})"
    case s: Term.Select => s"(Select ${renderMeta(s.qual)} ${s.name.value})"
    case a: Term.Apply =>
      s"(Apply ${renderMeta(a.fun)} [${a.args.map(renderMeta).mkString(",")}])"
    case i: Term.ApplyInfix =>
      val args = i.args
      require(args.length == 1, s"Alpaca only supports single-arg infix; got $args")
      s"(Infix ${renderMeta(i.lhs)} ${i.op.value} ${renderMeta(args.head)})"
    case u: Term.ApplyUnary => s"(Prefix ${u.op.value} ${renderMeta(u.arg)})"
    case ifTree: Term.If =>
      s"(If ${renderMeta(ifTree.cond)} ${renderMeta(ifTree.thenp)} ${renderMeta(ifTree.elsep)})"

    case b: Term.Block =>
      b.stats.lastOption match
        case Some(last: Term) if b.stats.size > 1 =>
          s"(Block [${b.stats.init.map(renderMeta).mkString(",")}] ${renderMeta(last)})"
        case Some(last: Term) => s"(Block [] ${renderMeta(last)})"
        case _ => s"(Block [${b.stats.map(renderMeta).mkString(",")}] (Unit))"

    case v: Defn.Val =>
      val name = v.pats match
        case List(Pat.Var(Term.Name(n))) => n
        case other => sys.error(s"unexpected Defn.Val pats: $other")
      s"(Val $name ${renderMeta(v.rhs)})"
    case v: Defn.Var =>
      val name = v.pats match
        case List(Pat.Var(Term.Name(n))) => n
        case other => sys.error(s"unexpected Defn.Var pats: $other")
      // Defn.Var.body: Term (concrete var always has a RHS)
      s"(Var $name ${renderMeta(v.body)})"

    case d: Defn.Def =>
      val tparams = d.tparams.map(tp => tp.name.value).mkString(",")
      // paramss: List[List[Term.Param]] — join all clauses flat for Alpaca
      // (Alpaca's LALR parser accepts at most 1 clause)
      val ps = d.paramss.flatten.map(renderMetaParam).mkString(",")
      val ret = d.decltpe match
        case Some(tpe) => renderMetaType(tpe)
        case None => sys.error(s"Alpaca requires a return type on every def; got none")
      s"(Def ${d.name.value} [$tparams] [$ps] $ret ${renderMeta(d.body)})"

    case c: Defn.Class =>
      val prefix = if c.mods.exists(_.isInstanceOf[Mod.Case]) then "CaseClass" else "Class"
      val tparams = c.tparams.map(_.name.value).mkString(",")
      val ctorParams = c.ctor.paramss.flatten.map(renderMetaParam).mkString(",")
      val parents = c.templ.inits.map(i => renderInitName(i.tpe)).mkString(",")
      s"($prefix ${c.name.value} [$tparams] [$ctorParams] [$parents] ${renderTemplateBody(c.templ)})"
    case o: Defn.Object =>
      val prefix = if o.mods.exists(_.isInstanceOf[Mod.Case]) then "CaseObject" else "Object"
      val parents = o.templ.inits.map(i => renderInitName(i.tpe)).mkString(",")
      s"($prefix ${o.name.value} [$parents] ${renderTemplateBody(o.templ)})"
    case tr: Defn.Trait =>
      s"(Trait ${tr.name.value} ${renderTemplateBody(tr.templ)})"

    case n: Term.New =>
      val init = n.init
      val name = renderInitName(init.tpe)
      val args = init.argss.flatten.map(renderMeta).mkString(",")
      s"(New $name [$args])"

    case t: Term.Tuple => s"(Tuple [${t.args.map(renderMeta).mkString(",")}])"

    case other => s"(UNSUPPORTED ${other.productPrefix})"

  private def renderMetaParam(p: Term.Param): String =
    val ty = p.decltpe match
      case Some(tpe) => renderMetaType(tpe)
      case None => sys.error(s"Alpaca params require a type annotation; got ${p.name.value}")
    s"(Param ${p.name.value} $ty)"

  private def renderMetaType(t: Type): String = t match
    case n: Type.Name => s"(TypeRef ${n.value})"
    case a: Type.Apply =>
      val base = a.tpe match
        case n: Type.Name => n.value
        case other => sys.error(s"unsupported AppliedType base: $other")
      s"(AppliedType $base [${a.args.map(renderMetaType).mkString(",")}])"
    case other => s"(UNSUPPORTED_TYPE ${other.productPrefix})"

  /** Init.tpe for `extends Foo` parses to Type.Name("Foo"); render the simple name. */
  private def renderInitName(t: Type): String = t match
    case n: Type.Name => n.value
    case other => sys.error(s"unsupported parent type: $other")

  /** Extract the stat list from a Template body and render as Alpaca Block. */
  private def renderTemplateBody(templ: Template): String =
    val stats = templ.body.stats
    if stats.isEmpty then "(Block [] (Unit))"
    else
      stats.lastOption match
        case Some(last: Term) if stats.size > 1 =>
          s"(Block [${stats.init.map(renderMeta).mkString(",")}] ${renderMeta(last)})"
        case Some(last: Term) => s"(Block [] ${renderMeta(last)})"
        case _ =>
          // No trailing Term — e.g. a class body that's all defs. Alpaca
          // requires a trailing Expr so this won't round-trip, but render
          // consistently anyway.
          s"(Block [${stats.map(renderMeta).mkString(",")}] (Unit))"

  // ---------- Helper ----------

  private def cross(input: String): Unit =
    val mine = renderMine(parseMine(input))
    val meta = renderMeta(parseMeta(input))
    assert(mine == meta, s"\nInput:      $input\nAlpaca:     $mine\nScala-meta: $meta")

  // ---------- Tests ----------

  // Literals / identifiers
  test("cross: integer literal")(cross("42"))
  test("cross: float literal")(cross("3.14"))
  test("cross: boolean true")(cross("true"))
  test("cross: boolean false")(cross("false"))
  test("cross: null")(cross("null"))
  test("cross: string literal")(cross(""""hello""""))
  test("cross: identifier")(cross("x"))

  // Arithmetic + precedence
  test("cross: addition")(cross("1 + 2"))
  test("cross: subtraction")(cross("10 - 3"))
  test("cross: multiplication")(cross("4 * 5"))
  test("cross: precedence: mul before add")(cross("1 + 2 * 3"))
  test("cross: left-assoc addition")(cross("1 + 2 + 3"))
  test("cross: mixed arithmetic")(cross("2 * 3 + 4 * 5"))

  // Comparison / logical
  test("cross: comparison")(cross("a < b"))
  test("cross: equality")(cross("x == y"))
  test("cross: arithmetic before comparison")(cross("a + b < c"))
  test("cross: logical and")(cross("a && b"))
  test("cross: logical or")(cross("a || b"))
  test("cross: and before or")(cross("a || b && c"))

  // Unary
  test("cross: unary minus")(cross("-x"))
  test("cross: logical not")(cross("!flag"))
  test("cross: unary minus precedence")(cross("-x * y"))

  // Select / apply
  test("cross: field selection")(cross("obj.field"))
  test("cross: chained selection")(cross("a.b.c"))
  test("cross: function call no args")(cross("f()"))
  test("cross: function call one arg")(cross("f(x)"))
  test("cross: function call many args")(cross("f(x, y, z)"))
  test("cross: method call")(cross("obj.method(x)"))
  test("cross: chained method calls")(cross("a.b().c()"))

  // If-else / block
  test("cross: if-else")(cross("if (x) a else b"))
  test("cross: if-else arithmetic")(cross("if (x > 0) x else -x"))
  test("cross: block single expr")(cross("{ 42 }"))
  test("cross: block val + expr")(cross("{ val x = 1; x }"))
  test("cross: block val with complex rhs")(cross("{ val y = a + b * c; y }"))

  // Var definitions
  test("cross: var")(cross("{ var x = 1; x }"))
  test("cross: val + var mixed")(cross("{ val x = 1; var y = 2; x + y }"))

  // Def definitions (simplified — single param clause, required return type)
  test("cross: def no params")(cross("{ def zero(): Int = 0; zero() }"))
  test("cross: def with param")(cross("{ def inc(x: Int): Int = x + 1; inc(41) }"))
  test("cross: def with multi params")(cross("{ def add(x: Int, y: Int): Int = x + y; add(1, 2) }"))
  test("cross: def with applied return type")(cross("{ def empty(): List[Int] = xs; empty() }"))
  test("cross: recursive factorial def") {
    cross("{ def fact(n: Int): Int = if (n <= 0) 1 else n * fact(n - 1); fact(5) }")
  }

  // Class definitions
  test("cross: empty class")(cross("{ class Empty {}; null }"))
  test("cross: class with constructor params") {
    cross("{ class Point(x: Int, y: Int) {}; null }")
  }
  test("cross: class extending parent")(cross("{ class Dog extends Animal {}; null }"))
  test("cross: class with params and parent") {
    cross("{ class Cat(name: String) extends Animal {}; null }")
  }
  test("cross: class with multiple parents") {
    cross("{ class Hybrid extends A with B with C {}; null }")
  }

  // Case class / case object
  test("cross: case class")(cross("{ case class Unit {}; null }"))
  test("cross: case class with params") {
    cross("{ case class Point(x: Int, y: Int) {}; null }")
  }
  test("cross: case object")(cross("{ case object None {}; null }"))

  // Object / trait
  test("cross: empty object")(cross("{ object Singleton {}; null }"))
  test("cross: object extending trait") {
    cross("{ object Bark extends Animal {}; null }")
  }
  test("cross: empty trait")(cross("{ trait Animal {}; null }"))

  // New
  test("cross: new with bare class name")(cross("new Foo"))

  // Tuples
  test("cross: pair")(cross("(1, 2)"))
  test("cross: triple")(cross("(1, 2, 3)"))
  test("cross: mixed-type tuple")(cross("""(42, "hi", true)"""))
  test("cross: nested tuples")(cross("((1, 2), (3, 4))"))

  // Modifiers (rendered identically — scala-meta includes them, Alpaca puts
  // them in a separate Modified wrapper we unwrap, so the structural cores
  // must still match).
  test("cross: private val")(cross("{ private val x = 1; x }"))
  test("cross: lazy val")(cross("{ lazy val xs = expensive(); xs }"))
  test("cross: sealed abstract class") {
    cross("{ sealed abstract class Tree {}; null }")
  }
  test("cross: implicit def") {
    cross("{ implicit def intToStr(x: Int): String = s; 0 }")
  }
