package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDIterativeRefinementStart extends AVDASDefault {

  @Pure
  @Override
  public boolean iterativeRefinementStart() {
    return true;
  }
  
}
