package org.jikesrvm.atomicity;

import org.vmmagic.pragma.Inline;

public final class ObjectWrapper {

  private final int objAddress;
  /** GC cycle num, num == 0 means object is still live, num > 0 indicates that the object is dead */
  private final int gcCycleNumber; 
  
  public ObjectWrapper() {
    objAddress = 0;
    gcCycleNumber = 0; // GC cycle number starts from 1
  }
  
  public ObjectWrapper(int address, int number) {
    objAddress = address;
    gcCycleNumber = number;
  }
  
  @Override
  public boolean equals(Object o) {
    if (o ==  null) {
      return false;
    }
    if (this == o) {
      return true;
    }
    if (o instanceof ObjectWrapper) {
      return ( (this.objAddress == ((ObjectWrapper)o).objAddress) &&
               (this.gcCycleNumber == ((ObjectWrapper)o).gcCycleNumber) );
    }
    return false;
  }
  
  @Override
  public int hashCode() {
    return (41 * (41 + objAddress)) + gcCycleNumber;
  }
  
  @Inline
  public int getObjectAddress() {
    return objAddress;
  }
  
  @Inline
  public int getGCCycleNumber() {
    return gcCycleNumber;
  }
  
}
