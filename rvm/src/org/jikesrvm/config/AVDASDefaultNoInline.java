package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDASDefaultNoInline extends AVDASDefault {

  @Pure
  @Override
  public boolean inlineBarriers() { 
    return false; 
  }
  
  @Pure
  @Override
  public boolean inlineStartEndTransactions() {
    return false;
  }
  
}
