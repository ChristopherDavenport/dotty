import scala.quoted._

inline def eval(inline e: Int): Int = ${ evalExpr('e) }

private def evalExpr(using QuoteContext)(e: Expr[Int]): Expr[Int] = {
  e match {
    case '{ val y: Int = $x; $body(y): Int } =>
      evalExpr(Expr.betaReduce('{$body(${evalExpr(x)})}))
    case '{ ($x: Int) * ($y: Int) } =>
      (x, y) match
        case (Const(a), Const(b)) => Expr(a * b)
        case _ => e
    case _ => e
  }
}
