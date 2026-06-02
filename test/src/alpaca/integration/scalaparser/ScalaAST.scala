package alpaca
package integration.scalaparser

/**
 * AST for a subset of Scala expressions.
 *
 * Follows the Scala 3 language specification grammar
 * (https://docs.scala-lang.org/scala3/reference/syntax.html), restricted to
 * classic brace syntax — no significant indentation, no optional braces, no
 * indent/outdent tokens.
 *
 * Productions covered (EBNF taken verbatim from spec; simplifications noted):
 *
 *   Literal       ::= integerLiteral | floatingPointLiteral
 *                   | booleanLiteral | characterLiteral | stringLiteral
 *                   | interpolatedStringLiteral | 'null'
 *   SimpleRef     ::= id
 *   SimpleExpr    ::= SimpleRef | Literal | BlockExpr | '(' Expr ')'
 *                   | SimpleExpr '.' id           (Select)
 *                   | SimpleExpr ArgumentExprs    (Apply)
 *                   | 'new' id                    (New — simplified, no args)
 *   PrefixExpr    ::= ['-' | '!'] SimpleExpr
 *   InfixExpr     ::= PrefixExpr | InfixExpr op InfixExpr
 *   Expr1         ::= 'if' '(' Expr ')' Expr 'else' Expr
 *   BlockExpr     ::= '{' '}' | '{' Block '}'
 *   Block         ::= {BlockStat ';'} Expr
 *   BlockStat     ::= Def ';' | Expr ';'
 *   Def           ::= ValDef | VarDef | DefDef | ClassDef
 *   ValDef        ::= 'val' id [':' Type] '=' Expr
 *   VarDef        ::= 'var' id [':' Type] '=' Expr
 *   DefDef        ::= 'def' id [TypeParams] '(' [Params] ')' ':' Type '=' Expr
 *   ClassDef      ::= ['case'] 'class' id [TypeParams] ['(' Params ')']
 *                       ['extends' id {'with' id}] BlockExpr
 *   TypeParams    ::= '[' id {',' id} ']'       (simplified: no bounds, no variance)
 *   ObjectDef     ::= ['case'] 'object' id ['extends' id {'with' id}] BlockExpr
 *   TraitDef      ::= 'trait' id BlockExpr
 *   Tuple         ::= '(' Expr ',' Expr {',' Expr} ')'     (2+ elements)
 *   ModifiedDef   ::= Modifiers Def                        (1+ modifiers prefix)
 *   Modifiers     ::= Modifier {Modifier}
 *   Modifier      ::= 'private' | 'protected' | 'final' | 'sealed'
 *                   | 'abstract' | 'override' | 'lazy' | 'implicit'
 *   Import        ::= 'import' id {'.' id}                 (simple path; no selectors)
 *   While         ::= 'while' '(' Expr ')' Expr
 *   Throw         ::= 'throw' Expr
 *   Return        ::= 'return' Expr
 *   Params        ::= Param {',' Param}
 *   Param         ::= id ':' Type ['=' Expr]      (optional default value)
 *   Type          ::= id | id '[' Types ']'           (SimpleType only)
 *   Types         ::= Type {',' Type}
 *   BlockExpr     ::= '{' CaseClauses '}'                 (partial function literal)
 *   CaseClauses   ::= CaseClause {CaseClause}
 *   CaseClause    ::= 'case' Pattern [Guard] '=>' Expr
 *   Guard         ::= 'if' Expr
 *   Pattern       ::= SimplePattern | id '@' SimplePattern
 *   SimplePattern ::= '_' | Literal | id | id '(' Patterns ')'
 *   Patterns      ::= Pattern {',' Pattern}
 *
 * Spec productions intentionally NOT covered (LALR state-explosion or scope):
 *   - Full match expressions `e match { ... }` — state explosion when
 *     combined with the full InfixExpr precedence ladder. Partial function
 *     literals (`{ case p => e }`) are supported instead; to simulate
 *     `x match { case ... }`, apply the partial function: `({ case ... })(x)`.
 *   - Lambdas / closures
 *   - Type ascription on expressions (`e: T`) — conflicts with param `:`
 *   - Typed patterns (`p: T`) — conflicts with case-clause arrow
 *   - Alt patterns (`a | b`) — state-explosion; use multiple case clauses
 *   - Function types (`A => B`) — arrow conflicts with case-clause arrow
 *   - Significant indentation and `indent`/`outdent` tokens
 *   - Context-sensitive `colon` token
 *   - Quoted patterns, `given` patterns, `inline` modifiers
 *   - Extensions, given instances, using clauses, implicit
 *   - Traits, objects, enums, case classes
 *   - Multiple parameter clauses, default args, by-name params
 *   - Type parameters on `def` and `class`
 *   - Variance, bounds (`<:`, `>:`), refinements, match types
 *   - `try`, `while`, `for`, `throw`, `return`
 *   - Interpolated strings, symbol literals, character literals
 *   - Constructor arguments on `new` (`new Foo(1, 2)`)
 */
