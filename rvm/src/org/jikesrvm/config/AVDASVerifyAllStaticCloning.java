package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDASVerifyAllStaticCloning extends AVDASVerifyTransactionInstrumentation {

  @Pure
  @Override
  public boolean verifyAVDBarrierCall() {
    return true;
  }
  
}
