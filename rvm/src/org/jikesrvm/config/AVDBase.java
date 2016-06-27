package org.jikesrvm.config;

import org.jikesrvm.VM;
import org.jikesrvm.atomicity.AVDAnalysis;
import org.jikesrvm.octet.ClientAnalysis;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public abstract class AVDBase extends OctetDefaultForAVD {

  public AVDBase() {
    // Hash table currently make use of a separate linear array to track occupied slots
    if (VM.VerifyAssertions) { VM._assert(testHashTableClear() ? useLinearArrayOfIndices() : true); }
  }
  
  /** Construct the client analysis to use. */
  @Interruptible
  @Override
  public ClientAnalysis constructClientAnalysis() { 
    return new AVDAnalysis(); 
  }
  
  @Pure
  @Override
  public boolean isAVDEnabled() {
    return true;
  }
  
}
