package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDGVSlowPathHooks extends AVDGVTransactionInstrumentation {

  @Pure
  @Override
  public boolean executeAVDSlowPathHooks() {
    return true;
  }
  
}