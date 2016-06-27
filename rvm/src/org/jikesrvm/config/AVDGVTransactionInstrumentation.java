package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDGVTransactionInstrumentation extends AVDGenerateViolations {

  @Pure
  @Override
  public boolean methodsAsTransactions() {
    return true;
  }
  
  @Pure
  @Override
  public boolean syncBlocksAsTransactions() {
    return false;
  }
  
  @Pure
  @Override
  public boolean inlineStartEndTransactions() {
    return true;
  }
  
}
