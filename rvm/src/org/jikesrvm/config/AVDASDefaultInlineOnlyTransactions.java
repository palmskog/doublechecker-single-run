package org.jikesrvm.config;

import org.jikesrvm.VM;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDASDefaultInlineOnlyTransactions extends AVDASDefault {

  public AVDASDefaultInlineOnlyTransactions() {
    if (VM.VerifyAssertions) { VM._assert(inlineStartEndTransactions()); }
  }
  
  @Pure
  @Override
  public boolean inlineBarriers() { 
    return false; 
  }
  
}
