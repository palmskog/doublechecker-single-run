package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDGVPerFieldMetadata extends AVDGVCycleDetection {

  @Pure
  @Override
  public boolean addPerFieldMetadata() {
    return true;
  }

}