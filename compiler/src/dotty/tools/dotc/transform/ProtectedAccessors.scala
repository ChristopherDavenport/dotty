package dotty.tools.dotc
package transform

import core.Contexts._
import core.NameKinds._
import core.Symbols._
import core.Flags._
import core.Decorators._
import MegaPhase.MiniPhase
import config.Printers.transforms

/** Add accessors for all protected accesses. An accessor is needed if
 *  according to the rules of the JVM a protected class member is not accessible
 *  from the point of access, but is accessible if the access is from an enclosing
 *  class. In this point a public access method is placed in that enclosing class.
 */
object ProtectedAccessors {
  val name: String = "protectedAccessors"

  /** Is the current context's owner inside the access boundary established by `sym`? */
  def insideBoundaryOf(sym: Symbol)(using Context): Boolean =
    if (sym.is(JavaDefined))
      sym.is(JavaStatic) ||  // Java's static protected definitions are treated as public
      ctx.owner.enclosingPackageClass == sym.enclosingPackageClass
    else {
      // For Scala-defined symbols we currently allow private and protected accesses
      // from inner packages, and compensate by widening accessibility of such symbols to public.
      // It would be good if we could revisit this at some point.
      val boundary = sym.accessBoundary(sym.enclosingPackageClass)
      ctx.owner.isContainedIn(boundary) || ctx.owner.isContainedIn(boundary.linkedClass)
    }

  /** Do we need a protected accessor if the current context's owner
   *  is not in a subclass or subtrait of `sym`?
   */
  def needsAccessorIfNotInSubclass(sym: Symbol)(using Context): Boolean =
    sym.isTerm && sym.is(Protected) &&
    !sym.owner.is(Trait) && // trait methods need to be handled specially, are currently always public
    !insideBoundaryOf(sym)

  /** Do we need a protected accessor for accessing sym from the current context's owner? */
  def needsAccessor(sym: Symbol)(using Context): Boolean =
    needsAccessorIfNotInSubclass(sym) &&
    !ctx.owner.enclosingClass.derivesFrom(sym.owner)
}

class ProtectedAccessors extends MiniPhase {
  import ast.tpd._

  override def phaseName: String = ProtectedAccessors.name

  object Accessors extends AccessProxies {
    val insert: Insert = new Insert {
      def accessorNameKind = ProtectedAccessorName
      def needsAccessor(sym: Symbol)(using Context) = ProtectedAccessors.needsAccessor(sym)

      override def ifNoHost(reference: RefTree)(using Context): Tree = {
        val curCls = ctx.owner.enclosingClass
        transforms.println(i"${curCls.ownersIterator.toList}%, %")
        report.error(i"illegal access to protected ${reference.symbol.showLocated} from $curCls",
          reference.sourcePos)
        reference
      }
    }
  }

  override def transformIdent(tree: Ident)(using Context): Tree =
    Accessors.insert.accessorIfNeeded(tree)

  override def transformSelect(tree: Select)(using Context): Tree =
    Accessors.insert.accessorIfNeeded(tree)

  override def transformAssign(tree: Assign)(using Context): Tree =
    tree.lhs match {
      case lhs: RefTree if lhs.name.is(ProtectedAccessorName) =>
        cpy.Apply(tree)(Accessors.insert.useSetter(lhs), tree.rhs :: Nil)
      case _ =>
        tree
    }

  override def transformTemplate(tree: Template)(using Context): Tree =
    cpy.Template(tree)(body = Accessors.addAccessorDefs(tree.symbol.owner, tree.body))
}
