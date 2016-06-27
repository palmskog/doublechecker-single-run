package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDGVCycleDetection extends AVDGVCrossThreadEdge {

  @Pure
  @Override
  public boolean invokeCycleDetection() {
    return true;
  }
  
}