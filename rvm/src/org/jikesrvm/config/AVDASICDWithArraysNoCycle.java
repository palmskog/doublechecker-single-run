package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDASICDWithArraysNoCycle extends AVDASICDNoCycle {

  @Pure
  @Override
  public boolean instrumentArrays() {
    return true;
  }
  
}
