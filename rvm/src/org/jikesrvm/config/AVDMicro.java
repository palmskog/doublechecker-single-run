package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

/** This config is intended especially for microbenchmarks */
@Uninterruptible
public class AVDMicro extends AVDASDefault {
  
  @Pure
  @Override
  public boolean generateDotFile() {
    return true;
  }
  
  @Pure
  @Override
  public boolean useDebugNodes() {
    return true;
  }
  
  @Pure
  @Override
  public boolean includeSiteIDinReadWriteSet() {
    return true;
  }
  
  @Pure
  @Override
  public boolean writePhase2OutputToFile() {
    return true;
  }
  
}
