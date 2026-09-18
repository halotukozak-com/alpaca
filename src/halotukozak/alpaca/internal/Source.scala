package halotukozak.alpaca.internal

import halotukozak.mcodec.MCodec

import scala.quoted.ToExprFactory

case class Source(line: Int, file: String) derives MCodec, ToExprFactory

object Source:
  def apply(using quotes: Quotes)(pos: quotes.reflect.Position): Source =
    Source(pos.startLine, pos.sourceFile.path)