enum ScalaTree:
  // Literals (Scala 3 spec: Literal)
  case IntLit(value: Long)
  case FloatLit(value: Double)
  case BoolLit(value: Boolean)
  case CharLit(value: String) // raw body between quotes; escapes un-decoded
  case StringLit(value: String)
  // Spec: Literal ::= interpolatedStringLiteral — `s"..."` / `f"..."` / `raw"..."`.
  // `raw` is the whole matched lexeme (including prefix + quotes); splices
  // are NOT parsed.
  case InterpolatedStr(raw: String)
  case NullLit

  // Simple references (Scala 3 spec: SimpleRef)
  case Ident(name: String)

  // Expressions (Scala 3 spec: InfixExpr, PrefixExpr, SimpleExpr)
  case Select(qual: ScalaTree, name: String) // SimpleExpr '.' id
  case Apply(fun: ScalaTree, args: List[ScalaTree]) // SimpleExpr ArgumentExprs
  case Prefix(op: String, operand: ScalaTree) // PrefixExpr
  case Infix(left: ScalaTree, op: String, right: ScalaTree) // InfixExpr

  // Expr1: if-then-else
  case If(cond: ScalaTree, thenBranch: ScalaTree, elseBranch: ScalaTree)

  // BlockExpr / Block
  case Block(stmts: List[ScalaTree], result: ScalaTree)
  case UnitBlock // empty block: {}

  // Definitions (Scala 3 spec: Def subtree)
  case ValDef(name: String, tpe: Option[ScalaType], value: ScalaTree)
  case VarDef(name: String, tpe: Option[ScalaType], value: ScalaTree)
  case DefDef(
    name: String,
    tparams: List[String],
    params: List[Param],
    retTpe: ScalaType,
    body: ScalaTree,
  )
  case ClassDef(
    isCase: Boolean,
    name: String,
    tparams: List[String],
    params: List[Param],
    parents: List[String],
    body: ScalaTree,
  )
  case ObjectDef(isCase: Boolean, name: String, parents: List[String], body: ScalaTree)
  case TraitDef(name: String, body: ScalaTree)

  // Spec: 'new' ConstrApp — simplified to 'new' id, no args
  case New(name: String, args: List[ScalaTree])

  // Spec: SimpleExpr ::= '(' ExprsInParens ')' with 2+ exprs — tuple literal
  case Tuple(elems: List[ScalaTree])

  // Spec: Def preceded by one or more Modifiers (private / final / etc.)
  case Modified(mods: List[String], inner: ScalaTree)

  // Spec: BlockStat ::= Import — simplified to a dotted path, no selectors
  case Import(path: List[String])

  // Spec: BlockExpr ::= '{' CaseClauses '}' — partial function literal
  case PartialFun(cases: List[MatchCase])

  // Control flow (Scala 3 spec: Expr1)
  case While(cond: ScalaTree, body: ScalaTree)
  case Throw(expr: ScalaTree)
  case Return(expr: ScalaTree)

/** Spec: CaseClause ::= 'case' Pattern [Guard] '=>' Expr */
case class MatchCase(pattern: ScalaPattern, guard: Option[ScalaTree], body: ScalaTree)

/** Spec: Param ::= id ':' Type ['=' Expr] */
case class Param(name: String, tpe: ScalaType, default: Option[ScalaTree])

/**
 * Pattern AST (Scala 3 spec: Pattern, SimplePattern — simplified).
 *
 * Constructor patterns (`Name(p1, p2)`) are covered; extractor-specific
 * mechanics are not — any `id(...)` lexically qualifies.
 */
enum ScalaPattern:
  case Wildcard // SimplePattern: '_'
  case VarPat(name: String) // SimplePattern: varid
  case LitPat(lit: ScalaTree) // SimplePattern: Literal
  case ConstrPat(name: String, args: List[ScalaPattern]) // SimplePattern: id '(' Patterns ')'
  case BindPat(name: String, inner: ScalaPattern) // Pattern: id '@' SimplePattern

/**
 * Type AST (Scala 3 spec: Type, SimpleType).
 *
 * Covers simple named types, applied types (`List[Int]`) and function types
 * (`A => B`). Intersection, refinement, match, bounds, and path-dependent
 * types are out of scope.
 */
enum ScalaType:
  case TypeRef(name: String) // SimpleType: id
  case AppliedType(base: String, args: List[ScalaType]) // SimpleType: id '[' Types ']'
