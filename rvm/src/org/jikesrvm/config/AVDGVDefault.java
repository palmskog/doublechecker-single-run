package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDGVDefault extends AVDGVICD {

  @Pure
  @Override
  public boolean performPhase2() {
    return true;
  }
  
}