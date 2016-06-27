package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDASCycleDetection extends AVDASCrossThreadEdge {

  @Pure
  @Override
  public boolean invokeCycleDetection() {
    return true;
  }
  
}
