package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDGVICD extends AVDGVPerFieldMetadata {

  /** In the default setting, we will track read/write sets for Phase 2. */
  @Pure
  @Override
  public boolean logRdWrAccesses() {
    return true;
  }
  
  @Pure
  @Override
  public boolean traceReadWriteSetDuringWeakReferencePhase() {
    return true;
  }
  
  /** Use hash table filter to record accesses, this is useful only if rd/wr accesses are 
   * being tracked. There is an assertion to check this.
   * */
  @Pure
  @Override
  public boolean recordAccessInfoInHashTable() {
    return true;
  }
  
  @Pure
  @Override
  public boolean useLinearArrayOfIndices() {
    return true;
  }

}