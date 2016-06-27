package org.jikesrvm.atomicity;

import java.util.HashMap;

import org.jikesrvm.VM;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;

/**
 * This class is a wrapper for storing last access information for each (obj+off).
 */
public final class LastAccessInfo {
  
  /** Last read set, since there can be multiple reads to (obj+off) */
  private HashMap<RVMThread, AVDNode> readSet;
  /** There can be only one last write */
  private AVDNode writeInfo;
  
  public LastAccessInfo() {
    readSet = new HashMap<RVMThread, AVDNode>();
    writeInfo = null;
  }
  
  public void clearReadSetInfo() {
    if (VM.VerifyAssertions) { VM._assert(readSet != null); }
    readSet.clear();
  }
  
  public AVDNode clearWriteInfo() {
    AVDNode temp = writeInfo;
    writeInfo = null;
    return temp;
  }
  
  public void clear() {
    clearReadSetInfo();
    clearWriteInfo();
  }
  
  @Inline
  public HashMap<RVMThread, AVDNode> getLastReadSet() {
    if (VM.VerifyAssertions) { VM._assert(readSet != null); }
    return readSet;
  }
  
  @Inline
  public AVDNode getLastWrite() {
    return writeInfo;
  }
  
  public AVDNode setLastWrite(AVDNode node) {
    if (VM.VerifyAssertions) { VM._assert(node != null); }
    AVDNode tmp = writeInfo;
    writeInfo = node;
    return tmp;
  }
  
  @Inline
  public void setLastReadSet(HashMap<RVMThread, AVDNode> set) {
    if (VM.VerifyAssertions) { 
      VM._assert(readSet != null);
      VM._assert(set != null);
      VM._assert(set.size() > 0);
    }
    readSet = set;
  }
  
  @Inline
  public void appendLastToReadSet(AVDNode node) {
    if (VM.VerifyAssertions) { 
      VM._assert(readSet != null);
      VM._assert(node != null);
    }
    readSet.put(node.octetThread, node);
  }
  
  @Inline
  public int sizeOfLastReadSet() {
    if (VM.VerifyAssertions) { VM._assert(readSet != null); }
    return readSet.size();
  }
  
}
