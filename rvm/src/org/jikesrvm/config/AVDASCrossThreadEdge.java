package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDASCrossThreadEdge extends AVDASSynchPrimitives {

  @Pure
  @Override
  public boolean crossThreadEdgeCreationEnabled() {
    return true;
  }
   
  @Pure
  @Override
  public boolean recordConflictingAccess() {
    return true;
  }
  
}
