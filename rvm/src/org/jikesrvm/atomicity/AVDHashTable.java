package org.jikesrvm.atomicity;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.runtime.Memory;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Extent;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

@Uninterruptible
public final class AVDHashTable implements Constants {
  
  private static final int LOG_CELL_SIZE_IN_BYTES = 2; // 2^2 == 4
  private static final int CELL_SIZE_IN_BYTES = 1 << LOG_CELL_SIZE_IN_BYTES; // 4 bytes or 1 word
  static final int LOG_NUM_WORDS_IN_CELL = 0;
  private static final int NUM_WORDS_IN_CELL = 1 << LOG_NUM_WORDS_IN_CELL; // 1 word
  private static final Word EMPTY = Word.zero();
  
  private static final int MAX_RETRIES_BEFORE_GROWING = 13;

  private static final int INITIAL_HASHTABLE_SLOTS = 5759; // Prime number
  public static final int INITIAL_HASHTABLE_SIZE = NUM_WORDS_IN_CELL * INITIAL_HASHTABLE_SLOTS; 
  
  // Constants for storing read/write access information
  // Write: MSB is 1 (set) 
  private static final int NUM_BITS_TO_STORE_ACCESS_TYPE = 1;
  private static final int BITS_TO_SHIFT_FOR_ACCESS_TYPE = BITS_IN_WORD - NUM_BITS_TO_STORE_ACCESS_TYPE; // 31
  private static final Word WRITE_BIT_MASK = Word.one().lsh(BITS_TO_SHIFT_FOR_ACCESS_TYPE); // 100...00
  private static final Word XOR_MASK = WRITE_BIT_MASK.minus(Word.one()); // 01111...111
  
  /**
   * This method is supposed to add access info if it is not already contained in the hash table
   * @return true if the field info has been added/updated in the hash table, otherwise false
   */
  // AVD: TODO: Decide whether to inline or not inline the hash table fast path. Avrora9 seems to degrade if this is 
  // inlined.
  @Inline
  public static void addToHashTableFastPath(RVMThread thread, Address objBaseAddress, int fieldOffset, /*Address fieldAddr,*/ 
      int type/*, int siteID*/) {
    if (VM.VerifyAssertions) { VM._assert(AVD.isAnyRead(type)); }
    Address fieldAddr = objBaseAddress.plus(Offset.fromIntSignExtend(fieldOffset));
    WordArray hashTableRef = thread.accessInfoHashTable;
    // This will have to change if each hash table cell size is no longer one word 
    int hashTableSize = hashTableRef.length();
    int key = getKey(fieldAddr);
    int primaryIndex = primaryHashFunc(key, hashTableSize);
    Address hashTableSlot = ObjectReference.fromObject(hashTableRef).toAddress().plus(primaryIndex << LOG_CELL_SIZE_IN_BYTES);
    Word value = hashTableSlot.loadWord(); // Load the field address
    if (value.xor(fieldAddr.toWord()).isZero() /* && type.isAnyRead()*/) { 
      // Field already exists, and current access is a read, do nothing
    } else {
      addToHashTableSlowPath(thread, objBaseAddress, fieldOffset, fieldAddr, primaryIndex, key, type, /*siteID,*/ value, hashTableSlot);
    }
  }
  
  @NoInline
  private static void addToHashTableSlowPath(RVMThread thread, Address objBaseAddress, int fieldOffset, Address fieldAddr, 
      int primaryIndex, int key, int type, /*int siteID,*/ Word value, Address hashTableSlot) {
    if (value.EQ(EMPTY)) {
      // The slot is empty, that means there was no previous access, so we can directly add 
      hashTableSlot.store(fieldAddr);
      if (AVD.useLinearArrayOfIndices()) {
        LinearArrayOfIndices.store(thread, primaryIndex);
      }
      if (AVD.logRdWrAccesses()) {
        AVDLogger.logAccessInReadWriteSet(thread, objBaseAddress, fieldOffset, type/*, siteID*/);
      }
    } else { // Location is filled up by some other element, so a collision
      addToHashTableSecondary(thread, objBaseAddress, fieldOffset, fieldAddr, primaryIndex, key, type/*, siteID*/);
    }
  }

