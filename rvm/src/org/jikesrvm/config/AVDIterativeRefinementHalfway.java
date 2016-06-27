package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDIterativeRefinementHalfway extends AVDASDefault {

  @Pure
  @Override
  public boolean iterativeRefinementHalfway() {
    return true;
  }
  
}
