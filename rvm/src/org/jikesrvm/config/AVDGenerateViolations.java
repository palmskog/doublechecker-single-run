package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

/** This class shouldn't be used for experiments. */
@Uninterruptible
public abstract class AVDGenerateViolations extends AVDBase {
  
  @Pure
  @Override
  public boolean isPerformanceRun() {
    return false;
  }

}