  @NoInline
  private static void addToHashTableSecondary(RVMThread thread, Address objBaseAddress, int fieldOffset, Address fieldAddr, 
      int primaryIndex, int key, int type/*, int siteID*/) {
    int count = 0;
    Word value = EMPTY;
    Address slot;
    WordArray hashTableRef = thread.accessInfoHashTable;
    int hashTableSize = hashTableRef.length();
    do {
      primaryIndex = secondaryHashFunction(key, primaryIndex, hashTableSize);
      slot = ObjectReference.fromObject(hashTableRef).toAddress().plus(primaryIndex << LOG_CELL_SIZE_IN_BYTES);
      value = slot.loadWord();
      if (value.xor(fieldAddr.toWord()).isZero()) {
        return;
      } else if (value.EQ(EMPTY)) {
        // The slot is empty, that means there was no previous access, so we can directly add 
        slot.store(fieldAddr); 
        if (AVD.useLinearArrayOfIndices()) {
          LinearArrayOfIndices.store(thread, primaryIndex);
        }
        if (AVD.logRdWrAccesses()) {
          AVDLogger.logAccessInReadWriteSet(thread, objBaseAddress, fieldOffset, type/*, siteID*/);
        }
        return;
      } else {
        count++;
      }
      if (count >= MAX_RETRIES_BEFORE_GROWING) { 
        // The linear array is not supposed to be reallocated, only the contents will possibly change
        hashTableSize = growAndCopyHashSet(hashTableRef, hashTableSize);
        hashTableRef = thread.accessInfoHashTable;
        count = 0;
        addToHashTableFastPath(thread, objBaseAddress, fieldOffset, type/*, siteID*/);
        return;
      }
    } while (true);
  }

  /** {@code length} is expected to be a prime number */
  @Inline
  private static int primaryHashFunc(int key, int length) {
    return key % length;
  }
  
  @Inline
  private static int getKey(Address addr) {
    return addr.toInt() & 0x7fffffff;
  }

  @Inline
  private static int secondaryHashFunction(int key, int primaryIndex, int length) {
    int secondaryIndex = (primaryIndex + h2Offset(key, length)) % length; // To avoid overflow
    return secondaryIndex;
  }
  
  @Inline
  private static int h2Offset(int key, int length) {
    return 1 + (key % (length - 1));
  }

  /** Only called while adding to the hash set.
   * @param oldHashTableRef
   * @param oldHashTableSize No. of elements (actual size is 2*oldSize)
   * @return size of the new hash table
   */
  @NoInline
  private static int growAndCopyHashSet(WordArray oldHashTableRef, int oldHashTableSize) {
    if (VM.VerifyAssertions) { VM._assert(oldHashTableSize == oldHashTableRef.length() >>> LOG_NUM_WORDS_IN_CELL); }
    
    RVMThread currentThread = RVMThread.getCurrentThread();
    int newHashTableSize = (oldHashTableSize << 1) + 1; // Double plus one, should be prime
    WordArray newHashTableRef = createAccessInfoArray(newHashTableSize); 
    currentThread.accessInfoHashTable = newHashTableRef; // Reset the pointer
    
    // Now copy elements after computing new hash codes
    // We can possibly use linear array over here, it should be cheaper especially if there are a number
    // of hash table hits, then iterating the linear array should be much cheaper than iterating the read-write set
    if (AVD.oldGrowAndCopyHashSet()) {
      copyHashTable(currentThread, oldHashTableRef, oldHashTableSize, newHashTableRef, newHashTableSize);
    } else {
      copyHashTableUsingLinearArray(currentThread, oldHashTableRef, oldHashTableSize);
    }
    
    oldHashTableRef = null;
    newHashTableRef = null;
    if (AVD.recordAVDStats()) {
      AVDStats.numHashTableGrown.inc(1L);
    }
    return currentThread.accessInfoHashTable.length();
  }

