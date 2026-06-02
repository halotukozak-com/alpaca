package alpaca
package integration.scalaparser

/**
 * Parser for a subset of Scala expressions.
 *
 * Implements productions from the Scala 3 specification
 * (https://docs.scala-lang.org/scala3/reference/syntax.html) restricted to
 * classic brace syntax (no significant indentation, no indent/outdent). See
 * [[ScalaAST]] for the list of productions covered vs. intentionally dropped.
 *
 * Operator precedence (from lowest to highest, matching Scala 3 spec):
 *   ||  (logical or)
 *   &&  (logical and)
 *   ==  !=  (equality)
 *   <  >  <=  >=  (relational)
 *   +  -  (additive)
 *   *  /  %  (multiplicative)
 *   -  !  (prefix / unary)
 *   .  ()  (select / apply, highest)
 */
object ScalaParser extends Parser:

  // =========================================================
  // Main expression rule
  // =========================================================

  val Expr: Rule[ScalaTree] = rule(
    // ---- InfixExpr: arithmetic ----
    "plus" { case (Expr(l), ScalaLexer.`\\+`(_), Expr(r)) => ScalaTree.Infix(l, "+", r) },
    "minus" { case (Expr(l), ScalaLexer.`-`(_), Expr(r)) => ScalaTree.Infix(l, "-", r) },
    "times" { case (Expr(l), ScalaLexer.`\\*`(_), Expr(r)) => ScalaTree.Infix(l, "*", r) },
    "divide" { case (Expr(l), ScalaLexer.`/`(_), Expr(r)) => ScalaTree.Infix(l, "/", r) },
    "mod" { case (Expr(l), ScalaLexer.`%`(_), Expr(r)) => ScalaTree.Infix(l, "%", r) },

    // ---- InfixExpr: comparison ----
    "eq" { case (Expr(l), ScalaLexer.eqeq(_), Expr(r)) => ScalaTree.Infix(l, "==", r) },
    "neq" { case (Expr(l), ScalaLexer.neq(_), Expr(r)) => ScalaTree.Infix(l, "!=", r) },
    "lt" { case (Expr(l), ScalaLexer.`<`(_), Expr(r)) => ScalaTree.Infix(l, "<", r) },
    "gt" { case (Expr(l), ScalaLexer.`>`(_), Expr(r)) => ScalaTree.Infix(l, ">", r) },
    "lte" { case (Expr(l), ScalaLexer.lte(_), Expr(r)) => ScalaTree.Infix(l, "<=", r) },
    "gte" { case (Expr(l), ScalaLexer.gte(_), Expr(r)) => ScalaTree.Infix(l, ">=", r) },

    // ---- InfixExpr: logical ----
    "and" { case (Expr(l), ScalaLexer.and(_), Expr(r)) => ScalaTree.Infix(l, "&&", r) },
    "or" { case (Expr(l), ScalaLexer.or(_), Expr(r)) => ScalaTree.Infix(l, "||", r) },

    // ---- PrefixExpr: unary operators ----
    "uplus" { case (ScalaLexer.`\\+`(_), Expr(e)) => ScalaTree.Prefix("+", e) },
    "uminus" { case (ScalaLexer.`-`(_), Expr(e)) => ScalaTree.Prefix("-", e) },
    "unot" { case (ScalaLexer.`!`(_), Expr(e)) => ScalaTree.Prefix("!", e) },

    // ---- SimpleExpr: field selection (Scala spec: SimpleExpr '.' id) ----
    "select" { case (Expr(q), ScalaLexer.`\\.`(_), ScalaLexer.id(n)) =>
      ScalaTree.Select(q, n.value)
    },

    // ---- SimpleExpr: function application (Scala spec: SimpleExpr ArgumentExprs) ----
    "apply" { case (Expr(f), ScalaLexer.`\\(`(_), Args(args), ScalaLexer.`\\)`(_)) =>
      ScalaTree.Apply(f, args)
    },
    "applyEmpty" { case (Expr(f), ScalaLexer.`\\(`(_), ScalaLexer.`\\)`(_)) =>
      ScalaTree.Apply(f, Nil)
    },

    // ---- SimpleExpr: parenthesised expression ----
    { case (ScalaLexer.`\\(`(_), Expr(e), ScalaLexer.`\\)`(_)) => e },

    // ---- Tuple literal (Scala spec: '(' ExprsInParens ')' with 2+ exprs) ----
    "tuple" { case (ScalaLexer.`\\(`(_), Expr(first), ScalaLexer.`,`(_), Args(rest), ScalaLexer.`\\)`(_)) =>
      ScalaTree.Tuple(first :: rest)
    },

    // ---- SimpleExpr: 'new' id (Scala spec: 'new' ConstrApp, simplified, no args) ----
    { case (ScalaLexer.`new`(_), ScalaLexer.id(n)) => ScalaTree.New(n.value, Nil) },

    // ---- Expr1: if-then-else (Scala spec: 'if' '(' Expr ')' Expr 'else' Expr) ----
    "ifelse" {
      case (
            ScalaLexer.`if`(_),
            ScalaLexer.`\\(`(_),
            Expr(cond),
            ScalaLexer.`\\)`(_),
            Expr(thn),
            ScalaLexer.`else`(_),
            Expr(els),
          ) =>
        ScalaTree.If(cond, thn, els)
    },

    // ---- Expr1: while loop (Scala spec: 'while' '(' Expr ')' Expr) ----
    "whileloop" { case (ScalaLexer.`while`(_), ScalaLexer.`\\(`(_), Expr(cond), ScalaLexer.`\\)`(_), Expr(body)) =>
      ScalaTree.While(cond, body)
    },

    // ---- Expr1: throw / return (Scala spec) ----
    "throw" { case (ScalaLexer.`throw`(_), Expr(e)) => ScalaTree.Throw(e) },
    "ret" { case (ScalaLexer.`return`(_), Expr(e)) => ScalaTree.Return(e) },

    // ---- BlockExpr (Scala spec: BlockExpr ::= '{' (CaseClauses | Block) '}') ----
    // Split into three rules so `{ case ... }` (PartialFun) is unambiguous with
    // `{ stats; expr }` at the opening `{` — otherwise an ε-reduction on a
    // `BlockStat*` kleene-star conflicts with shifting `case`.
    { case (ScalaLexer.`\\{`(_), ScalaLexer.`\\}`(_)) => ScalaTree.UnitBlock },
    { case (ScalaLexer.`\\{`(_), Expr(e), ScalaLexer.`\\}`(_)) => ScalaTree.Block(Nil, e) },
    { case (ScalaLexer.`\\{`(_), BlockStats(ss), Expr(e), ScalaLexer.`\\}`(_)) =>
      ScalaTree.Block(ss, e)
    },
    // Partial function literal: `{ case p1 => e1; case p2 => e2 }`
    // Uses CaseClauses (non-empty list) so `{}` stays unambiguously a UnitBlock.
    "pfun" { case (ScalaLexer.`\\{`(_), CaseClauses(cs), ScalaLexer.`\\}`(_)) =>
      ScalaTree.PartialFun(cs)
    },

    // ---- Literals ----
    { case ScalaLexer.intLit(n) => ScalaTree.IntLit(n.value) },
    { case ScalaLexer.floatLit(f) => ScalaTree.FloatLit(f.value) },
    { case ScalaLexer.`true`(_) => ScalaTree.BoolLit(true) },
    { case ScalaLexer.`false`(_) => ScalaTree.BoolLit(false) },
    { case ScalaLexer.`null`(_) => ScalaTree.NullLit },
    { case ScalaLexer.charLit(c) => ScalaTree.CharLit(c.value) },
    { case ScalaLexer.stringLit(s) => ScalaTree.StringLit(s.value) },
    { case ScalaLexer.interpStr(s) => ScalaTree.InterpolatedStr(s.value) },

    // ---- SimpleRef: identifier ----
    { case ScalaLexer.id(n) => ScalaTree.Ident(n.value) },
  )

  // =========================================================
  // Arguments list for function application
  // =========================================================

  val Args: Rule[List[ScalaTree]] = rule(
    { case Expr(e) => List(e) },
    { case (Args(as), ScalaLexer.`,`(_), Expr(e)) => as :+ e },
  )

  // =========================================================
  // Block statements (Scala spec: BlockStat)
  //
  //   BlockStat ::= Def ';' | Expr ';'
  //   Def       ::= ValDef | VarDef | DefDef | ClassDef
  // =========================================================

  val BlockStat: Rule[ScalaTree] = rule(
    { case (ValDef(v), ScalaLexer.`;`(_)) => v },
    { case (VarDef(v), ScalaLexer.`;`(_)) => v },
    { case (DefDef(d), ScalaLexer.`;`(_)) => d },
    { case (ClassDef(c), ScalaLexer.`;`(_)) => c },
    { case (ObjectDef(o), ScalaLexer.`;`(_)) => o },
    { case (TraitDef(t), ScalaLexer.`;`(_)) => t },
    { case (ModifiedDef(m), ScalaLexer.`;`(_)) => m },
    { case (ImportDef(i), ScalaLexer.`;`(_)) => i },
    { case (Expr(e), ScalaLexer.`;`(_)) => e },
  )

  // =========================================================
  // Import (Scala 3 spec: BlockStat ::= Import — simplified)
  //
  //   ImportDef ::= 'import' id {'.' id}
  //
  // No selectors: `import foo.{bar, baz}` and `import foo._` not supported.
  // =========================================================

  val ImportDef: Rule[ScalaTree] = rule:
    case (ScalaLexer.`import`(_), ImportPath(p)) => ScalaTree.Import(p)

  val ImportPath: Rule[List[String]] = rule(
    { case ScalaLexer.id(n) => List(n.value) },
    { case (ImportPath(p), ScalaLexer.`\\.`(_), ScalaLexer.id(n)) => p :+ n.value },
  )

  // =========================================================
  // Modifiers (Scala 3 spec: Modifier — simplified; no access qualifiers
  // like `private[this]`, no `inline`, no `transparent`, no annotations).
  //
  //   Modifier  ::= 'private' | 'protected' | 'final' | 'sealed'
  //              |  'abstract' | 'override' | 'lazy' | 'implicit'
  //   Modifiers ::= Modifier {Modifier}                    (non-empty)
  //   ModifiedDef ::= Modifiers (ValDef | VarDef | DefDef
  //                            | ClassDef | ObjectDef | TraitDef)
  // =========================================================

  val Modifier: Rule[String] = rule(
    { case ScalaLexer.`private`(_) => "private" },
    { case ScalaLexer.`protected`(_) => "protected" },
    { case ScalaLexer.`final`(_) => "final" },
    { case ScalaLexer.`sealed`(_) => "sealed" },
    { case ScalaLexer.`abstract`(_) => "abstract" },
    { case ScalaLexer.`override`(_) => "override" },
    { case ScalaLexer.`lazy`(_) => "lazy" },
    { case ScalaLexer.`implicit`(_) => "implicit" },
  )

  val Modifiers: Rule[List[String]] = rule(
    { case Modifier(m) => List(m) },
    { case (Modifiers(ms), Modifier(m)) => ms :+ m },
  )

  val ModifiedDef: Rule[ScalaTree] = rule(
    { case (Modifiers(ms), ValDef(v)) => ScalaTree.Modified(ms, v) },
    { case (Modifiers(ms), VarDef(v)) => ScalaTree.Modified(ms, v) },
    { case (Modifiers(ms), DefDef(d)) => ScalaTree.Modified(ms, d) },
    { case (Modifiers(ms), ClassDef(c)) => ScalaTree.Modified(ms, c) },
    { case (Modifiers(ms), ObjectDef(o)) => ScalaTree.Modified(ms, o) },
    { case (Modifiers(ms), TraitDef(t)) => ScalaTree.Modified(ms, t) },
  )

  /**
   * Non-empty list of BlockStats (keeps the `{ stats; expr }` Block production
   * distinct from the `{ case ... }` partial function at the opening brace).
   */
  val BlockStats: Rule[List[ScalaTree]] = rule(
    { case BlockStat(s) => List(s) },
    { case (BlockStats(ss), BlockStat(s)) => ss :+ s },
  )

  // =========================================================
  // Value and variable definitions
  //
  //   ValDef ::= 'val' id '=' Expr
  //   VarDef ::= 'var' id '=' Expr
  // =========================================================

  val ValDef: Rule[ScalaTree] = rule(
    "valdef0" { case (ScalaLexer.`val`(_), ScalaLexer.id(n), ScalaLexer.`=`(_), Expr(v)) =>
      ScalaTree.ValDef(n.value, None, v)
    },
    "valdef1" {
      case (
            ScalaLexer.`val`(_),
            ScalaLexer.id(n),
            ScalaLexer.`:`(_),
            Type(t),
            ScalaLexer.`=`(_),
            Expr(v),
          ) =>
        ScalaTree.ValDef(n.value, Some(t), v)
    },
  )

  val VarDef: Rule[ScalaTree] = rule(
    "vardef0" { case (ScalaLexer.`var`(_), ScalaLexer.id(n), ScalaLexer.`=`(_), Expr(v)) =>
      ScalaTree.VarDef(n.value, None, v)
    },
    "vardef1" {
      case (
            ScalaLexer.`var`(_),
            ScalaLexer.id(n),
            ScalaLexer.`:`(_),
            Type(t),
            ScalaLexer.`=`(_),
            Expr(v),
          ) =>
        ScalaTree.VarDef(n.value, Some(t), v)
    },
  )

  // =========================================================
  // Method definition (Scala spec: DefDef, simplified)
  //
  //   DefDef ::= 'def' id '(' [Params] ')' ':' Type '=' Expr
  // =========================================================

  val DefDef: Rule[ScalaTree] = rule(
    // def id() : T = body
    "defdef0" {
      case (
            ScalaLexer.`def`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\(`(_),
            ScalaLexer.`\\)`(_),
            ScalaLexer.`:`(_),
            Type(ret),
            ScalaLexer.`=`(_),
            Expr(body),
          ) =>
        ScalaTree.DefDef(n.value, Nil, Nil, ret, body)
    },
    // def id(Params) : T = body
    "defdef1" {
      case (
            ScalaLexer.`def`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\(`(_),
            Params(ps),
            ScalaLexer.`\\)`(_),
            ScalaLexer.`:`(_),
            Type(ret),
            ScalaLexer.`=`(_),
            Expr(body),
          ) =>
        ScalaTree.DefDef(n.value, Nil, ps, ret, body)
    },
    // def id[TypeParams]() : T = body
    "defdef2" {
      case (
            ScalaLexer.`def`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\[`(_),
            TypeParams(ts),
            ScalaLexer.`\\]`(_),
            ScalaLexer.`\\(`(_),
            ScalaLexer.`\\)`(_),
            ScalaLexer.`:`(_),
            Type(ret),
            ScalaLexer.`=`(_),
            Expr(body),
          ) =>
        ScalaTree.DefDef(n.value, ts, Nil, ret, body)
    },
    // def id[TypeParams](Params) : T = body
    "defdef3" {
      case (
            ScalaLexer.`def`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\[`(_),
            TypeParams(ts),
            ScalaLexer.`\\]`(_),
            ScalaLexer.`\\(`(_),
            Params(ps),
            ScalaLexer.`\\)`(_),
            ScalaLexer.`:`(_),
            Type(ret),
            ScalaLexer.`=`(_),
            Expr(body),
          ) =>
        ScalaTree.DefDef(n.value, ts, ps, ret, body)
    },
  )

  // =========================================================
  // Class definition (Scala spec: ClassDef, simplified)
  //
  //   ClassDef ::= 'class' id ['(' Params ')'] ['extends' id] BlockExpr
  //
  // Expanded into four separate productions to avoid optional parts.
  // =========================================================

  val ClassDef: Rule[ScalaTree] = rule(
    // class id BODY
    "class0" { case (ScalaLexer.`class`(_), ScalaLexer.id(n), Expr(body)) =>
      ScalaTree.ClassDef(false, n.value, Nil, Nil, Nil, body)
    },
    // class id (Params) BODY
    "class1" {
      case (
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\(`(_),
            Params(ps),
            ScalaLexer.`\\)`(_),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(false, n.value, Nil, ps, Nil, body)
    },
    // class id extends Parents BODY
    "class2" {
      case (
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`extends`(_),
            Parents(ps),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(false, n.value, Nil, Nil, ps, body)
    },
    // class id (Params) extends Parents BODY
    "class3" {
      case (
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\(`(_),
            Params(prms),
            ScalaLexer.`\\)`(_),
            ScalaLexer.`extends`(_),
            Parents(pts),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(false, n.value, Nil, prms, pts, body)
    },
    // class id [TypeParams] BODY
    "class4" {
      case (
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\[`(_),
            TypeParams(ts),
            ScalaLexer.`\\]`(_),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(false, n.value, ts, Nil, Nil, body)
    },
    // class id [TypeParams] (Params) BODY
    "class5" {
      case (
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\[`(_),
            TypeParams(ts),
            ScalaLexer.`\\]`(_),
            ScalaLexer.`\\(`(_),
            Params(ps),
            ScalaLexer.`\\)`(_),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(false, n.value, ts, ps, Nil, body)
    },
    // class id [TypeParams] extends Parents BODY
    "class6" {
      case (
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\[`(_),
            TypeParams(ts),
            ScalaLexer.`\\]`(_),
            ScalaLexer.`extends`(_),
            Parents(pts),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(false, n.value, ts, Nil, pts, body)
    },
    // class id [TypeParams] (Params) extends Parents BODY
    "class7" {
      case (
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\[`(_),
            TypeParams(ts),
            ScalaLexer.`\\]`(_),
            ScalaLexer.`\\(`(_),
            Params(prms),
            ScalaLexer.`\\)`(_),
            ScalaLexer.`extends`(_),
            Parents(pts),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(false, n.value, ts, prms, pts, body)
    },

    // ---- case class variants (case prefix) ----
    "cc0" { case (ScalaLexer.`case`(_), ScalaLexer.`class`(_), ScalaLexer.id(n), Expr(body)) =>
      ScalaTree.ClassDef(true, n.value, Nil, Nil, Nil, body)
    },
    "cc1" {
      case (
            ScalaLexer.`case`(_),
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\(`(_),
            Params(ps),
            ScalaLexer.`\\)`(_),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(true, n.value, Nil, ps, Nil, body)
    },
    "cc2" {
      case (
            ScalaLexer.`case`(_),
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`extends`(_),
            Parents(ps),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(true, n.value, Nil, Nil, ps, body)
    },
    "cc3" {
      case (
            ScalaLexer.`case`(_),
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\(`(_),
            Params(prms),
            ScalaLexer.`\\)`(_),
            ScalaLexer.`extends`(_),
            Parents(pts),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(true, n.value, Nil, prms, pts, body)
    },
    "cc4" {
      case (
            ScalaLexer.`case`(_),
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\[`(_),
            TypeParams(ts),
            ScalaLexer.`\\]`(_),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(true, n.value, ts, Nil, Nil, body)
    },
    "cc5" {
      case (
            ScalaLexer.`case`(_),
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\[`(_),
            TypeParams(ts),
            ScalaLexer.`\\]`(_),
            ScalaLexer.`\\(`(_),
            Params(ps),
            ScalaLexer.`\\)`(_),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(true, n.value, ts, ps, Nil, body)
    },
    "cc6" {
      case (
            ScalaLexer.`case`(_),
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\[`(_),
            TypeParams(ts),
            ScalaLexer.`\\]`(_),
            ScalaLexer.`extends`(_),
            Parents(pts),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(true, n.value, ts, Nil, pts, body)
    },
    "cc7" {
      case (
            ScalaLexer.`case`(_),
            ScalaLexer.`class`(_),
            ScalaLexer.id(n),
            ScalaLexer.`\\[`(_),
            TypeParams(ts),
            ScalaLexer.`\\]`(_),
            ScalaLexer.`\\(`(_),
            Params(prms),
            ScalaLexer.`\\)`(_),
            ScalaLexer.`extends`(_),
            Parents(pts),
            Expr(body),
          ) =>
        ScalaTree.ClassDef(true, n.value, ts, prms, pts, body)
    },
  )

  // =========================================================
  // Object and trait definitions (Scala spec: TmplDef)
  //
  //   ObjectDef ::= 'object' id ['extends' id {'with' id}] BlockExpr
  //   TraitDef  ::= 'trait' id BlockExpr
  // =========================================================

  val ObjectDef: Rule[ScalaTree] = rule(
    "object0" { case (ScalaLexer.`object`(_), ScalaLexer.id(n), Expr(body)) =>
      ScalaTree.ObjectDef(false, n.value, Nil, body)
    },
    "object1" { case (ScalaLexer.`object`(_), ScalaLexer.id(n), ScalaLexer.`extends`(_), Parents(ps), Expr(body)) =>
      ScalaTree.ObjectDef(false, n.value, ps, body)
    },
    "co0" { case (ScalaLexer.`case`(_), ScalaLexer.`object`(_), ScalaLexer.id(n), Expr(body)) =>
      ScalaTree.ObjectDef(true, n.value, Nil, body)
    },
    "co1" {
      case (
            ScalaLexer.`case`(_),
            ScalaLexer.`object`(_),
            ScalaLexer.id(n),
            ScalaLexer.`extends`(_),
            Parents(ps),
            Expr(body),
          ) =>
        ScalaTree.ObjectDef(true, n.value, ps, body)
    },
  )

  val TraitDef: Rule[ScalaTree] = rule:
    case (ScalaLexer.`trait`(_), ScalaLexer.id(n), Expr(body)) =>
      ScalaTree.TraitDef(n.value, body)

  // =========================================================
  // Parents for extends-clause (Scala spec: InheritClauses, simplified to ids)
  //
  //   Parents ::= id {'with' id}
  // =========================================================

  val Parents: Rule[List[String]] = rule(
    { case ScalaLexer.id(n) => List(n.value) },
    { case (Parents(ps), ScalaLexer.`with`(_), ScalaLexer.id(n)) => ps :+ n.value },
  )

  // =========================================================
  // Type parameters (Scala spec: DefTypeParamClause / ClsTypeParamClause)
  //
  //   TypeParams ::= id {',' id}
  //
  // Simplified: no variance (`+A`, `-A`), no bounds (`A <: B`, `A >: B`),
  // no higher-kinded types.
  // =========================================================

  val TypeParams: Rule[List[String]] = rule(
    { case ScalaLexer.id(n) => List(n.value) },
    { case (TypeParams(ts), ScalaLexer.`,`(_), ScalaLexer.id(n)) => ts :+ n.value },
  )

  // =========================================================
  // Parameters (Scala spec: Params, Param — simplified, no defaults)
  //
  //   Params ::= Param {',' Param}
  //   Param  ::= id ':' Type
  // =========================================================

  val ParamRule: Rule[Param] = rule(
    "param" { case (ScalaLexer.id(n), ScalaLexer.`:`(_), Type(t)) => Param(n.value, t, None) },
    "paramDefault" { case (ScalaLexer.id(n), ScalaLexer.`:`(_), Type(t), ScalaLexer.`=`(_), Expr(e)) =>
      Param(n.value, t, Some(e))
    },
  )

  val Params: Rule[List[Param]] = rule(
    { case ParamRule(p) => List(p) },
    { case (Params(ps), ScalaLexer.`,`(_), ParamRule(p)) => ps :+ p },
  )

  // =========================================================
  // Types (Scala spec: SimpleType — simplified)
  //
  //   Type  ::= id | id '[' Types ']'
  //   Types ::= Type {',' Type}
  //
  // Function types (`A => B`) are intentionally dropped — keeping them
  // would introduce an ambiguity with the case-clause arrow in patterns
  // and blow up the LALR state machine.
  // =========================================================

  val Type: Rule[ScalaType] = rule(
    { case ScalaLexer.id(n) => ScalaType.TypeRef(n.value) },
    { case (ScalaLexer.id(n), ScalaLexer.`\\[`(_), Types(ts), ScalaLexer.`\\]`(_)) =>
      ScalaType.AppliedType(n.value, ts)
    },
  )

  val Types: Rule[List[ScalaType]] = rule(
    { case Type(t) => List(t) },
    { case (Types(ts), ScalaLexer.`,`(_), Type(t)) => ts :+ t },
  )

  // =========================================================
  // Case clauses (Scala spec: CaseClauses, CaseClause, Guard)
  //
  //   CaseClauses ::= CaseClause {CaseClause}
  //   CaseClause  ::= 'case' Pattern [Guard] '=>' Expr
  //   Guard       ::= 'if' Expr
  //
  // Simplification vs. spec: case body is a single Expr (not a Block).
  // Wrap in `{}` for multi-statement bodies.
  // =========================================================

  val CaseClause: Rule[MatchCase] = rule(
    { case (ScalaLexer.`case`(_), Pattern(p), ScalaLexer.arrow(_), Expr(body)) =>
      MatchCase(p, None, body)
    },
    {
      case (
            ScalaLexer.`case`(_),
            Pattern(p),
            ScalaLexer.`if`(_),
            Expr(g),
            ScalaLexer.arrow(_),
            Expr(body),
          ) =>
        MatchCase(p, Some(g), body)
    },
  )

  /** Non-empty case-clause list (avoids the empty-block ambiguity with UnitBlock). */
  val CaseClauses: Rule[List[MatchCase]] = rule(
    { case CaseClause(c) => List(c) },
    { case (CaseClauses(cs), CaseClause(c)) => cs :+ c },
  )

  // =========================================================
  // Patterns (Scala spec: Pattern, SimplePattern — simplified)
  //
  //   Pattern       ::= Pattern1 {'|' Pattern1}
  //   Pattern1      ::= Pattern2 | Pattern2 ':' Type
  //   Pattern2      ::= SimplePattern | id '@' SimplePattern
  //   SimplePattern ::= '_' | Literal | id | id '(' Patterns ')'
  //   Patterns      ::= Pattern {',' Pattern}
  // =========================================================

  // Simplified to reduce LALR state-machine size:
  //   - no typed patterns (`p: T`) — would conflict with function-type arrow
  //   - no alt patterns (`a | b`) — saves states; use multiple case clauses
  //   - Pattern1/Pattern2 collapsed into Pattern
  //   - kept: wildcard, literals, variable, constructor, bind

  val Pattern: Rule[ScalaPattern] = rule(
    { case SimplePattern(p) => p },
    "bindpat" { case (ScalaLexer.id(n), ScalaLexer.`@`(_), SimplePattern(p)) =>
      ScalaPattern.BindPat(n.value, p)
    },
  )

  val SimplePattern: Rule[ScalaPattern] = rule(
    { case ScalaLexer.wildcard(_) => ScalaPattern.Wildcard },
    { case ScalaLexer.id(n) => ScalaPattern.VarPat(n.value) },
    { case ScalaLexer.intLit(n) => ScalaPattern.LitPat(ScalaTree.IntLit(n.value)) },
    { case ScalaLexer.floatLit(f) => ScalaPattern.LitPat(ScalaTree.FloatLit(f.value)) },
    { case ScalaLexer.`true`(_) => ScalaPattern.LitPat(ScalaTree.BoolLit(true)) },
    { case ScalaLexer.`false`(_) => ScalaPattern.LitPat(ScalaTree.BoolLit(false)) },
    { case ScalaLexer.`null`(_) => ScalaPattern.LitPat(ScalaTree.NullLit) },
    { case ScalaLexer.charLit(c) => ScalaPattern.LitPat(ScalaTree.CharLit(c.value)) },
    { case ScalaLexer.stringLit(s) => ScalaPattern.LitPat(ScalaTree.StringLit(s.value)) },
    "constrpat" { case (ScalaLexer.id(n), ScalaLexer.`\\(`(_), Patterns(ps), ScalaLexer.`\\)`(_)) =>
      ScalaPattern.ConstrPat(n.value, ps)
    },
    "constrpatempty" { case (ScalaLexer.id(n), ScalaLexer.`\\(`(_), ScalaLexer.`\\)`(_)) =>
      ScalaPattern.ConstrPat(n.value, Nil)
    },
  )

  val Patterns: Rule[List[ScalaPattern]] = rule(
    { case Pattern(p) => List(p) },
    { case (Patterns(ps), ScalaLexer.`,`(_), Pattern(p)) => ps :+ p },
  )

  // =========================================================
  // Top-level rule
  // =========================================================

  val root: Rule[ScalaTree] = rule:
    case Expr(e) => e

  // =========================================================
  // Conflict resolutions for operator precedence
  //
  // `X.after(Y)` means "don't reduce X when seeing Y" (Y binds tighter).
  // `X.before(Y)` means "reduce X before shifting Y" (X binds tighter).
  // =========================================================

  override val resolutions: Set[ConflictResolution] = Set(
    // Select '.' and Apply '(' bind tightest
    production.plus.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.minus.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.times.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.divide.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.mod.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.eq.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.neq.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.lt.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.gt.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.lte.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.gte.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.and.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.or.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.uplus.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.uminus.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.unot.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),
    production.ifelse.after(ScalaLexer.`\\.`, ScalaLexer.`\\(`),

    // Unary '+' / '-' / '!' bind tighter than binary operators
    production.uplus.before(ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),
    production.uplus.before(ScalaLexer.`\\+`, ScalaLexer.`-`),
    production.uplus.before(ScalaLexer.`<`, ScalaLexer.`>`, ScalaLexer.lte, ScalaLexer.gte),
    production.uplus.before(ScalaLexer.eqeq, ScalaLexer.neq),
    production.uplus.before(ScalaLexer.and),
    production.uplus.before(ScalaLexer.or),

    production.uminus.before(ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),
    production.uminus.before(ScalaLexer.`\\+`, ScalaLexer.`-`),
    production.uminus.before(ScalaLexer.`<`, ScalaLexer.`>`, ScalaLexer.lte, ScalaLexer.gte),
    production.uminus.before(ScalaLexer.eqeq, ScalaLexer.neq),
    production.uminus.before(ScalaLexer.and),
    production.uminus.before(ScalaLexer.or),

    production.unot.before(ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),
    production.unot.before(ScalaLexer.`\\+`, ScalaLexer.`-`),
    production.unot.before(ScalaLexer.`<`, ScalaLexer.`>`, ScalaLexer.lte, ScalaLexer.gte),
    production.unot.before(ScalaLexer.eqeq, ScalaLexer.neq),
    production.unot.before(ScalaLexer.and),
    production.unot.before(ScalaLexer.or),

    // '*' '/' '%' higher precedence than '+' '-'
    production.plus.after(ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),
    production.minus.after(ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),
    production.plus.before(ScalaLexer.`\\+`, ScalaLexer.`-`),
    production.minus.before(ScalaLexer.`\\+`, ScalaLexer.`-`),
    production.times.before(ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),
    production.divide.before(ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),
    production.mod.before(ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),

    // '+' '-' higher precedence than '<' '>' '<=' '>='
    production.lt.after(ScalaLexer.`\\+`, ScalaLexer.`-`, ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),
    production.gt.after(ScalaLexer.`\\+`, ScalaLexer.`-`, ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),
    production.lte.after(ScalaLexer.`\\+`, ScalaLexer.`-`, ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),
    production.gte.after(ScalaLexer.`\\+`, ScalaLexer.`-`, ScalaLexer.`\\*`, ScalaLexer.`/`, ScalaLexer.`%`),
    production.lt.before(ScalaLexer.`<`, ScalaLexer.`>`, ScalaLexer.lte, ScalaLexer.gte),
    production.gt.before(ScalaLexer.`<`, ScalaLexer.`>`, ScalaLexer.lte, ScalaLexer.gte),
    production.lte.before(ScalaLexer.`<`, ScalaLexer.`>`, ScalaLexer.lte, ScalaLexer.gte),
    production.gte.before(ScalaLexer.`<`, ScalaLexer.`>`, ScalaLexer.lte, ScalaLexer.gte),

    // '<' '>' '<=' '>=' higher precedence than '==' '!='
    production.eq.after(ScalaLexer.`<`, ScalaLexer.`>`, ScalaLexer.lte, ScalaLexer.gte),
    production.neq.after(ScalaLexer.`<`, ScalaLexer.`>`, ScalaLexer.lte, ScalaLexer.gte),
    production.eq.before(ScalaLexer.eqeq, ScalaLexer.neq),
    production.neq.before(ScalaLexer.eqeq, ScalaLexer.neq),

    // '==' '!=' higher precedence than '&&'
    production.and.after(ScalaLexer.eqeq, ScalaLexer.neq),
    production.and.before(ScalaLexer.and),

    // '&&' higher precedence than '||'
    production.or.after(ScalaLexer.and),
    production.or.before(ScalaLexer.or),

    // if-else: else-branch is greedy
    production.ifelse.after(
      ScalaLexer.`\\+`,
      ScalaLexer.`-`,
      ScalaLexer.`\\*`,
      ScalaLexer.`/`,
      ScalaLexer.`%`,
      ScalaLexer.`<`,
      ScalaLexer.`>`,
      ScalaLexer.lte,
      ScalaLexer.gte,
      ScalaLexer.eqeq,
      ScalaLexer.neq,
      ScalaLexer.and,
      ScalaLexer.or,
    ),

    // while body is greedy: `while (c) x + 1` parses as `while (c) (x + 1)`
    production.whileloop.after(
      ScalaLexer.`\\+`,
      ScalaLexer.`-`,
      ScalaLexer.`\\*`,
      ScalaLexer.`/`,
      ScalaLexer.`%`,
      ScalaLexer.`<`,
      ScalaLexer.`>`,
      ScalaLexer.lte,
      ScalaLexer.gte,
      ScalaLexer.eqeq,
      ScalaLexer.neq,
      ScalaLexer.and,
      ScalaLexer.or,
    ),

    // throw / return bodies are greedy: `throw x + 1` parses as `throw (x + 1)`
    production.`throw`.after(
      ScalaLexer.`\\+`,
      ScalaLexer.`-`,
      ScalaLexer.`\\*`,
      ScalaLexer.`/`,
      ScalaLexer.`%`,
      ScalaLexer.`<`,
      ScalaLexer.`>`,
      ScalaLexer.lte,
      ScalaLexer.gte,
      ScalaLexer.eqeq,
      ScalaLexer.neq,
      ScalaLexer.and,
      ScalaLexer.or,
      ScalaLexer.`\\.`,
      ScalaLexer.`\\(`,
    ),
    production.ret.after(
      ScalaLexer.`\\+`,
      ScalaLexer.`-`,
      ScalaLexer.`\\*`,
      ScalaLexer.`/`,
      ScalaLexer.`%`,
      ScalaLexer.`<`,
      ScalaLexer.`>`,
      ScalaLexer.lte,
      ScalaLexer.gte,
      ScalaLexer.eqeq,
      ScalaLexer.neq,
      ScalaLexer.and,
      ScalaLexer.or,
      ScalaLexer.`\\.`,
      ScalaLexer.`\\(`,
    ),
  )
