package org.jikesrvm.atomicity;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.mm.mminterface.Barriers;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/** One source of confusion is between the actual size and the logical size, since each cell is of two words. */
@Uninterruptible
public final class ReadWriteLog implements Constants {
  
  // Each cell is either 16 bytes/4 words (2^4) or 8 bytes/2 words (2^3)
  public static final int LOG_CELL_SIZE_IN_BYTES = (AVD.includeSiteIDinReadWriteSet()) ? 4 : 3; 
  public static final int CELL_SIZE_IN_BYTES = 1 << LOG_CELL_SIZE_IN_BYTES;
  // Each cell is either 16 bytes/4 words (2^4) or 8 bytes/2 words (2^3)
  public static final int LOG_NUM_WORDS_IN_CELL = (AVD.includeSiteIDinReadWriteSet()) ? 2 : 1;
  public static final int NUM_WORDS_IN_CELL = 1 << LOG_NUM_WORDS_IN_CELL; 
  
  /** Initial size of the read write set, the buffer can grow based on the needs */
  // Number of logical cells. Each cell in the read/write set is of two words
  // Each log buffer slot is composed of two words 
  public static final int LOG_BUFFER_SLOTS = 256 * 1024; // 256K logical slots
  public static final int INITIAL_LOG_BUFFER_REFERENCE_SIZE = 1024;
  
  // The first two words in each cell store the following: the first one stores the object reference,  
  // and the second one stores the following information: Field offset, GC Cycle Num, READ/WRITE from MSB to LSB
  // The following layout is with the assumption that a word is 32 bits
  // The low two bits are used to store read/write access type 
  // Next high 12 bits store the GC number, while the top 18 store the field offset. 
  // This is done this way since the offset can be negative
  
  // If each cell is of 4 words, then the 3rd word stores the site id of the accessing instruction 
  
  private static final int NUM_BITS_TO_STORE_ACCESS_TYPE = 2;
  private static final int NUM_BITS_TO_STORE_GC_CYCLE = 12; // MAX_PHASES in MMTk Stats
  private static final int NUM_BITS_TO_STORE_FIELD_OFFSET = BITS_IN_WORD - NUM_BITS_TO_STORE_ACCESS_TYPE - NUM_BITS_TO_STORE_GC_CYCLE;
  // Two bits from LSB side, mask is 000...011
  private static final Word ACCESS_TYPE_BIT_MASK = Word.one().lsh(NUM_BITS_TO_STORE_ACCESS_TYPE).minus(Word.one()); 
  // High "NUM_BITS_TO_STORE_FIELD_OFFSET" bits should be one
  private static final Word FIELD_OFFSET_MASK = Word.max().xor(Word.one().lsh(NUM_BITS_TO_STORE_GC_CYCLE + NUM_BITS_TO_STORE_ACCESS_TYPE).minus(Word.one()));
  // 11 bits after NUM_BITS_TO_STORE_ACCESS_TYPE should be one
  private static final Word GC_CYCLE_NUMBER_MASK = Word.one().lsh(BITS_IN_WORD - NUM_BITS_TO_STORE_FIELD_OFFSET).minus(Word.one()).and(ACCESS_TYPE_BIT_MASK.not());
  
  static final int LOG_GROWTH_FACTOR = 1;
  public static final int GROWTH_FACTOR = 1 << LOG_GROWTH_FACTOR; // 2X
  
  @Inline
  static void logAccess(AVDNode node, Address objAddr, int fieldOffset, int type/*, int siteID*/) {
    RVMThread thread = RVMThread.getCurrentThread();
    ObjectReference objRef = thread.currentWordArrayStart;
    if (VM.VerifyAssertions) {
      VM._assert(!objRef.isNull());
      VM._assert(MemoryManager.validRef(objRef));
    }
    int index = thread.wordArrayBufferIndex;
    if (VM.VerifyAssertions) { VM._assert(index >= 0 && index < LOG_BUFFER_SLOTS); }
    
    Address slot = thread.currentWordArraySlot;
    if (VM.VerifyAssertions) { 
      VM._assert(slot.loadObjectReference().isNull()); // First word should be empty
      VM._assert(slot.loadWord(Offset.fromIntZeroExtend(BYTES_IN_WORD)).isZero()); // Second word should be empty
      Address tmp = objRef.toAddress().plus(index << LOG_CELL_SIZE_IN_BYTES);
      VM._assert(slot.EQ(tmp));
    }
    
    if (AVD.useGenerationalBarriers()) { // Need a generational write barrier over here
      Barriers.objectFieldWrite(objRef.toObject(), objAddr.toObjectReference().toObject(), 
          Offset.fromIntZeroExtend(index << LOG_CELL_SIZE_IN_BYTES), 0);
    } else {
      slot.store(objAddr.toObjectReference());
    }
    
    Word second = Word.fromIntSignExtend(fieldOffset).lsh(BITS_IN_WORD - NUM_BITS_TO_STORE_FIELD_OFFSET);    
    second = second.or(Word.fromIntZeroExtend(type));
    // Nothing to do for GC cycle number over here
    if (VM.VerifyAssertions) { VM._assert(getGCCycleNumber(second) == 0); }
    slot.store(second, Offset.fromIntZeroExtend(BYTES_IN_WORD));
    
    // Store the site id information in the third and fourth words 
    if (AVD.includeSiteIDinReadWriteSet()) {
      VM.sysFail("Site id is not being passed");
    }
  }
  