  private static void copyHashTable(RVMThread thread, WordArray oldHashTableRef, int oldHashTableSize, WordArray newHashTableRef, 
      int newHashTableSize) {
    if (VM.VerifyAssertions) { VM._assert(false); }
  }
  
  // We don't need to allocate and prepare a new linear array. It is true that the hash table slots would change, but 
  // then we can overwrite the new slot information in the old linear array itself
  private static boolean copyHashTableUsingLinearArray(RVMThread thread, WordArray oldHashTableRef, int oldHashTableSize) {
    Address linearSlot = ObjectReference.fromObject(thread.linearArray).toAddress();
    int lengthOfLinearArray = thread.indexOfLinearArray;
    if (VM.VerifyAssertions) { VM._assert(lengthOfLinearArray <= thread.linearArray.length()); }
    
    Address oldHashTableStart = ObjectReference.fromObject(oldHashTableRef).toAddress();
    Address fieldAddr; // This stores the address of a field in the old hash table 
    
    WordArray newHashTableRef = thread.accessInfoHashTable; 
    int newHashTableSize = newHashTableRef.length();
    if (VM.VerifyAssertions) { VM._assert(newHashTableSize == 2*oldHashTableSize + 1); }
    
    for (int i = 0; i < lengthOfLinearArray; i++) {
      int index = linearSlot.loadInt();
      if (VM.VerifyAssertions) { VM._assert(index >= 0 && index < oldHashTableRef.length()); }
      fieldAddr = oldHashTableStart.plus(index << LOG_CELL_SIZE_IN_BYTES).loadAddress();
      if (VM.VerifyAssertions) { VM._assert(fieldAddr.NE(Address.zero())); }
      addToNewHashTableFastPath(thread, fieldAddr, linearSlot, newHashTableRef, newHashTableSize);

      // We are currently not allowing growing of hash table
//      if (!flag) { // More than 7 secondary collisions, hash table needs to grow and copy should be restarted
//        newLinearArray = null;
//        return flag;
//      } else {
//        oldLinearSlot = oldLinearSlot.plus(BYTES_IN_WORD);
//        newLinearSlot = newLinearSlot.plus(BYTES_IN_WORD);
//      }
      
      linearSlot = linearSlot.plus(BYTES_IN_WORD);
    }
    // The following assertion is not correct since we do not zero out the contents of the linear array. Hence, it is 
    // possible that the linear array contains data from earlier boundary points.
    //if (VM.VerifyAssertions) { VM._assert(linearSlot.loadWord().isZero()); }
    
    return true;
  }

  private static boolean addToNewHashTableFastPath(RVMThread thread, Address fieldAddr, Address linearSlot, WordArray newHashTableRef, 
      int newHashTableSize) {
    int key = getKey(fieldAddr);
    int primaryIndex = primaryHashFunc(key, newHashTableSize);
    Address hashTableSlot = ObjectReference.fromObject(newHashTableRef).toAddress().plus(primaryIndex << LOG_CELL_SIZE_IN_BYTES);
    Word temp = hashTableSlot.loadWord();
    if (temp.isZero()) { // Slot in new hash table is empty
      hashTableSlot.store(fieldAddr);
      if (VM.VerifyAssertions) { VM._assert(linearSlot.loadInt() >= 0); }
      linearSlot.store(primaryIndex); // There is no relation between the stored index value and primaryIndex
      return true;
    } else { // Collision
      return addToNewHashTableSecondary(thread, newHashTableRef, newHashTableSize, fieldAddr, linearSlot, key, primaryIndex);
    }
  }

  private static boolean addToNewHashTableSecondary(RVMThread thread, WordArray newHashTableRef, int newHashTableSize, 
              Address fieldAddr, Address linearSlot, int key, int primaryIndex) {
    Word temp;
    Address slot;
    do {
      primaryIndex = secondaryHashFunction(key, primaryIndex, newHashTableSize);
      slot = ObjectReference.fromObject(newHashTableRef).toAddress().plus(primaryIndex << LOG_CELL_SIZE_IN_BYTES);
      temp = slot.loadWord();
      if (temp.isZero()) {
        slot.store(fieldAddr);
        if (VM.VerifyAssertions) { VM._assert(linearSlot.loadInt() >= 0 && linearSlot.loadInt() < newHashTableRef.length()); }
        linearSlot.store(primaryIndex);
        return true;
      } 
    } while (true);
  }

