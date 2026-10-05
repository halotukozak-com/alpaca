package halotukozak
package alpaca
package integration

import halotukozak.alpaca.internal.parser.Parser
import org.scalatest.funsuite.AnyFunSuite

final class CompilationTheoryTest extends AnyFunSuite:
  final case class ASTNode(value: String, children: List[ASTNode])

  val CTLexer = lexer:
    case "\\s+" => Token.Ignored
    case "#.*" => Token.Ignored

    case comparator @ ("<=" | ">=" | "==" | "!=") => Token[comparator.type]
    case comparator @ ("<" | ">") => Token[comparator.type]

    case assignOp @ ("\\+=" | "-=" | "\\*=" | "/=" | "=") => Token[assignOp.type]

    case matrixOp @ ("\\.\\+" | "\\.\\-" | "\\.\\*" | "\\./") => Token[matrixOp.type]

    case bracket @ ("\\(" | "\\)" | "\\[" | "\\]" | "\\{" | "\\}") => Token[bracket.type]

    case binaryOp @ ("\\+" | "-" | "\\*" | "/") => Token[binaryOp.type]

    case literal @ (":" | "'" | "," | ";") => Token[literal.type]

    case keyword @ ("if" | "else" | "while" | "for" | "break" | "continue" | "return") => Token[keyword.type]

    case function @ ("eye" | "zeros" | "ones" | "print") => Token["function"](function)

    case id @ "[a-zA-Z_][a-zA-Z0-9_]*" => Token["id"](id)

    case float @ """-?(\d+\.\d*|\.\d+)""" => Token["float"](float.toDouble)

    case int @ "-?\\d+" => Token["int"](int.toInt)

    case string @ """"(\\.|[^"])*"""" => Token["string"](string.slice(1, string.length - 1))

  object ASTPrinterParser extends Parser:
    val root: Rule[ASTNode] = rule:
      case Instruction.List(instructions) => ASTNode("program", instructions)

    val Block: Rule[ASTNode] = rule(
      { case Instruction(instruction) => ASTNode("block", scala.List(instruction)) },
      { case (CTLexer.`\\{`(_), Instruction.List(instructions), CTLexer.`\\}`(_)) => ASTNode("block", instructions) },
    )

    val Instruction: Rule[ASTNode] = rule(
      { case (Statement(statement), CTLexer.`;`(_)) => statement },
      "bareIf" { case (CTLexer.`if`(_), CTLexer.`\\(`(_), Condition(cond), CTLexer.`\\)`(_), Block(block)) =>
        ASTNode("IF", scala.List(cond, block))
      },
      {
        case (
              CTLexer.`if`(_),
              CTLexer.`\\(`(_),
              Condition(cond),
              CTLexer.`\\)`(_),
              Block(ifBlock),
              CTLexer.`else`(_),
              Block(elseBlock),
            ) =>
          ASTNode("IF", scala.List(cond, ifBlock, elseBlock))
      },
      { case (CTLexer.`while`(_), CTLexer.`\\(`(_), Condition(cond), CTLexer.`\\)`(_), Block(block)) =>
        ASTNode("WHILE", scala.List(cond, block))
      },
      { case (CTLexer.`for`(_), CTLexer.id(id), CTLexer.`=`(_), Range(range), Block(block)) =>
        ASTNode("FOR", scala.List(ASTNode(id.value, Nil), range, block))
      },
    )

    val Statement: Rule[ASTNode] = rule(
      { case (Element(elem), AssignOp(op), Expression(expr)) => ASTNode(op, scala.List(elem, expr)) },
      { case (CTLexer.id(id), AssignOp(op), Expression(expr)) => ASTNode(op, scala.List(ASTNode(id.value, Nil), expr)) },
      { case CTLexer.break(_) => ASTNode("BREAK", Nil) },
      { case CTLexer.continue(_) => ASTNode("CONTINUE", Nil) },
      { case (CTLexer.`return`(_), Expression(expr)) => ASTNode("RETURN", scala.List(expr)) },
      { case (CTLexer.function(fname), CTLexer.`\\(`(_), Arguments(args), CTLexer.`\\)`(_)) =>
        ASTNode(fname.value, args)
      },
    )

    val Condition: Rule[ASTNode] = rule:
      case (Expression(lhs), Comparator(op), Expression(rhs)) => ASTNode(op, scala.List(lhs, rhs))

    val Range: Rule[ASTNode] = rule:
      case (Expression(start), CTLexer.`:`(_), Expression(end)) => ASTNode(":", scala.List(start, end))

    val Arguments: Rule[List[ASTNode]] = rule(
      { case Expression(expr) => scala.List(expr) },
      { case (Arguments(args), CTLexer.`,`(_), Expression(expr)) => args :+ expr },
    )

    val Element: Rule[ASTNode] = rule:
      case (CTLexer.id(id), CTLexer.`\\[`(_), Arguments(args), CTLexer.`\\]`(_)) =>
        ASTNode("element", ASTNode(id.value, Nil) :: args)

    val Expression: Rule[ASTNode] = rule(
      { case CTLexer.id(id) => ASTNode(id.value, Nil) },
      { case Matrix(matrix) => matrix },
      { case Element(elem) => elem },
      { case (CTLexer.`\\(`(_), Expression(expr), CTLexer.`\\)`(_)) => ASTNode("expr", scala.List(expr)) },
      { case (CTLexer.function(fname), CTLexer.`\\(`(_), Arguments(args), CTLexer.`\\)`(_)) =>
        ASTNode(fname.value, args)
      },
      "add" { case (Expression(lhs), CTLexer.`\\+`(_), Expression(rhs)) => ASTNode("+", scala.List(lhs, rhs)) },
      "sub" { case (Expression(lhs), CTLexer.`-`(_), Expression(rhs)) => ASTNode("-", scala.List(lhs, rhs)) },
      "mul" { case (Expression(lhs), CTLexer.`\\*`(_), Expression(rhs)) => ASTNode("*", scala.List(lhs, rhs)) },
      "div" { case (Expression(lhs), CTLexer.`/`(_), Expression(rhs)) => ASTNode("/", scala.List(lhs, rhs)) },
      "matrixAdd" { case (Expression(lhs), CTLexer.`\\.\\+`(_), Expression(rhs)) =>
        ASTNode(".+", scala.List(lhs, rhs))
      },
      "matrixSub" { case (Expression(lhs), CTLexer.`\\.\\-`(_), Expression(rhs)) =>
        ASTNode(".-", scala.List(lhs, rhs))
      },
      "matrixMul" { case (Expression(lhs), CTLexer.`\\.\\*`(_), Expression(rhs)) =>
        ASTNode(".*", scala.List(lhs, rhs))
      },
      "matrixDiv" { case (Expression(lhs), CTLexer.`\\./`(_), Expression(rhs)) => ASTNode("./", scala.List(lhs, rhs)) },
      "uMinus" { case (CTLexer.`-`(_), Expression(rhs)) => ASTNode("-", scala.List(rhs)) },
      "transpose" { case (Expression(lhs), CTLexer.`'`(_)) => ASTNode("'", scala.List(lhs)) },
      { case CTLexer.int(i) => ASTNode(i.value.toString, Nil) },
      { case CTLexer.float(f) => ASTNode(f.value.toString, Nil) },
      { case CTLexer.string(s) => ASTNode(s.value, Nil) },
    )

    val Matrix: Rule[ASTNode] = rule:
      case (CTLexer.`\\[`(_), Arguments(args), CTLexer.`\\]`(_)) => ASTNode("matrix", args)

    val Comparator: Rule[String] = rule(
      { case CTLexer.`<`(_) => "<" },
      { case CTLexer.`>`(_) => ">" },
      { case CTLexer.`<=`(_) => "<=" },
      { case CTLexer.`>=`(_) => ">=" },
      { case CTLexer.`==`(_) => "==" },
      { case CTLexer.`!=`(_) => "!=" },
    )

    val AssignOp: Rule[String] = rule(
      { case CTLexer.`\\+=`(_) => "+=" },
      { case CTLexer.`-=`(_) => "-=" },
      { case CTLexer.`\\*=`(_) => "*=" },
      { case CTLexer.`/=`(_) => "/=" },
      { case CTLexer.`=`(_) => "=" },
    )

  given Resolutions[ASTPrinterParser.type] = resolutions(
    production.bareIf.after(CTLexer.`else`),
    CTLexer.`'`.before(
      production.uMinus,
      production.mul,
      production.div,
      production.matrixMul,
      production.matrixDiv,
    ),
    production.uMinus
      .before(
        CTLexer.`\\.\\*`,
        CTLexer.`\\./`,
        CTLexer.`\\*`,
        CTLexer.`/`,
      ),
    production.mul.before(CTLexer.`\\*`, CTLexer.`/`, CTLexer.`\\.\\*`, CTLexer.`\\./`),
    production.div.before(CTLexer.`\\*`, CTLexer.`/`, CTLexer.`\\.\\*`, CTLexer.`\\./`),
    production.matrixMul.before(CTLexer.`\\*`, CTLexer.`/`, CTLexer.`\\.\\*`, CTLexer.`\\./`),
    production.matrixDiv.before(CTLexer.`\\*`, CTLexer.`/`, CTLexer.`\\.\\*`, CTLexer.`\\./`),
    CTLexer.`\\*`.before(production.add, production.sub, production.matrixAdd, production.matrixSub),
    CTLexer.`/`.before(production.add, production.sub, production.matrixAdd, production.matrixSub),
    CTLexer.`\\.\\*`.before(production.add, production.sub, production.matrixAdd, production.matrixSub),
    CTLexer.`\\./`.before(production.add, production.sub, production.matrixAdd, production.matrixSub),
    production.add.before(CTLexer.`\\.\\+`, CTLexer.`\\.\\-`, CTLexer.`\\+`, CTLexer.`-`),
    production.sub.before(CTLexer.`\\.\\+`, CTLexer.`\\.\\-`, CTLexer.`\\+`, CTLexer.`-`),
    production.matrixAdd.before(CTLexer.`\\.\\+`, CTLexer.`\\.\\-`, CTLexer.`\\+`, CTLexer.`-`),
    production.matrixSub.before(CTLexer.`\\.\\+`, CTLexer.`\\.\\-`, CTLexer.`\\+`, CTLexer.`-`),
  )

  test("special functions, initializations") {
    withLazyReader("""
      A = zeros(5);  # create 5x5 matrix filled with zeros
      B = ones(7);   # create 7x7 matrix filled with ones
      I = eye(10);   # create 10x10 matrix filled with ones on diagonal and zeros elsewhere

      # initialize 3x3 matrix with specific values
      E1 = [ [1, 2, 3],
             [4, 5, 6],
             [7, 8, 9] ] ;

      A[1,3] = 0 ;

      x = 2;
      y = 2.5;
      """) { input =>
      val lexemes = CTLexer.tokenize(input).getOrThrow
      val result = ASTPrinterParser.parse(lexemes).getOrThrow

      val expected = ASTNode(
        "program",
        scala.List(
          ASTNode("=", scala.List(ASTNode("A", Nil), ASTNode("zeros", scala.List(ASTNode("5", Nil))))),
          ASTNode("=", scala.List(ASTNode("B", Nil), ASTNode("ones", scala.List(ASTNode("7", Nil))))),
          ASTNode("=", scala.List(ASTNode("I", Nil), ASTNode("eye", scala.List(ASTNode("10", Nil))))),
          ASTNode(
            "=",
            scala.List(
              ASTNode("E1", Nil),
              ASTNode(
                "matrix",
                scala.List(
                  ASTNode(
                    "matrix",
                    scala.List(
                      ASTNode("1", Nil),
                      ASTNode("2", Nil),
                      ASTNode("3", Nil),
                    ),
                  ),
                  ASTNode(
                    "matrix",
                    scala.List(
                      ASTNode("4", Nil),
                      ASTNode("5", Nil),
                      ASTNode("6", Nil),
                    ),
                  ),
                  ASTNode(
                    "matrix",
                    scala.List(
                      ASTNode("7", Nil),
                      ASTNode("8", Nil),
                      ASTNode("9", Nil),
                    ),
                  ),
                ),
              ),
            ),
          ),
          ASTNode(
            "=",
            scala.List(
              ASTNode("element", scala.List(ASTNode("A", Nil), ASTNode("1", Nil), ASTNode("3", Nil))),
              ASTNode("0", Nil),
            ),
          ),
          ASTNode("=", scala.List(ASTNode("x", Nil), ASTNode("2", Nil))),
          ASTNode("=", scala.List(ASTNode("y", Nil), ASTNode("2.5", Nil))),
        ),
      )

      assert(result == expected)
    }
  }

  test("assignment operators, binary operators, transposition") {
    withLazyReader("""
    C = -A;     # assignemnt with unary expression
    C = B' ;    # assignemnt with matrix transpose
    C = A+B ;   # assignemnt with binary addition
    C = A-B ;   # assignemnt with binary substraction
    C = A*B ;   # assignemnt with binary multiplication
    C = A/B ;   # assignemnt with binary division
    C = A.+B ;  # add element-wise A to B
    C = A.-B ;  # substract B from A
    C = A.*B ;  # multiply element-wise A with B
    C = A./B ;  # divide element-wise A by B

    C += B ;  # add B to C
    C -= B ;  # substract B from C
    C *= A ;  # multiply A with C
    C /= A ;  # divide A by C
    """) { input =>
      val lexemes = CTLexer.tokenize(input).getOrThrow
      val result = ASTPrinterParser.parse(lexemes).getOrThrow
      val expected = ASTNode(
        "program",
        scala.List(
          ASTNode("=", scala.List(ASTNode("C", Nil), ASTNode("-", scala.List(ASTNode("A", Nil))))),
          ASTNode("=", scala.List(ASTNode("C", Nil), ASTNode("'", scala.List(ASTNode("B", Nil))))),
          ASTNode("=", scala.List(ASTNode("C", Nil), ASTNode("+", scala.List(ASTNode("A", Nil), ASTNode("B", Nil))))),
          ASTNode("=", scala.List(ASTNode("C", Nil), ASTNode("-", scala.List(ASTNode("A", Nil), ASTNode("B", Nil))))),
          ASTNode("=", scala.List(ASTNode("C", Nil), ASTNode("*", scala.List(ASTNode("A", Nil), ASTNode("B", Nil))))),
          ASTNode("=", scala.List(ASTNode("C", Nil), ASTNode("/", scala.List(ASTNode("A", Nil), ASTNode("B", Nil))))),
          ASTNode("=", scala.List(ASTNode("C", Nil), ASTNode(".+", scala.List(ASTNode("A", Nil), ASTNode("B", Nil))))),
          ASTNode("=", scala.List(ASTNode("C", Nil), ASTNode(".-", scala.List(ASTNode("A", Nil), ASTNode("B", Nil))))),
          ASTNode("=", scala.List(ASTNode("C", Nil), ASTNode(".*", scala.List(ASTNode("A", Nil), ASTNode("B", Nil))))),
          ASTNode("=", scala.List(ASTNode("C", Nil), ASTNode("./", scala.List(ASTNode("A", Nil), ASTNode("B", Nil))))),
          ASTNode("+=", scala.List(ASTNode("C", Nil), ASTNode("B", Nil))),
          ASTNode("-=", scala.List(ASTNode("C", Nil), ASTNode("B", Nil))),
          ASTNode("*=", scala.List(ASTNode("C", Nil), ASTNode("A", Nil))),
          ASTNode("/=", scala.List(ASTNode("C", Nil), ASTNode("A", Nil))),
        ),
      )

      assert(result == expected)
    }
  }

  test("control flow instruction") {
    withLazyReader("""
    N = 10;
    M = 20;

    if(N==10)
        print("N==10");
    else if(N!=10)
        print("N!=10");


    if(N>5) {
        print("N>5");
    }
    else if(N>=0) {
        print("N>=0");
    }

    if(N<10) {
        print("N<10");
    }
    else if(N<=15)
        print("N<=15");

    k = 10;
    while(k>0)
        k = k - 1;

    while(k>0) {
        if(k<5)
            i = 1;
        else if(k<10)
            i = 2;
        else
            i = 3;

        k = k - 1;
    }


    for i = 1:N
      for j = i:M
        print(i, j);

    for i = 1:N {
        if(i<=N/16)
            print(i);
        else if(i<=N/8)
            break;
        else if(i<=N/4)
            continue;
        else if(i<=N/2)
            return 0;
    }
    """): input =>
      val lexemes = CTLexer.tokenize(input).getOrThrow
      val result = ASTPrinterParser.parse(lexemes).getOrThrow
      val expected = ASTNode(
        "program",
        scala.List(
          ASTNode("=", scala.List(ASTNode("N", Nil), ASTNode("10", Nil))),
          ASTNode("=", scala.List(ASTNode("M", Nil), ASTNode("20", Nil))),
          ASTNode(
            "IF",
            scala.List(
              ASTNode("==", scala.List(ASTNode("N", Nil), ASTNode("10", Nil))),
              ASTNode("block", scala.List(ASTNode("print", scala.List(ASTNode("N==10", Nil))))),
              ASTNode(
                "block",
                scala.List(
                  ASTNode(
                    "IF",
                    scala.List(
                      ASTNode("!=", scala.List(ASTNode("N", Nil), ASTNode("10", Nil))),
                      ASTNode("block", scala.List(ASTNode("print", scala.List(ASTNode("N!=10", Nil))))),
                    ),
                  ),
                ),
              ),
            ),
          ),
          ASTNode(
            "IF",
            scala.List(
              ASTNode(">", scala.List(ASTNode("N", Nil), ASTNode("5", Nil))),
              ASTNode(
                "block",
                scala.List(ASTNode("print", scala.List(ASTNode("N>5", Nil)))),
              ),
              ASTNode(
                "block",
                scala.List(
                  ASTNode(
                    "IF",
                    scala.List(
                      ASTNode(">=", scala.List(ASTNode("N", Nil), ASTNode("0", Nil))),
                      ASTNode(
                        "block",
                        scala.List(ASTNode("print", scala.List(ASTNode("N>=0", Nil)))),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
          ASTNode(
            "IF",
            scala.List(
              ASTNode("<", scala.List(ASTNode("N", Nil), ASTNode("10", Nil))),
              ASTNode(
                "block",
                scala.List(ASTNode("print", scala.List(ASTNode("N<10", Nil)))),
              ),
              ASTNode(
                "block",
                scala.List(
                  ASTNode(
                    "IF",
                    scala.List(
                      ASTNode("<=", scala.List(ASTNode("N", Nil), ASTNode("15", Nil))),
                      ASTNode("block", scala.List(ASTNode("print", scala.List(ASTNode("N<=15", Nil))))),
                    ),
                  ),
                ),
              ),
            ),
          ),
          ASTNode("=", scala.List(ASTNode("k", Nil), ASTNode("10", Nil))),
          ASTNode(
            "WHILE",
            scala.List(
              ASTNode(">", scala.List(ASTNode("k", Nil), ASTNode("0", Nil))),
              ASTNode(
                "block",
                scala.List(
                  ASTNode(
                    "=",
                    scala.List(
                      ASTNode("k", Nil),
                      ASTNode("-", scala.List(ASTNode("k", Nil), ASTNode("1", Nil))),
                    ),
                  ),
                ),
              ),
            ),
          ),
          ASTNode(
            "WHILE",
            scala.List(
              ASTNode(">", scala.List(ASTNode("k", Nil), ASTNode("0", Nil))),
              ASTNode(
                "block",
                scala.List(
                  ASTNode(
                    "IF",
                    scala.List(
                      ASTNode("<", scala.List(ASTNode("k", Nil), ASTNode("5", Nil))),
                      ASTNode(
                        "block",
                        scala.List(
                          ASTNode(
                            "=",
                            scala.List(ASTNode("i", Nil), ASTNode("1", Nil)),
                          ),
                        ),
                      ),
                      ASTNode(
                        "block",
                        scala.List(
                          ASTNode(
                            "IF",
                            scala.List(
                              ASTNode("<", scala.List(ASTNode("k", Nil), ASTNode("10", Nil))),
                              ASTNode(
                                "block",
                                scala.List(
                                  ASTNode(
                                    "=",
                                    scala.List(ASTNode("i", Nil), ASTNode("2", Nil)),
                                  ),
                                ),
                              ),
                              ASTNode(
                                "block",
                                scala.List(
                                  ASTNode(
                                    "=",
                                    scala.List(ASTNode("i", Nil), ASTNode("3", Nil)),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                  ASTNode(
                    "=",
                    scala.List(
                      ASTNode("k", Nil),
                      ASTNode("-", scala.List(ASTNode("k", Nil), ASTNode("1", Nil))),
                    ),
                  ),
                ),
              ),
            ),
          ),
          ASTNode(
            "FOR",
            scala.List(
              ASTNode("i", Nil),
              ASTNode(":", scala.List(ASTNode("1", Nil), ASTNode("N", Nil))),
              ASTNode(
                "block",
                scala.List(
                  ASTNode(
                    "FOR",
                    scala.List(
                      ASTNode("j", Nil),
                      ASTNode(":", scala.List(ASTNode("i", Nil), ASTNode("M", Nil))),
                      ASTNode(
                        "block",
                        scala.List(
                          ASTNode("print", scala.List(ASTNode("i", Nil), ASTNode("j", Nil))),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
          ASTNode(
            "FOR",
            scala.List(
              ASTNode("i", Nil),
              ASTNode(":", scala.List(ASTNode("1", Nil), ASTNode("N", Nil))),
              ASTNode(
                "block",
                scala.List(
                  ASTNode(
                    "IF",
                    scala.List(
                      ASTNode(
                        "<=",
                        scala.List(ASTNode("i", Nil), ASTNode("/", scala.List(ASTNode("N", Nil), ASTNode("16", Nil)))),
                      ),
                      ASTNode(
                        "block",
                        scala.List(
                          ASTNode("print", scala.List(ASTNode("i", Nil))),
                        ),
                      ),
                      ASTNode(
                        "block",
                        scala.List(
                          ASTNode(
                            "IF",
                            scala.List(
                              ASTNode(
                                "<=",
                                scala
                                  .List(ASTNode("i", Nil), ASTNode("/", scala.List(ASTNode("N", Nil), ASTNode("8", Nil)))),
                              ),
                              ASTNode("block", scala.List(ASTNode("BREAK", Nil))),
                              ASTNode(
                                "block",
                                scala.List(
                                  ASTNode(
                                    "IF",
                                    scala.List(
                                      ASTNode(
                                        "<=",
                                        scala.List(
                                          ASTNode("i", Nil),
                                          ASTNode("/", scala.List(ASTNode("N", Nil), ASTNode("4", Nil))),
                                        ),
                                      ),
                                      ASTNode("block", scala.List(ASTNode("CONTINUE", Nil))),
                                      ASTNode(
                                        "block",
                                        scala.List(
                                          ASTNode(
                                            "IF",
                                            scala.List(
                                              ASTNode(
                                                "<=",
                                                scala.List(
                                                  ASTNode("i", Nil),
                                                  ASTNode("/", scala.List(ASTNode("N", Nil), ASTNode("2", Nil))),
                                                ),
                                              ),
                                              ASTNode("block", scala.List(ASTNode("RETURN", scala.List(ASTNode("0", Nil))))),
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      )
      assert(result == expected)
  }
