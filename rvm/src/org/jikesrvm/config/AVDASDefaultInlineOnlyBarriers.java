package org.jikesrvm.config;

import org.jikesrvm.VM;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDASDefaultInlineOnlyBarriers extends AVDASDefault {

  public AVDASDefaultInlineOnlyBarriers() {
    if (VM.VerifyAssertions) { VM._assert(inlineBarriers()); }
  }
  
  @Pure
  @Override
  public boolean inlineStartEndTransactions() {
    return false;
  }
}