  /**
   * @param newSize number of logical elements
   */
  @UninterruptibleNoWarn
  private static WordArray createAccessInfoArray(int newSize) {
    MemoryManager.startAllocatingInUninterruptibleCode();
    WordArray newBuffer = WordArray.create(newSize << LOG_NUM_WORDS_IN_CELL); // 4 bytes/1 word cell size
    MemoryManager.stopAllocatingInUninterruptibleCode();
    return newBuffer;
  }

  /**
   * @param thread Any thread whose access info hash table should be cleared
   */
  public static void clear(RVMThread thread) {
//    if (AVD.recordAVDStats()) {
//      AVDStats.numHashTableClears.inc(1L);
//    }
    
    // We reset the hash table reference to null during thread termination, this assertion is to 
    // check that clear() is not invoked after that for a terminated thread as part of communication
    if (VM.VerifyAssertions) { VM._assert(thread.accessInfoHashTable != null); }

    if (AVD.useLinearArrayOfIndices()) {
      int linearArrayLength = thread.indexOfLinearArray;

      if (linearArrayLength > 0) { // Something was added to the hash table in between
        
        // AVD: LATER: How can we redo this assert?
//        if (VM.VerifyAssertions) { VM._assert(last.currentLogIndex - thread.indexDuringLastClear >= thread.indexOfLinearArray); }
        
        WordArray hashTableRef = thread.accessInfoHashTable;
        int hashTableSize = hashTableRef.length(); // Actual size
        clearUsingLinearArray(thread, hashTableRef, hashTableSize >>> LOG_NUM_WORDS_IN_CELL, linearArrayLength);
        if (VM.VerifyAssertions) { // Expensive test
          if (Octet.getConfig().testHashTableClear()) {
            testHashTableClear(thread);
          }
        }
      }
    } else {
      WordArray hashTableRef = thread.accessInfoHashTable;
      int hashTableSize = hashTableRef.length(); // Actual size
      // AVD: LATER: Support for non-temporal instructions is probably poor
      Memory.zero(false, ObjectReference.fromObject(hashTableRef).toAddress(), 
          Extent.fromIntZeroExtend(hashTableSize << LOG_BYTES_IN_WORD));
    }
  }
  
  /** Zero out the slots in the hash table based on the linear array entries. */
  private static void clearUsingLinearArray(RVMThread thread, WordArray hashTableRef, int logicalSlotsInHashTable, int linearArrayLength) {
    Address slot = ObjectReference.fromObject(thread.linearArray).toAddress();
    Address hashTableSlot;
    Address hashTableStart = ObjectReference.fromObject(hashTableRef).toAddress();
    int index;
    for (int i = 0 ; i < linearArrayLength; i++) {
      slot = slot.plus(BYTES_IN_ADDRESS);
      index = slot.loadInt();
      if (VM.VerifyAssertions) { VM._assert(index >= 0 && index < logicalSlotsInHashTable); }
      hashTableSlot = hashTableStart.plus(index << LOG_CELL_SIZE_IN_BYTES);
      hashTableSlot.store(Word.zero());
    }    
    thread.indexOfLinearArray = 0; // Reset the index, instead of zeroing the linear array
  }

  /** This method is expected to be expensive.
   * @param thread The thread whose hash table is to be tested
   */
  private static void testHashTableClear(RVMThread thread) {
    WordArray hashTableRef = thread.accessInfoHashTable;
    int hashTableSize = hashTableRef.length(); // Actual size
    Address start = ObjectReference.fromObject(hashTableRef).toAddress();
    Address end = start.plus(hashTableSize << LOG_BYTES_IN_WORD); 
    for (Address addr = start; addr.LT(end); addr = addr.plus(BYTES_IN_ADDRESS)) {
      Word value = addr.loadWord();
      if (VM.VerifyAssertions) { VM._assert(value.isZero()); }
    }
  }
   
}
