package org.jikesrvm.atomicity;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

// No need to clear this linear array, we can just reset the array index to zero
@Uninterruptible
public class LinearArrayOfIndices implements Constants {
  
  @Inline
  public static void store(RVMThread thread, int value) {
    if (thread.indexOfLinearArray == thread.linearArray.length()) {
      growLinearArray(thread);
    }
    if (VM.VerifyAssertions) { VM._assert(value < thread.accessInfoHashTable.length()); }
    Address slot = ObjectReference.fromObject(thread.linearArray).toAddress().plus(thread.indexOfLinearArray << LOG_BYTES_IN_WORD);
    slot.store(value);
    thread.indexOfLinearArray++;
  }
  
  @NoInline
  private static void growLinearArray(RVMThread thread) {
    if (AVD.recordAVDStats()) {
      AVDStats.numLinearArrayGrowths.inc(1L);
    }
    WordArray oldArray = thread.linearArray;
    int oldSize = oldArray.length();
    int newSize = oldSize << 1; // Double the size
    WordArray newArray = createNewLinearArray(newSize);
    thread.linearArray = newArray;
    int i = 0;
    Word value;
    for ( ; i < oldSize; i++) {
      value = oldArray.get(i);
      if (VM.VerifyAssertions) { VM._assert(value.toInt() < thread.accessInfoHashTable.length()); }
      newArray.set(i, value);
    }
  }
  
  @UninterruptibleNoWarn
  public static WordArray createNewLinearArray(int newSize) {
    MemoryManager.startAllocatingInUninterruptibleCode();
    WordArray newArray = WordArray.create(newSize); // 4 byte/ 1 word cell size
    MemoryManager.stopAllocatingInUninterruptibleCode();
    return newArray;
  }

}
