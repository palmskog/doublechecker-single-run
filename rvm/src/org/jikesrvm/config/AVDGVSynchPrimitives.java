package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDGVSynchPrimitives extends AVDGVSlowPathHooks {

  @Pure
  @Override
  public boolean trackSynchronizationPrimitives() {
    return true;
  }

  @Pure
  @Override
  public boolean trackThreadSynchronizationPrimitives() {
    return true;
  }
  
}
