package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDASDefault extends AVDASICD {

  @Pure
  @Override
  public boolean performPhase2() {
    return true;
  }
  
}
