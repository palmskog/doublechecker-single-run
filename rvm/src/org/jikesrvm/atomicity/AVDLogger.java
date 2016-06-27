package org.jikesrvm.atomicity;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.octet.OctetState;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoCheckStore;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.AddressArray;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

@Uninterruptible
public final class AVDLogger implements Constants {
  
  @Inline 
  public static void avdReadMetadataFastPath(RVMThread currentThread, Address objBaseAddr, int fieldOffset, 
      int avdOffset, /*int siteID,*/ int type) {
    if (VM.VerifyAssertions) { VM._assert(AVD.addPerFieldMetadata() && type == AVD.REGULAR_READ); }
    Word avdMetadata = objBaseAddr.loadWord(Offset.fromIntSignExtend(avdOffset));
    if (avdMetadata.and(AVDMetadataHelper.THREADID_BPCOUNT_MASK).toInt() == currentThread.bpCount) {
      // Fast path, match found and current access is a read
      // Debugging assertion, the fast path check should never succeed if an Octet slow path has been taken
      //if (VM.VerifyAssertions) { VM._assert(!currentThread.slowPathTaken); }
    } else { // Slow path
      avdMetadataSlowPath(currentThread, objBaseAddr, fieldOffset, avdOffset, /*avdMetadata, siteID,*/ type);
    }
  }
  
  // Called only from resolved barriers for read-shared objects. We avoid storing read accesses
  // if the per-field AVD offset is not initialized
  @NoInline
  public static void avdReadSlowPathHelper(RVMThread currentThread, Address objBaseAddr, int fieldOffset, 
      int avdOffset, Word octetMetadata, /*int siteID,*/ int type) {
    if (VM.VerifyAssertions) { VM._assert(AVD.isRegularRead(type)); }
    if (VM.VerifyAssertions) { VM._assert(avdOffset != AVD.UNINITIALIZED_OFFSET); }
    if (OctetState.isReadExcl(octetMetadata) || OctetState.isReadSharedPossiblyUnfenced(octetMetadata)) {
      // RdEx_T1 --> Rd_T or RdSh --> Rd_T
      AVDHashTable.addToHashTableFastPath(currentThread, objBaseAddr, fieldOffset, type/*, siteID*/);
    } else { // WrEx_T1 --> Rd_T, the boundary point fast path is bound to fail
      AVDLogger.avdMetadataSlowPath(currentThread, objBaseAddr, fieldOffset, avdOffset, /*siteID,*/ type);
    }
  }
  
  @NoInline
  public static void avdMetadataSlowPath(RVMThread currentThread, Address objBaseAddr,
      int fieldOffset, int avdOffset, /*Word avdMetadata, int siteID,*/ int type) {    
    if (VM.VerifyAssertions) { VM._assert(AVD.isRegularRead(type) || AVD.isRegularWrite(type)); }
    Word avdMetadata = objBaseAddr.loadWord(Offset.fromIntSignExtend(avdOffset));
    if (avdMetadata.and(AVDMetadataHelper.THREADID_BPCOUNT_MASK).toInt() == currentThread.bpCount) { // Match found
      if (VM.VerifyAssertions) { VM._assert(AVD.isRegularWrite(type)); } // Current access should be a write
      if (!AVDMetadataHelper.getAccessType(avdMetadata)) {
        // Current access is a write, previous was read        
        Word newMetadata = avdMetadata.or(AVDMetadataHelper.ACCESS_TYPE_BIT_MASK); // Update AVD metadata
        objBaseAddr.store(newMetadata, Offset.fromIntSignExtend(avdOffset));
      }
    } else {
      Word newMetadata = AVDMetadataHelper.updateMetadata(currentThread, currentThread.bpCount, AVD.isRegularRead(type));
      objBaseAddr.store(newMetadata, Offset.fromIntSignExtend(avdOffset));      
    }
    // This is not a reference store
    if (AVD.logRdWrAccesses()) {
      logAccessInReadWriteSet(currentThread, objBaseAddr, fieldOffset, type/*, siteID*/);
    }
  }

