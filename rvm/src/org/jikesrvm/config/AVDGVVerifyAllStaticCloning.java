package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDGVVerifyAllStaticCloning extends AVDGVVerifyTransactionInstrumentation {

  @Pure
  @Override
  public boolean verifyAVDBarrierCall() {
    return true;
  }
  
}
