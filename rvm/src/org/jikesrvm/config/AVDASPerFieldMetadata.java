package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDASPerFieldMetadata extends AVDASCycleDetection {

  @Pure
  @Override
  public boolean addPerFieldMetadata() {
    return true;
  }
  
}