  @NoInline // This is not part of the fast path
  public static void logAccessInReadWriteSet(RVMThread currentThread, Address objAddress, int fieldSlotOffset, 
      int type/*, int siteID*/) {
    if (VM.VerifyAssertions) { // Not an important assertion
      if (objAddress.NE(AVD.JTOC_ADDRESS)) { VM._assert(MemoryManager.validRef(objAddress.toObjectReference())); }
    }
    
    if (currentThread.wordArrayBufferIndex == ReadWriteLog.LOG_BUFFER_SLOTS) { // Current word array is full
      AddressArray currentArray = currentThread.logBufferReferenceArray;
      int currentSize = currentArray.length();
      int currentLogBufferIndex = currentThread.logBufferReferenceArrayIndex;
      
      // Check whether the logBufferReferenceArray itself needs to be grown
      if (currentLogBufferIndex == currentSize - 1) {
        // Check whether the log buffer reference array gets full for any benchmark
        if (VM.VerifyAssertions) { VM._assert(VM.NOT_REACHED); }
        
        // This indicates that the last reference array slot is being used, so we need to expand the reference array
        // before we can allocate another word array. 
        // newRefArray could be allocated in the nursery
        AddressArray newRefArray = createAddressArray(currentSize << ReadWriteLog.LOG_GROWTH_FACTOR); // Double
        
        // Now copy the references to the individual word arrays to the new address array
        int j = 0;
        for (int i = 0; i < currentSize; i++) {
          Address addr = currentArray.get(i);
          if (addr.isZero()) { // Leave out word arrays that are nulled out
            continue;
          }
          if (VM.VerifyAssertions) { 
            ObjectReference objRef = addr.loadObjectReference();
            VM._assert(MemoryManager.validRef(objRef));
            WordArray w = (WordArray) objRef.toObject(); // For type checking
          }
          // We are not creating old-to-nursery references, hence we shouldn't need generational barriers
          newRefArray.set(j, addr); // Store in new address array
          j++;
        }
        
        currentThread.logBufferReferenceArray = newRefArray;
        currentThread.logBufferReferenceArrayIndex = j;
        
        // Update the local variables since they will have changed
        currentArray = currentThread.logBufferReferenceArray;
        currentSize = currentArray.length();
        currentLogBufferIndex = j;
      }
      
      // Allocate new word array, two words per cell
      WordArray newWordArray = (WordArray) MemoryManager.createAVDSpace(ReadWriteLog.LOG_BUFFER_SLOTS << ReadWriteLog.LOG_NUM_WORDS_IN_CELL); 
      if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(ObjectReference.fromObject(newWordArray))); }
      currentLogBufferIndex = ++currentThread.logBufferReferenceArrayIndex;
      if (VM.VerifyAssertions) { VM._assert(currentArray.get(currentLogBufferIndex).toObjectReference().isNull()); }
      currentArray.set(currentLogBufferIndex, ObjectReference.fromObject(newWordArray).toAddress());
      
      currentThread.wordArrayBufferIndex = 0;
      currentThread.currentWordArraySlot = currentArray.get(currentLogBufferIndex);
      currentThread.currentWordArrayStart = currentThread.currentWordArraySlot.toObjectReference();
    }
    
    // Record access information
    AVDNode node = currentThread.currentTransaction; // node points to the current transaction 
    ReadWriteLog.logAccess(node, objAddress, fieldSlotOffset, type/*, siteID*/);
    currentThread.wordArrayBufferIndex++;
    if (VM.VerifyAssertions) { VM._assert(currentThread.wordArrayBufferIndex <= ReadWriteLog.LOG_BUFFER_SLOTS); }
    // AVD: LATER: We can possibly do away with this in the fast path
    node.currentReadWriteLogIndex++;
    currentThread.currentWordArraySlot = currentThread.currentWordArraySlot.plus(ReadWriteLog.CELL_SIZE_IN_BYTES);
    
    // Test for correct manipulation of word addresses
    if (VM.VerifyAssertions) {
      Address wordArrayStart = ObjectReference.fromObject(currentThread.logBufferReferenceArray).toAddress().
          plus(Offset.fromIntSignExtend(currentThread.logBufferReferenceArrayIndex << LOG_BYTES_IN_ADDRESS)).loadAddress();
      Address slot = wordArrayStart.plus(currentThread.wordArrayBufferIndex << ReadWriteLog.LOG_CELL_SIZE_IN_BYTES); 
      VM._assert(slot.EQ(currentThread.currentWordArraySlot));
    }
    
    if (AVD.recordAVDStats()) {
      AVDStats.numPhase1InstructionsLogged.inc(1L);
    }
  }

  /**
   * @param remoteThread Conflicting remote thread
   * @param remoteTransaction
   * @param remoteIndex
   * @param loggingThread thread which will actually wants to log the conflict information
   */
  @NoCheckStore
  public static void recordConflictAccess(RVMThread remoteThread, AVDNode remoteTransaction, 
                          int remoteIndex, RVMThread loggingThread) {
    AVDNode loggingNode = loggingThread.currentTransaction;
    if (VM.VerifyAssertions) { VM._assert(!loggingNode.isUnaryTrans); } // Unary transactions are not expected to have conflict info
    if (loggingNode.crossThreadAccessLogIndex == loggingNode.currentCrossThreadAccessLogSize) {
      growConflictAccessBuffer(loggingNode);
    }
    loggingNode.crossThreadAccessLog[loggingNode.crossThreadAccessLogIndex++] 
                = CrossThreadAccess.createAccessIdentifier(remoteThread, remoteTransaction, remoteIndex, 
                    loggingNode.currentReadWriteLogIndex);
    if (VM.VerifyAssertions) { VM._assert(remoteIndex <= remoteTransaction.currentReadWriteLogIndex); }
  }

  private static void growConflictAccessBuffer(AVDNode node) {
    int newSize = node.currentCrossThreadAccessLogSize << 1; // 2x
    CrossThreadAccess[] newArray 
            = CrossThreadAccess.growCrossThreadArray(node.crossThreadAccessLog, node.currentCrossThreadAccessLogSize, newSize);
    node.crossThreadAccessLog = newArray;
    node.currentCrossThreadAccessLogSize = newSize;
    if (VM.VerifyAssertions) { VM._assert(node.currentCrossThreadAccessLogSize == 2 * node.crossThreadAccessLogIndex); }
  }
  
  /** Allocate an AddressArray of {@code size} */
  private static AddressArray createAddressArray(int size) {
    MemoryManager.startAllocatingInUninterruptibleCode();
    AddressArray temp = AddressArray.create(size);
    MemoryManager.stopAllocatingInUninterruptibleCode();
    return temp;
  }
  
}
