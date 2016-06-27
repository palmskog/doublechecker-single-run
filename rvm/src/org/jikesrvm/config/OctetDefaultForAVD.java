package org.jikesrvm.config;

import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

/** This class sets the modified features of Octet that AVD uses */
@Uninterruptible
public class OctetDefaultForAVD extends OctetDefault {

  @Pure 
  @Override
  public boolean forceUseCommunicationQueue() { 
    return true; 
  }
  
  @Pure
  @Override
  public boolean forceUseHoldState() { 
    return true; 
  }
  
  @Interruptible // since enum accesses apparently call interruptible methods
  @Pure
  @Override
  public RedundantBarrierRemover.AnalysisLevel overrideDefaultRedundantBarrierAnalysisLevel() { 
    return RedundantBarrierRemover.AnalysisLevel.NONE;
  }
 
  @Pure
  @Override
  public boolean instrumentLibraries() { 
    return false; 
  }

  @Pure
  @Override
  public boolean instrumentArrays() {
    return false;
  }
  
  @Pure
  @Override
  public boolean isFieldSensitiveAnalysis() {
    return true;
  }

  @Pure
  @Override
  public boolean needsSites() {
    return true;
  }
  
}
