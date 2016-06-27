package org.jikesrvm.atomicity;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.RVMArray;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;

@Uninterruptible
public class CrossThreadAccess {

  /** Index in the read/write set till which we have to compute for proper hb relation
   * E.g., 5 indicates that we need to process till index 4 in the remote transaction before processing
   * <code>ownIndex</code>
   */
  int remoteIndex;
  RVMThread remoteThread;
  AVDNode remoteTransaction;
  /** Index of the conflicting access. This basically means that before <code>ownIndex</code> can be 
   * processed for dependence information, it is necessary to ensure that the entries in the read/write 
   * set of the remote transaction till at least the remoteIndex'th entry
   */
  int ownIndex;
  
  public CrossThreadAccess(RVMThread t, AVDNode id, int ix, int ox) {
    remoteIndex = ix;
    remoteThread = t;
    remoteTransaction = id;
    ownIndex = ox;
  }
  
  @UninterruptibleNoWarn
  public static CrossThreadAccess createAccessIdentifier(RVMThread t, AVDNode id, int ix, int ox) {
    MemoryManager.startAllocatingInUninterruptibleCode();
    CrossThreadAccess temp = new CrossThreadAccess(t, id, ix, ox);
    MemoryManager.stopAllocatingInUninterruptibleCode();
    return temp;
  }
  
  @UninterruptibleNoWarn
  public static CrossThreadAccess[] growCrossThreadArray(CrossThreadAccess[] oldArray, int oldSize, int newSize) {
    if (VM.VerifyAssertions) { VM._assert(oldSize > 0 && newSize > oldSize); }
    MemoryManager.startAllocatingInUninterruptibleCode();
    // There were NegativeArraySizeExceptions over here a few times.
    CrossThreadAccess[] newArray = new CrossThreadAccess[newSize];
    RVMArray.arraycopy(oldArray, 0, newArray, 0, oldSize);
    MemoryManager.stopAllocatingInUninterruptibleCode();
    return newArray;
  }
  
}
