package org.jikesrvm.atomicity;

import java.util.Comparator;

import org.jikesrvm.VM;
import org.vmmagic.pragma.Uninterruptible;

/** We sort {@code AVDNode} with transaction id. */
@Uninterruptible
public final class AVDNodeComparator implements Comparator<AVDNode> {

  @Override
  public int compare(AVDNode arg0, AVDNode arg1) {
    if (VM.VerifyAssertions) { VM._assert(arg0.transactionID != arg1.transactionID); }
    return (arg0.transactionID - arg1.transactionID);
  }

}
