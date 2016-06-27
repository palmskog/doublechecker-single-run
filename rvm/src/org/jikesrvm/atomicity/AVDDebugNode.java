package org.jikesrvm.atomicity;

import org.jikesrvm.VM;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public final class AVDDebugNode extends AVDNode {

  public AVDDebugNode(RVMThread t, boolean isUnary) {
    super(t, isUnary);
  }
  
  /** This ctor creates a node that is supposed to represent a transaction. */
  public AVDDebugNode(RVMThread thread, int transID, int site, boolean isUnary, int id) {
    super(thread, transID, site, isUnary, id);
  }
  
  @Interruptible
  @Override
  protected void finalize() throws Throwable {
    if (VM.VerifyAssertions) { VM._assert(AVD.useDebugNodes()); }
    super.finalize(); // Don't miss this!
  }

}
