package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

/** This class shouldn't be used for experiments. */
@Uninterruptible
public abstract class AVDAtomicitySpecifications extends AVDBase {
  
  @Pure
  @Override
  public boolean isPerformanceRun() {
    return true;
  }

}
