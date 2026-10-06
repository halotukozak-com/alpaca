package bench.alpaca

import halotukozak.alpaca.internal.parser.Parser
import halotukozak.alpaca.{after, lexer, resolutions, rule, Production as P, Resolutions, Rule, Token}

// Synthetic big-grammar lexer with 30 keyword token types plus numbers and whitespace.
// Token names are 2-char codes (ka-kz, la-ld) to avoid prefix shadowing in the regex lexer.
// Designed as a stress test for lexer/parser table generation, not a realistic language.
val BigGrammarLexer = lexer {
  case "\\s+" => Token.Ignored
  case x @ (
        "ka" | "kb" | "kc" | "kd" | "ke" | "kf" | "kg" | "kh" | "ki" | "kj" | "kk" | "kl" | "km" | "kn" | "ko" | "kp" |
        "kq" | "kr" | "ks" | "kt" | "ku" | "kv" | "kw" | "kx" | "ky" | "kz" | "la" | "lb" | "lc" | "ld"
      ) =>
    Token[x.type]
  case num @ "\\d+" => Token["num"](num.toInt)
}

// Synthetic big-grammar parser with 50+ rules combining 30 token types.
// Grammar structure: simple rules match single tokens, compound rules match
// sequences, and composite rules combine alternatives. The root rule accepts
// a list of statements (single token, pair, triple, or number).
object BigGrammarParser extends Parser:

  // --- Simple token rules (30 rules: one per token type) ---
  val TokA: Rule[String] = rule { case BigGrammarLexer.ka(_) => "ka" }
  val TokB: Rule[String] = rule { case BigGrammarLexer.kb(_) => "kb" }
  val TokC: Rule[String] = rule { case BigGrammarLexer.kc(_) => "kc" }
  val TokD: Rule[String] = rule { case BigGrammarLexer.kd(_) => "kd" }
  val TokE: Rule[String] = rule { case BigGrammarLexer.ke(_) => "ke" }
  val TokF: Rule[String] = rule { case BigGrammarLexer.kf(_) => "kf" }
  val TokG: Rule[String] = rule { case BigGrammarLexer.kg(_) => "kg" }
  val TokH: Rule[String] = rule { case BigGrammarLexer.kh(_) => "kh" }
  val TokI: Rule[String] = rule { case BigGrammarLexer.ki(_) => "ki" }
  val TokJ: Rule[String] = rule { case BigGrammarLexer.kj(_) => "kj" }
  val TokK: Rule[String] = rule { case BigGrammarLexer.kk(_) => "kk" }
  val TokL: Rule[String] = rule { case BigGrammarLexer.kl(_) => "kl" }
  val TokM: Rule[String] = rule { case BigGrammarLexer.km(_) => "km" }
  val TokN: Rule[String] = rule { case BigGrammarLexer.kn(_) => "kn" }
  val TokO: Rule[String] = rule { case BigGrammarLexer.ko(_) => "ko" }
  val TokP: Rule[String] = rule { case BigGrammarLexer.kp(_) => "kp" }
  val TokQ: Rule[String] = rule { case BigGrammarLexer.kq(_) => "kq" }
  val TokR: Rule[String] = rule { case BigGrammarLexer.kr(_) => "kr" }
  val TokS: Rule[String] = rule { case BigGrammarLexer.ks(_) => "ks" }
  val TokT: Rule[String] = rule { case BigGrammarLexer.kt(_) => "kt" }
  val TokU: Rule[String] = rule { case BigGrammarLexer.ku(_) => "ku" }
  val TokV: Rule[String] = rule { case BigGrammarLexer.kv(_) => "kv" }
  val TokW: Rule[String] = rule { case BigGrammarLexer.kw(_) => "kw" }
  val TokX: Rule[String] = rule { case BigGrammarLexer.kx(_) => "kx" }
  val TokY: Rule[String] = rule { case BigGrammarLexer.ky(_) => "ky" }
  val TokZ: Rule[String] = rule { case BigGrammarLexer.kz(_) => "kz" }
  val Tok26: Rule[String] = rule { case BigGrammarLexer.la(_) => "la" }
  val Tok27: Rule[String] = rule { case BigGrammarLexer.lb(_) => "lb" }
  val Tok28: Rule[String] = rule { case BigGrammarLexer.lc(_) => "lc" }
  val Tok29: Rule[String] = rule { case BigGrammarLexer.ld(_) => "ld" }

  // --- Number rule ---
  val Num: Rule[String] = rule { case BigGrammarLexer.num(_) => "num" }

  // --- Compound rules: sequences of tokens (5 pairs) ---
  val PairAB: Rule[String] = rule { case (TokA(a), TokB(b)) => s"$a-$b" }
  val PairCD: Rule[String] = rule { case (TokC(a), TokD(b)) => s"$a-$b" }
  val PairEF: Rule[String] = rule { case (TokE(a), TokF(b)) => s"$a-$b" }
  val PairGH: Rule[String] = rule { case (TokG(a), TokH(b)) => s"$a-$b" }
  val PairIJ: Rule[String] = rule { case (TokI(a), TokJ(b)) => s"$a-$b" }

  // --- Compound rules: triples of tokens (5 triples) ---
  val TripleABC: Rule[String] = rule { case (TokA(a), TokB(b), TokC(c)) => s"$a-$b-$c" }
  val TripleDEF: Rule[String] = rule { case (TokD(a), TokE(b), TokF(c)) => s"$a-$b-$c" }
  val TripleGHI: Rule[String] = rule { case (TokG(a), TokH(b), TokI(c)) => s"$a-$b-$c" }
  val TripleJKL: Rule[String] = rule { case (TokJ(a), TokK(b), TokL(c)) => s"$a-$b-$c" }
  val TripleMNO: Rule[String] = rule { case (TokM(a), TokN(b), TokO(c)) => s"$a-$b-$c" }

  // --- Composite rules: groups of alternative tokens (6 groups) ---
  val GroupLow: Rule[String] = rule(
    { case TokA(v) => v },
    { case TokB(v) => v },
    { case TokC(v) => v },
    { case TokD(v) => v },
    { case TokE(v) => v },
  )

  val GroupMid: Rule[String] = rule(
    { case TokF(v) => v },
    { case TokG(v) => v },
    { case TokH(v) => v },
    { case TokI(v) => v },
    { case TokJ(v) => v },
  )

  val GroupHigh: Rule[String] = rule(
    { case TokK(v) => v },
    { case TokL(v) => v },
    { case TokM(v) => v },
    { case TokN(v) => v },
    { case TokO(v) => v },
  )

  val GroupTop: Rule[String] = rule(
    { case TokP(v) => v },
    { case TokQ(v) => v },
    { case TokR(v) => v },
    { case TokS(v) => v },
    { case TokT(v) => v },
  )

  val GroupExtra: Rule[String] = rule(
    { case TokU(v) => v },
    { case TokV(v) => v },
    { case TokW(v) => v },
    { case TokX(v) => v },
    { case TokY(v) => v },
  )

  val GroupFinal: Rule[String] = rule(
    { case TokZ(v) => v },
    { case Tok26(v) => v },
    { case Tok27(v) => v },
    { case Tok28(v) => v },
    { case Tok29(v) => v },
  )

  // --- Compound alternatives: any pair or any triple ---
  val AnyPair: Rule[String] = rule(
    { case PairAB(v) => v },
    { case PairCD(v) => v },
    { case PairEF(v) => v },
    { case PairGH(v) => v },
    { case PairIJ(v) => v },
  )

  val AnyTriple: Rule[String] = rule(
    { case TripleABC(v) => v },
    { case TripleDEF(v) => v },
    { case TripleGHI(v) => v },
    { case TripleJKL(v) => v },
    { case TripleMNO(v) => v },
  )

  // --- Wider composite: any single token ---
  val AnyToken: Rule[String] = rule(
    { case GroupLow(v) => v },
    { case GroupMid(v) => v },
    { case GroupHigh(v) => v },
    { case GroupTop(v) => v },
    { case GroupExtra(v) => v },
    { case GroupFinal(v) => v },
  )

  // --- Statement: a token, pair, triple, or number ---
  val Statement: Rule[Any] = rule(
    { case AnyTriple(v) => v },
    { case AnyPair(v) => v },
    { case AnyToken(v) => v },
    { case Num(v) => v },
  )

  // --- Root: a list of statements ---
  val root: Rule[List[Any]] = rule { case Statement.List(vs) => vs }

// Statements are not delimited, so `ka kb` could be one pair or two single tokens.
// Prefer shifting: the longest pair or triple wins.
given Resolutions[BigGrammarParser.type] = resolutions(
  P(BigGrammarParser.TokA).after(BigGrammarLexer.kb),
  P(BigGrammarParser.TokC).after(BigGrammarLexer.kd),
  P(BigGrammarParser.TokD).after(BigGrammarLexer.ke),
  P(BigGrammarParser.TokE).after(BigGrammarLexer.kf),
  P(BigGrammarParser.TokG).after(BigGrammarLexer.kh),
  P(BigGrammarParser.TokI).after(BigGrammarLexer.kj),
  P(BigGrammarParser.TokJ).after(BigGrammarLexer.kk),
  P(BigGrammarParser.TokM).after(BigGrammarLexer.kn),
  P(BigGrammarParser.TokA, BigGrammarParser.TokB).after(BigGrammarLexer.kc),
  P(BigGrammarParser.TokG, BigGrammarParser.TokH).after(BigGrammarLexer.ki),
)
