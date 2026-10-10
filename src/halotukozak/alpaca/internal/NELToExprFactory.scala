package halotukozak
package alpaca.internal

import scala.quoted.*

// $COVERAGE-OFF$
private[internal] given [A: ToExprFactory] => ToExprFactory[NEL[A]]:
  override def apply()(using Type[NEL[A]]): ToExpr[NEL[A]] = new ToExpr[NEL[A]]:
    override def apply(x: NEL[A])(using quotes: Quotes): Expr[NEL[A]] =
      import quotes.reflect.*
      TypeRepr.of[NEL[A]].typeArgs.head.asType match
        case '[t] =>
          given Type[A] = summon[Type[t]].asInstanceOf[Type[A]]
          given ToExpr[A] = summon[ToExprFactory[A]]()
          NEL.toExprImpl(x)
// $COVERAGE-ON$
