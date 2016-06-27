package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDCustom1 extends AVDASDefault {

  @Pure
  @Override
  public boolean checkBasicBlockNumbering() {
    return true;
  }
  
}
