package alpaca
package integration.scalaparser

/**
 * Lexer for a subset of Scala expressions.
 *
 * Tokens follow the Scala 3 specification lexical syntax
 * (https://docs.scala-lang.org/scala3/reference/syntax.html).
 * Multi-character tokens are listed before single-character ones so the
 * longest match wins. Keywords use a word-boundary assertion so they do
 * not match as prefixes of identifiers (e.g. `if` must not match inside
 * `iffy`).
 */
val ScalaLexer = lexer:
  // Whitespace and single-line comments are ignored
  case _ @ "[ \t\r\n]+" => Token.Ignored
  case _ @ "//[^\n]*\n?" => Token.Ignored

  // Keywords – word-boundary assertion prevents matching identifier prefixes
  case "if\\b" => Token["if"]
  case "else\\b" => Token["else"]
  case "val\\b" => Token["val"]
  case "var\\b" => Token["var"]
  case "def\\b" => Token["def"]
  case "class\\b" => Token["class"]
  case "object\\b" => Token["object"]
  case "trait\\b" => Token["trait"]
  case "match\\b" => Token["match"]
  case "case\\b" => Token["case"]
  case "new\\b" => Token["new"]
  case "extends\\b" => Token["extends"]
  case "with\\b" => Token["with"]
  case "while\\b" => Token["while"]
  case "throw\\b" => Token["throw"]
  case "return\\b" => Token["return"]
  case "import\\b" => Token["import"]
  // Modifiers (Scala 3 spec: LocalModifier / AccessModifier / Modifier)
  case "private\\b" => Token["private"]
  case "protected\\b" => Token["protected"]
  case "final\\b" => Token["final"]
  case "sealed\\b" => Token["sealed"]
  case "abstract\\b" => Token["abstract"]
  case "override\\b" => Token["override"]
  case "lazy\\b" => Token["lazy"]
  case "implicit\\b" => Token["implicit"]

  case "true\\b" => Token["true"]
  case "false\\b" => Token["false"]
  case "null\\b" => Token["null"]

  // Multi-character operators (must come before single-character ones)
  case "==" => Token["eqeq"]
  case "!=" => Token["neq"]
  case "<=" => Token["lte"]
  case ">=" => Token["gte"]
  case "&&" => Token["and"]
  case "\\|\\|" => Token["or"]
  case "=>" => Token["arrow"]

  // Wildcard — named because `_` is reserved in Scala identifiers
  case "_" => Token["wildcard"]

  // Single-character operators and punctuation
  case literal @ ("\\+" | "-" | "\\*" | "/" | "%" | "<" | ">" | "!" | "=" | "\\." | "," | ";" | ":" | "@" | "\\|" |
      "\\(" | "\\)" | "\\{" | "\\}" | "\\[" | "\\]") =>
    Token[literal.type]

  // Floating-point literals (before integers to ensure correct matching)
  case x @ """(\d+\.\d+|\.\d+)([eE][+-]?\d+)?""" => Token["floatLit"](x.toDouble)

  // Integer literals
  case x @ """\d+""" => Token["intLit"](x.toLong)

  // Character literals — simplified: store the raw middle content as String.
  // Single unescaped char: `'a'` => "a". Escape sequences like `'\n'` stored
  // as the 2-char String "\\n" — the caller post-processes if it cares.
  case x @ """'(\\.|[^'])'""" => Token["charLit"](x.slice(1, x.length - 1))

  // Interpolated string literals: s"..." / f"..." / raw"..."
  // The prefix identifier and the raw contents are kept verbatim; `$var` /
  // `${expr}` splices are NOT re-lexed — consumer decides.
  case x @ """(s|f|raw)"(\\.|[^"])*"""" => Token["interpStr"](x)

  // String literals (basic, supports backslash-escape sequences)
  case x @ """"(\\.|[^"])*"""" => Token["stringLit"](x.slice(1, x.length - 1))

  // Identifiers (must come after keywords)
  case x @ "[a-zA-Z_][a-zA-Z0-9_]*" => Token["id"](x)
