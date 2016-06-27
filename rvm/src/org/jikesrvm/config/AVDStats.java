package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDStats extends AVDASDefaultNoInline {

  @Pure
  @Override
  public boolean recordAVDStats() {
    return true;
  }
  
  @Pure
  @Override
  public boolean performPhase2() {
    return false;
  }
  
}