  @Inline
  static ObjectReference getObjectReference(AVDNode node, int index) {
    if (VM.VerifyAssertions) { VM._assert(index >= 0 && index < node.currentReadWriteLogIndex); }
    Word value = getFirstWord(node, index);
    ObjectReference objRef = value.toAddress().toObjectReference();
    if (VM.VerifyAssertions) { VM._assert(!objRef.isNull()); }
    return objRef;
  }
  
  @Inline
  static Word getFirstWord(AVDNode node, int index) {
    if (VM.VerifyAssertions) { VM._assert(index >= 0 && index < node.currentReadWriteLogIndex); }
    Address addr = node.getLogBufferSlotAddress(index);
    if (VM.VerifyAssertions) { VM._assert(!addr.isZero()); }
    return addr.loadWord();
  }
  
  @Inline
  static Word getSecondWord(AVDNode node, int index) {
    if (VM.VerifyAssertions) { VM._assert(index >= 0 && index < node.currentReadWriteLogIndex); }
    Word value = node.getLogBufferSlotAddress(index).loadWord(Offset.fromIntZeroExtend(BYTES_IN_WORD));
    // Second word can actually be zero
    return value;
  }
  
  /** See AccessType objects in class AVD for types */  
  @Inline
  static int getAccessType(AVDNode node, int index) {
    Word value = getSecondWord(node, index);
    return getAccessType(value);
  }
  
  @Inline
  static int getAccessType(Word value) {
    return value.and(ACCESS_TYPE_BIT_MASK).toInt();
  }
  
  @Inline
  static int getFieldOffset(AVDNode node, int index) {
    Word value = getSecondWord(node, index);
    return getFieldOffset(value);
  }
  
  @Inline 
  static int getFieldOffset(Word value) {
    return value.and(FIELD_OFFSET_MASK).rsha(BITS_IN_WORD - NUM_BITS_TO_STORE_FIELD_OFFSET).toInt();
  }
  
  @Inline
  static int getGCCycleNumber(AVDNode node, int index) {
    Word value = getSecondWord(node, index);
    return getGCCycleNumber(value);
  }   

  @Inline
  public static int getGCCycleNumber(Word value) {
    return value.and(GC_CYCLE_NUMBER_MASK).rsha(NUM_BITS_TO_STORE_ACCESS_TYPE).toInt();
  }
  
  @Inline
  static int getSiteID(AVDNode node, int index) {
    if (VM.VerifyAssertions) { VM._assert(index >= 0 && index < node.currentReadWriteLogIndex); }
    Address slot = node.getLogBufferSlotAddress(index); 
    if (VM.VerifyAssertions) { VM._assert(!slot.loadObjectReference().isNull()); }
    int value = slot.loadInt(Offset.fromIntZeroExtend(2 * BYTES_IN_WORD));
    return value;
  }
  
  @Inline
  public static Word setGCCycleNumber(Word value, int number) {
    if (VM.VerifyAssertions) { VM._assert(number > 0); } // Collection count starts from one
    if (VM.VerifyAssertions) { VM._assert(getGCCycleNumber(value) == 0); }
    Word temp = Word.fromIntZeroExtend(number);
    // Assert number <= 2^12
    if (VM.VerifyAssertions) { VM._assert(number <= Word.one().lsh(NUM_BITS_TO_STORE_GC_CYCLE).minus(Word.one()).toInt()); }
    return value.xor(temp.lsh(NUM_BITS_TO_STORE_ACCESS_TYPE));
  }
  
	// newSize is number of logical slots (each slot is two words)
  @UninterruptibleNoWarn
  static WordArray createNewReadWriteLog(int newSize) {
    MemoryManager.startAllocatingInUninterruptibleCode();
    //WordArray newArray = WordArray.create(newSize * NUM_WORDS_IN_CELL); // 8 byte cell size
    WordArray newArray = (WordArray)MemoryManager.createAVDSpace(NUM_WORDS_IN_CELL * newSize); // 8 byte cell size
    MemoryManager.stopAllocatingInUninterruptibleCode();
    return newArray;
  }

}