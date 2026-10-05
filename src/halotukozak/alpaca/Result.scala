package halotukozak
package alpaca

/**
 * The outcome of tokenizing or parsing: the value, or the errors met on the way.
 *
 * Both cases carry the context (lexer or parser) as it was when the run ended. A [[Result.Failure]] may still hold a
 * value in `recovered`, when an error-handling strategy skipped past the errors and the run went on to the end;
 * otherwise `recovered` is `None`.
 *
 * {{{
 * MyParser.parse(lexemes) match
 *   case Result.Success(ctx, value) => println(value)
 *   case Result.Failure(ctx, recovered, errors) => errors.foreach(e => println(e.message))
 * }}}
 *
 * For Alpaca's own error types, `getOrThrow` returns the value or throws the errors as an exception
 * ([[LexerException]] for [[LexerError]]s, [[ParserException]] for [[ParserError]]s).
 *
 * @tparam Ctx the lexer or parser context type
 * @tparam A   the value: the lexemes for `tokenize`, the root rule's result for `parse`
 * @tparam E   the error type: [[LexerError]] for `tokenize`, [[ParserError]] for `parse`
 */
enum Result[+Ctx, +A, +E]:
  /** The context as it was when the run ended. */
  def ctx: Ctx

  /** The whole input was accepted and produced `value`. */
  case Success(ctx: Ctx, value: A)

  /**
   * Some input was not accepted; `errors` says what and where, in input order. `recovered` is the value produced
   * anyway when an error-handling strategy skipped past the errors, `None` when the run stopped at the first one.
   */
  case Failure(ctx: Ctx, recovered: Option[A], errors: ::[E])

  /** The value, or `None` if there were errors. */
  def toOption: Option[A] = this match
    case Success(_, value) => Some(value)
    case Failure(_, _, _) => None

  /** The value on the right, or the errors on the left. */
  def toEither: Either[::[E], A] = this match
    case Success(_, value) => Right(value)
    case Failure(_, _, errors) => Left(errors)
