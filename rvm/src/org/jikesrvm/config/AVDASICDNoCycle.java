package org.jikesrvm.config;

import org.jikesrvm.VM;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDASICDNoCycle extends AVDASICD {

  public AVDASICDNoCycle() {
    if (VM.VerifyAssertions) { VM._assert(!performPhase2()); }
  }
  
  @Pure
  @Override
  public boolean invokeCycleDetection() {
    return false;
  }
  
}
