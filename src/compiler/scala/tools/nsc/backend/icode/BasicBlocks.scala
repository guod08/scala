/* NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.nsc
package backend
package icode

import scala.collection.{ mutable, immutable }
import mutable.ListBuffer
import backend.icode.analysis.ProgramPoint
import scala.language.postfixOps

trait BasicBlocks {
  self: ICodes =>

  import opcodes._
  import global._

  /** Override Array creation for efficiency (to not go through reflection). */
  private implicit val instructionTag: scala.reflect.ClassTag[Instruction] = new scala.reflect.ClassTag[Instruction] {
    def runtimeClass: java.lang.Class[Instruction] = classOf[Instruction]
    final override def newArray(len: Int): Array[Instruction] = new Array[Instruction](len)
  }

  object NoBasicBlock extends BasicBlock(-1, null)

  /** This class represents a basic block. Each
   *  basic block contains a list of instructions that are
   *  either executed all, or none. No jumps
   *  to/from the "middle" of the basic block are allowed (modulo exceptions).
   */
  class BasicBlock(val label: Int, val method: IMethod) extends ProgramPoint[BasicBlock] {
    outer =>

    import BBFlags._

    def code = if (method eq null) NoCode else method.code

    private final class SuccessorList() {
      private var successors: List[BasicBlock] = Nil
      /** This method is very hot! Handle with care. */
      private def updateConserve() {
        var lb: ListBuffer[BasicBlock]              = null
        var matches                                 = 0
        var remaining                               = successors
        val direct                                  = directSuccessors
        var scratchHandlers: List[ExceptionHandler] = method.exh
        var scratchBlocks: List[BasicBlock]         = direct

        def addBlock(bb: BasicBlock) {
          if (matches < 0)
            lb += bb
          else if (remaining.isEmpty || bb != remaining.head) {
            lb = ListBuffer[BasicBlock]() ++= (successors take matches) += bb
            matches = -1
          }
          else {
            matches += 1
            remaining = remaining.tail
          }
        }

        while (scratchBlocks ne Nil) {
          addBlock(scratchBlocks.head)
          scratchBlocks = scratchBlocks.tail
        }
        /* Return a list of successors for 'b' that come from exception handlers
         * covering b's (non-exceptional) successors. These exception handlers
         * might not cover 'b' itself. This situation corresponds to an
         * exception being thrown as the first thing of one of b's successors.
         */
        while (scratchHandlers ne Nil) {
          val handler = scratchHandlers.head
          if (handler covers outer)
            addBlock(handler.startBlock)

          scratchBlocks = direct
          while (scratchBlocks ne Nil) {
            if (handler covers scratchBlocks.head)
              addBlock(handler.startBlock)
            scratchBlocks = scratchBlocks.tail
          }
          scratchHandlers = scratchHandlers.tail
        }
        // Blocks did not align: create a new list.
        if (matches < 0)
          successors = lb.toList
        // Blocks aligned, but more blocks remain.  Take a prefix of the list.
        else if (remaining.nonEmpty)
          successors = successors take matches
        // Otherwise the list is unchanged, leave it alone.
      }

      /** This is called millions of times: it is performance sensitive. */
      def updateSuccs() {
        if (isEmpty) {
          if (successors.nonEmpty)
            successors = Nil
        }
        else updateConserve()
      }
      def toList = successors
    }

    /** Flags of this basic block. */
    private[this] var flags: Int = 0

    /** Does this block have the given flag? */
    def hasFlag(flag: Int): Boolean = (flags & flag) != 0

    /** Set the given flag. */
    private def setFlag(flag: Int): Unit = flags |= flag
    private def resetFlag(flag: Int) {
      flags &= ~flag
    }

    /** Is this block closed? */
    def closed: Boolean = hasFlag(CLOSED)
    def closed_=(b: Boolean) = if (b) setFlag(CLOSED) else resetFlag(CLOSED)

    /** When set, the `emit` methods will be ignored. */
    def ignore: Boolean = hasFlag(IGNORING)
    def ignore_=(b: Boolean) = if (b) setFlag(IGNORING) else resetFlag(IGNORING)

    /** Is this block the head of a while? */
    def loopHeader = hasFlag(LOOP_HEADER)
    def loopHeader_=(b: Boolean) =
      if (b) setFlag(LOOP_HEADER) else resetFlag(LOOP_HEADER)

    /** Is this block the start block of an exception handler? */
    def exceptionHandlerStart = hasFlag(EX_HEADER)
    def exceptionHandlerStart_=(b: Boolean) =
      if (b) setFlag(EX_HEADER) else resetFlag(EX_HEADER)

    /** Has this basic block been modified since the last call to 'successors'? */
    def touched = hasFlag(DIRTYSUCCS)
    def touched_=(b: Boolean) = if (b) {
      setFlag(DIRTYSUCCS | DIRTYPREDS)
    } else {
      resetFlag(DIRTYSUCCS | DIRTYPREDS)
    }

    // basic blocks start in a dirty state
    setFlag(DIRTYSUCCS | DIRTYPREDS)

    /** Cached predecessors. */
    var preds: List[BasicBlock] = Nil

    /** Local variables that are in scope at entry of this basic block. Used
     *  for debugging information.
     */
    val varsInScope: mutable.Set[Local] = new mutable.LinkedHashSet()

    /** ICode instructions, used as temporary storage while emitting code.
     * Once closed is called, only the `instrs` array should be used.
     */
    private var instructionList: List[Instruction] = Nil
    private var instrs: Array[Instruction] = _

    def take(n: Int): Seq[Instruction] =
      if (closed) instrs take n else instructionList takeRight n reverse

    def toList: List[Instruction] =
      if (closed) instrs.toList else instructionList.reverse

    /** Return an iterator over the instructions in this basic block. */
    def iterator: Iterator[Instruction] =
      if (closed) instrs.iterator else instructionList.reverseIterator

    /** return the underlying array of instructions */
    def getArray: Array[Instruction] = {
      assert(closed, this)
      instrs
    }

    def fromList(is: List[Instruction]) {
      code.touched = true
      instrs = is.toArray
      closed = true
    }

    /** Return the index of inst. Uses reference equality.
     *  Returns -1 if not found.
     */
    def indexOf(inst: Instruction): Int = {
      assert(closed, this)
      instrs indexWhere (_ eq inst)
    }

    /** Apply a function to all the instructions of the block. */
    final def foreach[U](f: Instruction => U) = {
      if (!closed) dumpMethodAndAbort(method, this)
      else instrs foreach f

      // !!! If I replace "instrs foreach f" with the following:
      // var i = 0
      // val len = instrs.length
      // while (i < len) {
      //   f(instrs(i))
      //   i += 1
      // }
      //
      // Then when compiling under -optimise, quick.plugins fails as follows:
      //
      // quick.plugins:
      //     [mkdir] Created dir: /scratch/trunk6/build/quick/classes/continuations-plugin
      // [scalacfork] Compiling 5 files to /scratch/trunk6/build/quick/classes/continuations-plugin
      // [scalacfork] error: java.lang.VerifyError: (class: scala/tools/nsc/typechecker/Implicits$ImplicitSearch, method: typedImplicit0 signature: (Lscala/tools/nsc/typechecker/Implicits$ImplicitInfo;Z)Lscala/tools/nsc/typechecker/Implicits$SearchResult;) Incompatible object argument for function call
      // [scalacfork]   at scala.tools.nsc.typechecker.Implicits$class.inferImplicit(Implicits.scala:67)
      // [scalacfork]   at scala.tools.nsc.Global$$anon$1.inferImplicit(Global.scala:419)
      // [scalacfork]   at scala.tools.nsc.typechecker.Typers$Typer.wrapImplicit$1(Typers.scala:170)
      // [scalacfork]   at scala.tools.nsc.typechecker.Typers$Typer.inferView(Typers.scala:174)
      // [scalacfork]   at scala.tools.nsc.typechecker.Typers$Typer.adapt(Typers.scala:963)
      // [scalacfork]   at scala.tools.nsc.typechecker.Typers$Typer.typed(Typers.scala:4378)
      //
      // This is bad and should be understood/eliminated.
    }

    /** The number of instructions in this basic block so far. */
    def length = if (closed) instrs.length else instructionList.length
    def size = length

    /** Return the n-th instruction. */
    def apply(n: Int): Instruction =
      if (closed) instrs(n) else instructionList.reverse(n)

    ///////////////////// Substitutions ///////////////////////

    /**
     * Replace the instruction at the given position. Used by labels when they are anchored.
     * The replacing instruction is given the nsc.util.Position of the instruction it replaces.
     */
    def replaceInstruction(pos: Int, instr: Instruction): Boolean = {
      assert(closed, "Instructions can be replaced only after the basic block is closed")
      instr.setPos(instrs(pos).pos)
      instrs(pos) = instr
      code.touched = true
      true
    }

    /**
     * Replace the given instruction with the new one.
     * Returns `true` if it actually changed something.
     * The replacing instruction is given the nsc.util.Position of the instruction it replaces.
     */
    def replaceInstruction(oldInstr: Instruction, newInstr: Instruction): Boolean = {
      assert(closed, "Instructions can be replaced only after the basic block is closed")

      indexOf(oldInstr) match {
        case -1   => false
        case idx  =>
          newInstr setPos oldInstr.pos
          instrs(idx) = newInstr
          code.touched = true
          true
      }
    }

    /** Replaces `oldInstr` with `is`. It does not update
     *  the position field in the newly inserted instructions, so it behaves
     *  differently than the one-instruction versions of this function.
     */
    def replaceInstruction(oldInstr: Instruction, is: List[Instruction]): Boolean = {
      assert(closed, "Instructions can be replaced only after the basic block is closed")

      indexOf(oldInstr) match {
        case -1   => false
        case idx  =>
          instrs = instrs.patch(idx, is, 1)
          code.touched = true
          true
      }
    }

    /** Removes instructions found at the given positions.
     */
    def removeInstructionsAt(positions: Int*) {
      assert(closed, this)
      instrs = instrs.indices.toArray filterNot positions.toSet map instrs
      code.touched = true
    }

    /** Remove the last instruction of this basic block. It is
     *  fast for an open block, but slower when the block is closed.
     */
    def removeLastInstruction() {
      if (closed)
        removeInstructionsAt(length)
      else {
        instructionList = instructionList.tail
        code.touched = true
      }
    }

    /** Replaces all instructions found in the map.
     */
    def subst(map: Map[Instruction, Instruction]): Unit =
      if (!closed)
        instructionList = instructionList map (x => map.getOrElse(x, x))
      else
        instrs.zipWithIndex collect {
          case (oldInstr, i) if map contains oldInstr =>
            // SI-6288 clone important here because `replaceInstruction` assigns
            // a position to `newInstr`. Without this, a single instruction can
            // be added twice, and the position last position assigned clobbers
            // all previous positions in other usages.
            val newInstr = map(oldInstr).clone()
            code.touched |= replaceInstruction(i, newInstr)
        }

    ////////////////////// Emit //////////////////////


    /** Add a new instruction at the end of the block,
     *  using the same source position as the last emitted instruction
     */
    def emit(instr: Instruction) {
      val pos = if (instructionList.isEmpty) NoPosition else instructionList.head.pos
      emit(instr, pos)
    }

    /** Emitting does not set touched to true. During code generation this is a hotspot and
     *  setting the flag for each emit is a waste. Caching should happen only after a block
     *  is closed, which sets the DIRTYSUCCS flag.
     */
    def emit(instr: Instruction, pos: Position) {
      assert(!closed || ignore, this)

      if (ignore) {
        if (settings.debug) {
          /* Trying to pin down what it's likely to see after a block has been
           * put into ignore mode so we hear about it if there's a problem.
           */
          instr match {
            case JUMP(_) | RETURN(_) | THROW(_) | SCOPE_EXIT(_)                  => // ok
            case STORE_LOCAL(local) if nme.isExceptionResultName(local.sym.name) => // ok
            case x                                                               => log("Ignoring instruction, possibly at our peril, at " + pos + ": " + x)
          }
        }
      }
      else {
        instr.setPos(pos)
        instructionList ::= instr
      }
    }

    def emit(is: Seq[Instruction]) {
      is foreach (i => emit(i, i.pos))
    }

    /** The semantics of this are a little odd but it's designed to work
     *  seamlessly with the existing code.  It emits each supplied instruction,
     *  then closes the block.  The odd part is that if the instruction has
     *  pos == NoPosition, it calls the 1-arg emit, but otherwise it calls
     *  the 2-arg emit.  This way I could retain existing behavior exactly by
     *  calling setPos on any instruction using the two arg version which
     *  I wanted to include in a call to emitOnly.
     */
    def emitOnly(is: Instruction*) {
      is foreach (i => if (i.pos == NoPosition) emit(i) else emit(i, i.pos))
      this.close()
    }

    /** do nothing if block is already closed */
    def closeWith(instr: Instruction) {
      if (!closed) {
        emit(instr)
        close()
      }
    }

    def closeWith(instr: Instruction, pos: Position) {
      if (!closed) {
        emit(instr, pos)
        close()
      }
    }

    /** Close the block */
    def close() {
      assert(!closed || ignore, this)
      if (ignore && closed) { // redundant `ignore &&` for clarity -- we should never be in state `!ignore && closed`
        // not doing anything to this block is important...
        // because the else branch reverses innocent blocks, which is wrong when they're in ignore mode (and closed)
        // reversing the instructions when (closed && ignore) wreaks havoc for nested label jumps (see comments in genLoad)
      } else {
        closed = true
        setFlag(DIRTYSUCCS)
        instructionList = instructionList.reverse
        instrs = instructionList.toArray
        if (instructionList.isEmpty) {
          debuglog(s"Removing empty block $this")
          code removeBlock this
        }
      }
    }

    /**
     * if cond is true, closes this block, entersIgnoreMode, and removes the block from
     * its list of blocks. Used to allow a block to be started and then cancelled when it
     * is discovered to be unreachable.
     */
    def killIf(cond: Boolean) {
      if (!settings.YdisableUnreachablePrevention && cond) {
        debuglog(s"Killing block $this")
        assert(instructionList.isEmpty, s"Killing a non empty block $this")
        // only checked under debug because fetching predecessor list is moderately expensive
        if (settings.debug)
          assert(predecessors.isEmpty, s"Killing block $this which is referred to from ${predecessors.mkString}")

        close()
        enterIgnoreMode()
      }
    }

    /**
     * Same as killIf but with the logic of the condition reversed
     */
    def killUnless(cond: Boolean) {
      this killIf !cond
    }

    def open() {
      assert(closed, this)
      closed = false
      ignore = false
      touched = true
      instructionList = instructionList.reverse  // prepare for appending to the head
    }

    def clear() {
      instructionList = Nil
      instrs = null
      preds  = Nil
    }

    final def isEmpty = instructionList.isEmpty
    final def nonEmpty = !isEmpty

    /** Enter ignore mode: new 'emit'ted instructions will not be
     *  added to this basic block. It makes the generation of THROW
     *  and RETURNs easier.
     */
    def enterIgnoreMode() = {
      ignore = true
    }

    /** Return the last instruction of this basic block. */
    def lastInstruction =
      if (closed) instrs(instrs.length - 1)
      else instructionList.head

    def exceptionSuccessors: List[BasicBlock] =
      exceptionSuccessorsForBlock(this)

    def exceptionSuccessorsForBlock(block: BasicBlock): List[BasicBlock] =
      method.exh collect { case x if x covers block => x.startBlock }

    /** Cached value of successors. Must be recomputed whenever a block in the current method is changed. */
    private val succs = new SuccessorList

    def successors: List[BasicBlock] = {
      if (touched) {
        succs.updateSuccs()
        resetFlag(DIRTYSUCCS)
      }
      succs.toList
    }

    def directSuccessors: List[BasicBlock] =
      if (isEmpty) Nil else lastInstruction match {
        case JUMP(whereto)            => whereto :: Nil
        case CJUMP(succ, fail, _, _)  => fail :: succ :: Nil
        case CZJUMP(succ, fail, _, _) => fail :: succ :: Nil
        case SWITCH(_, labels)        => labels
        case RETURN(_)                => Nil
        case THROW(_)                 => Nil
        case _                        =>
          if (closed)
            devWarning(s"$lastInstruction/${lastInstruction.getClass.getName} is not a control flow instruction")

          Nil
      }

    /** Returns the predecessors of this block.     */
    def predecessors: List[BasicBlock] = {
      if (hasFlag(DIRTYPREDS)) {
        resetFlag(DIRTYPREDS)
        preds = code.blocks.iterator filter (_.successors contains this) toList
      }
      preds
    }

    override def equals(other: Any): Boolean = other match {
      case that: BasicBlock => (that.label == label) && (that.code == code)
      case _ => false
    }

    override def hashCode = label * 41 + code.hashCode

    private def succString = if (successors.isEmpty) "[S: N/A]" else successors.distinct.mkString("[S: ", ", ", "]")
    private def predString = if (predecessors.isEmpty) "[P: N/A]" else predecessors.distinct.mkString("[P: ", ", ", "]")

    override def toString(): String = "" + label

    def blockContents = {
      def posStr(p: Position) = if (p.isDefined) p.line.toString else "<??>"
      val xs = this.toList map (instr => posStr(instr.pos) + "\t" + instr)
      xs.mkString(fullString + " {\n  ", "\n  ", "\n}")
    }
    def predContents = predecessors.map(_.blockContents).mkString(predecessors.size + " preds:\n", "\n", "\n")
    def succContents = successors.map(_.blockContents).mkString(successors.size + " succs:\n", "\n", "\n")

    def fullString: String = List("Block", label, succString, predString, flagsString) mkString " "
    def flagsString: String = BBFlags.flagsToString(flags)
  }
}

object BBFlags {
  /** This block is a loop header (was translated from a while). */
  final val LOOP_HEADER = (1 << 0)

  /** Ignoring mode: emit instructions are dropped. */
  final val IGNORING    = (1 << 1)

  /** This block is the header of an exception handler. */
  final val EX_HEADER   = (1 << 2)

  /** This block is closed. No new instructions can be added. */
  final val CLOSED      = (1 << 3)

  /** Code has been changed, recompute successors. */
  final val DIRTYSUCCS  = (1 << 4)

  /** Code has been changed, recompute predecessors. */
  final val DIRTYPREDS  = (1 << 5)

  val flagMap = Map[Int, String](
    LOOP_HEADER -> "loopheader",
    IGNORING    -> "ignore",
    EX_HEADER   -> "exheader",
    CLOSED      -> "closed",
    DIRTYSUCCS  -> "dirtysuccs",
    DIRTYPREDS  -> "dirtypreds"
  )
  def flagsToString(flags: Int) = {
    flagMap collect { case (bit, name) if (bit & flags) != 0 => "<" + name + ">" } mkString " "
  }
}
