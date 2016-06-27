package org.jikesrvm.octet;

import org.jikesrvm.VM;
import org.jikesrvm.atomicity.AVD;
import org.jikesrvm.atomicity.AVDHashTable;
import org.jikesrvm.atomicity.AVDLogger;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.Time;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/** AVD: This class is duplicated from octet.StateTransfers. This class manages state changes for regular program accesses 
 *  (read/writes), not for synchronization operations, from Octet threads should. The StateTransfers class manages 
 *  read/write of synchronization operations.
 *  
 *  Debugging/statistics code has been commented out in different places to help the opt compiler do less work */
@Uninterruptible
public class AVDStateTransfers extends OctetState {

  @Inline
  public static void readResolved(Address objBaseAddr, Offset octetOffset, int fieldOffset, int avdOffset, int siteID) {
    //Stats.fastPathsEntered.inc(siteID);
    RVMThread currentThread = RVMThread.getCurrentThread();
    //currentThread.slowPathTaken = false;
    
    // A nice guarantee.
    if (VM.VerifyAssertions) { VM._assert(currentThread.isOctetThread()); }
    Word octetMetadata = objBaseAddr.prepareWord(octetOffset);
    
    // Octet: LATER: metadata check can slow down the opt compiler
    //check(octetMetadata);

    // Fast path: state must be WrEx or RdEx for current thread, or RdSh
    // First check to see if the state supports this thread's read
    // Octet: LATER: It might not be worth it to do this slightly more complex check for "read or write excl" rather than just checking for "write excl"
    // (since the latter shouldn't require allocating a register).
    if (!isExclForCurrentThread(octetMetadata)) {
      // commenting out stats because even though they're dead code with assertions turned off, they'll bloat the IR when they get inlined
      // now check for RdSh
      if (!isReadSharedAndFenced(octetMetadata)) {
        // neither quick test succeeded
        // AVD: RdEx_T1 --> Read_T, or, WrEx_T1 --> Read_T, or, RdSh --> Unfenced read by T
        readSlowPathAVDHelper(objBaseAddr, octetOffset, octetMetadata, fieldOffset, avdOffset, siteID);
        //currentThread.slowPathTaken = true;
      } else { // RdSh --> Fenced read by T
        //Stats.RdSh_RdSh_NoFence.inc();
        // AVD: Use hash table for logging
        if (AVD.logRdWrAccesses()) {
          AVDHashTable.addToHashTableFastPath(currentThread, objBaseAddr, fieldOffset, AVD.REGULAR_READ/*, siteID*/);
        }
      }
    } else { // Object is already in the exclusive state
      if (AVD.logRdWrAccesses()) { // Record read information in the read/write log
        AVDLogger.avdReadMetadataFastPath(currentThread, objBaseAddr, fieldOffset, avdOffset, /*siteID,*/ AVD.REGULAR_READ);
      }
    }
    
//    if (Octet.getConfig().stats()) {
//      if (isWriteExclForCurrentThread(octetMetadata)) {
//        Stats.WrEx_WrEx_Same.inc();
//        // Already in WrEx state
//      } else {
//        Stats.RdEx_RdEx.inc();
//        // RdEx state
//      }
//    }
    
//    if (AVD.recordAVDStats()) {
//      AVDStats.numTotalPhase1Instructions.inc(1L);
//      if (RVMThread.getCurrentThread().currentTransaction.isUnaryTrans) {
//        if (VM.VerifyAssertions) { VM._assert(!RVMThread.getCurrentThread().inTransaction()); }
//        AVDStats.numBarrierCallsFromUnaryTx.inc(1L);
//      } else {
//        if (VM.VerifyAssertions) { VM._assert(RVMThread.getCurrentThread().inTransaction()); }
//        AVDStats.numBarrierCallsFromRegularTx.inc(1L);
//      }
//    }
    
  }

  // AVD: TODO: Decide whether to inline. It doesn't seem to affect the DaCapo benchmarks much.
  @NoInline
  public static void readUnresolved(Address objBaseAddr, Offset octetOffset, int fieldOffset, int avdOffset, int siteID,
      boolean hasAVDMetadata) {
    //Stats.fastPathsEntered.inc(siteID);
    RVMThread currentThread = RVMThread.getCurrentThread();
    //currentThread.slowPathTaken = false;
    
    // A nice guarantee.
    if (VM.VerifyAssertions) { VM._assert(currentThread.isOctetThread()); }
    Word octetMetadata = objBaseAddr.prepareWord(octetOffset);
    
    // AVD: Commented out metadata check
    // Octet: LATER: metadata check can slow down the opt compiler
    //check(octetMetadata);

    // Fast path: state must be WrEx or RdEx for current thread, or RdSh
    // First check to see if the state supports this thread's read
    // Octet: LATER: It might not be worth it to do this slightly more complex check for "read or write excl" rather than just checking for "write excl"
    // (since the latter shouldn't require allocating a register).
    if (!isExclForCurrentThread(octetMetadata)) {
      // commenting out stats because even though they're dead code with assertions turned off, they'll bloat the IR when they get inlined
      // now check for RdSh
      if (!isReadSharedAndFenced(octetMetadata)) {
        // neither quick test succeeded
        // AVD: RdEx_T1 --> Read_T, or, WrEx_T1 --> Read_T, or, RdSh --> Unfenced read by T
        readSlowPathAVDHelper(objBaseAddr, octetOffset, octetMetadata, fieldOffset, avdOffset, siteID);
        //currentThread.slowPathTaken = true;
      } else { // RdSh --> Fenced read by T
        //Stats.RdSh_RdSh_NoFence.inc();
        // AVD: Use hash table for logging
        if (AVD.logRdWrAccesses()) {
          AVDHashTable.addToHashTableFastPath(currentThread, objBaseAddr, fieldOffset, AVD.REGULAR_READ/*, siteID*/);
        }
      }
    } else { // Object is already in the exclusive state
      if (AVD.logRdWrAccesses() && hasAVDMetadata) { // Record read information in the read/write log
        AVDLogger.avdReadMetadataFastPath(currentThread, objBaseAddr, fieldOffset, avdOffset, /*siteID,*/ AVD.REGULAR_READ);
      }
    }
    
//    if (Octet.getConfig().stats()) {
//      if (isWriteExclForCurrentThread(octetMetadata)) {
//        Stats.WrEx_WrEx_Same.inc();
//        // Already in WrEx state
//      } else {
//        Stats.RdEx_RdEx.inc();
//        // RdEx state
//      }
//    }
    
//    if (AVD.recordAVDStats()) {
//      AVDStats.numTotalPhase1Instructions.inc(1L);
//      if (RVMThread.getCurrentThread().currentTransaction.isUnaryTrans) {
//        if (VM.VerifyAssertions) { VM._assert(!RVMThread.getCurrentThread().inTransaction()); }
//        AVDStats.numBarrierCallsFromUnaryTx.inc(1L);
//      } else {
//        if (VM.VerifyAssertions) { VM._assert(RVMThread.getCurrentThread().inTransaction()); }
//        AVDStats.numBarrierCallsFromRegularTx.inc(1L);
//      }
//    }
    
  }

  // AVD: TODO: Possibly merge the resolved and unresolved methods, and leave out unwanted parameters
  
  @Inline
  public static void writeResolved(Address objBaseAddr, Offset octetOffset, int fieldOffset, int avdOffset, int siteID) {
    //Stats.fastPathsEntered.inc(siteID);
    RVMThread currentThread = RVMThread.getCurrentThread();
    //currentThread.slowPathTaken = false;
    
    // A nice guarantee.
    if (VM.VerifyAssertions) { VM._assert(currentThread.isOctetThread()); }
    Word octetMetadata = objBaseAddr.prepareWord(octetOffset);
    
    // Octet: LATER: metadata check can slow down the opt compiler
    //check(octetMetadata);
    
    // fast path: state must be WrEx with current thread
    if (!isWriteExclForCurrentThread(octetMetadata)) {
      // commenting out stats because even though they're dead code with assertions turned off, they'll bloat the IR when they get inlined
      writeSlowPath(objBaseAddr, octetOffset, octetMetadata, fieldOffset, avdOffset, siteID);
      //currentThread.slowPathTaken = true;
      currentThread.bpCount++;
    } else {
      //Stats.WrEx_WrEx_Same.inc();
    }
    
    // AVD: Record write information in the read/write set
    if (AVD.logRdWrAccesses()) {
      AVDLogger.avdMetadataSlowPath(currentThread, objBaseAddr, fieldOffset, avdOffset, /*siteID,*/ AVD.REGULAR_WRITE);
    }
    
//    if (Octet.getConfig().stats()) {
//      if (isWriteExclForCurrentThread(octetMetadata)) {
//        Stats.WrEx_WrEx_Same.inc();
//        // Already in WrEx state
//      } else {
//        Stats.RdEx_RdEx.inc();
//        // RdEx state
//      }
//    }
    
//    if (AVD.recordAVDStats()) {
//      AVDStats.numTotalPhase1Instructions.inc(1L);
//      if (RVMThread.getCurrentThread().currentTransaction.isUnaryTrans) {
//        if (VM.VerifyAssertions) { VM._assert(!RVMThread.getCurrentThread().inTransaction()); }
//        AVDStats.numBarrierCallsFromUnaryTx.inc(1L);
//      } else {
//        if (VM.VerifyAssertions) { VM._assert(RVMThread.getCurrentThread().inTransaction()); }
//        AVDStats.numBarrierCallsFromRegularTx.inc(1L);
//      }
//    }
    
  }
  
  // AVD: TODO: Decide whether to inline. It doesn't seem to affect the DaCapo benchmarks much.
  @NoInline
  public static void writeUnresolved(Address objBaseAddr, Offset octetOffset, int fieldOffset, int avdOffset, int siteID,
      boolean hasAVDMetadata) {
    //Stats.fastPathsEntered.inc(siteID);
    RVMThread currentThread = RVMThread.getCurrentThread();
    //currentThread.slowPathTaken = false;
    
    // A nice guarantee.
    if (VM.VerifyAssertions) { VM._assert(currentThread.isOctetThread()); }
    Word octetMetadata = objBaseAddr.prepareWord(octetOffset);
    
    // Octet: LATER: metadata check can slow down the opt compiler
    //check(octetMetadata);
    
    // fast path: state must be WrEx with current thread
    if (!isWriteExclForCurrentThread(octetMetadata)) {
      // commenting out stats because even though they're dead code with assertions turned off, they'll bloat the IR when they get inlined
      writeSlowPath(objBaseAddr, octetOffset, octetMetadata, fieldOffset, avdOffset, siteID);
      //currentThread.slowPathTaken = true;
      currentThread.bpCount++;
    } else {
      //Stats.WrEx_WrEx_Same.inc();
    }
    
    // AVD: Record write information in the read/write set
    if (AVD.logRdWrAccesses() && hasAVDMetadata) {
      if (VM.VerifyAssertions) { VM._assert(avdOffset != AVD.UNINITIALIZED_OFFSET); }
        AVDLogger.avdMetadataSlowPath(currentThread, objBaseAddr, fieldOffset, avdOffset, /*siteID,*/ AVD.REGULAR_WRITE);
    }
    
//    if (Octet.getConfig().stats()) {
//      if (isWriteExclForCurrentThread(octetMetadata)) {
//        Stats.WrEx_WrEx_Same.inc();
//        // Already in WrEx state
//      } else {
//        Stats.RdEx_RdEx.inc();
//        // RdEx state
//      }
//    }
    
//    if (AVD.recordAVDStats()) {
//      AVDStats.numTotalPhase1Instructions.inc(1L);
//      if (RVMThread.getCurrentThread().currentTransaction.isUnaryTrans) {
//        if (VM.VerifyAssertions) { VM._assert(!RVMThread.getCurrentThread().inTransaction()); }
//        AVDStats.numBarrierCallsFromUnaryTx.inc(1L);
//      } else {
//        if (VM.VerifyAssertions) { VM._assert(RVMThread.getCurrentThread().inTransaction()); }
//        AVDStats.numBarrierCallsFromRegularTx.inc(1L);
//      }
//    }
    
  }

  // This is a helper method to help with inlining. The initial contents of the method should be in sync with readSlowPath()
  @NoInline
  static final void readSlowPathAVDHelper(Address baseAddr, Offset octetOffset, Word oldMetadata, int fieldOffset, 
      int avdOffset, int siteID) {
    boolean switchSlowPath = Octet.getClientAnalysis().alternateReadSlowPath(baseAddr, octetOffset, oldMetadata, fieldOffset, siteID);
    if (!switchSlowPath) {
      slowPath(true, baseAddr, octetOffset, oldMetadata, fieldOffset, avdOffset, siteID);
    }
    
    // Now put in the logging code
    RVMThread currentThread = RVMThread.getCurrentThread();
    currentThread.bpCount++; // Increment boundary point because of the slow path
    
    if (AVD.logRdWrAccesses()) { // Now put in the logging code
      if (OctetState.isReadExcl(oldMetadata) || OctetState.isReadSharedPossiblyUnfenced(oldMetadata)) {
        // RdEx_T1 --> Rd_T or RdSh --> Rd_T
        AVDHashTable.addToHashTableFastPath(currentThread, baseAddr, fieldOffset, AVD.REGULAR_READ/*, siteID*/);
      } else { // WrEx_T1 --> Rd_T, the boundary point fast path is bound to fail
        AVDLogger.avdMetadataSlowPath(currentThread, baseAddr, fieldOffset, avdOffset, /*siteID,*/ AVD.REGULAR_READ);
      }
    }
  }
  
  @NoInline
  static final void readSlowPath(Address baseAddr, Offset octetOffset, Word oldMetadata, int fieldOrIndexInfo, int avdOffset, int siteID) {
    boolean switchSlowPath = Octet.getClientAnalysis().alternateReadSlowPath(baseAddr, octetOffset, oldMetadata, fieldOrIndexInfo, siteID);
    if (!switchSlowPath) {
      slowPath(true, baseAddr, octetOffset, oldMetadata, fieldOrIndexInfo, avdOffset, siteID);
    }
  }

  @NoInline
  static final void writeSlowPath(Address baseAddr, Offset octetOffset, Word oldMetadata, int fieldOrIndexInfo, int avdOffset, int siteID) {
    boolean switchSlowPath = Octet.getClientAnalysis().alternateWriteSlowPath(baseAddr, octetOffset, oldMetadata, fieldOrIndexInfo, siteID);
    if (!switchSlowPath) {
      slowPath(false, baseAddr, octetOffset, oldMetadata, fieldOrIndexInfo, avdOffset, siteID);
    }
  }

  @Inline
  public static final void slowPath(boolean isRead, Address baseAddr, Offset octetOffset, Word oldMetadata, int fieldOrIndexInfo, int avdOffset, int siteID) {
    if (Octet.getConfig().noSlowPath()) {
      return;
    }
    Stats.slowPathsEntered.inc(siteID);
    long startTime = (Octet.getConfig().stats() || VM.VerifyAssertions) ? Time.nanoTime() : 0;

    // Allow slow path to change the state even for non-Octet threads
    // Octet: TODO: trying out this change
    boolean exitSlowPath = false; //!RVMThread.getCurrentThread().isOctetThread();
    if (!exitSlowPath) {
      exitSlowPath = Octet.getClientAnalysis().alternateSlowPath(true, baseAddr, octetOffset, oldMetadata, fieldOrIndexInfo, siteID);
    }
    if (exitSlowPath) {
      Stats.slowPathsExitedEarly.inc(siteID);
      return;
    }

    // Figure out if big objects are causing a lot of state transitions
    if (Octet.getConfig().stats()) {
      // Determine whether we're dealing with an object or a static by comparing with the JTOC pointer
      if (baseAddr.NE(Magic.getTocPointer())) {
        int bytesUsed = ObjectModel.bytesUsed(baseAddr.toObjectReference().toObject());
        Stats.logSlowPathObjectSize.incBin(bytesUsed);
      }
    }
    
    // we're here, so we've got to do a CAS or an invalidation
    // (unless some other thread happens to change the state to RdSh in the meantime)
    int iter;
    for (iter = 0; ; iter++) {
      
      if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(false)) {
        boolean exitLoop = Octet.getClientAnalysis().handleEventsInSlowPathLoop(isRead, baseAddr, octetOffset, oldMetadata, fieldOrIndexInfo, siteID);
        if (exitLoop) {
          break;
        }
      }
      // check for the uninitialized state
      if (oldMetadata.EQ(MIN_READ_SHARED)) {
        Word newMetadata = getInitial();
        boolean result = baseAddr.attempt(oldMetadata, newMetadata, octetOffset);
        if (result) {
          Stats.Uninit_Init.inc();
          
//          // AVD: Initialize the per-field word  
//          if (AVD.addPerFieldMetadata()) {
//            RVMThread currentThread = RVMThread.getCurrentThread();
//            if (currentThread.isOctetThread()) {
//              newMetadata = AVDMetadataHelper.createOrUpdateMetadata(currentThread, currentThread.bpCount, isRead);
//            } 
//            if (VM.VerifyAssertions) { VM._assert(avdOffset != AVD.UNINITIALIZED_OFFSET); }
//            Word oldValue = baseAddr.prepareWord(Offset.fromIntSignExtend(avdOffset));
//            result = baseAddr.attempt(oldValue, newMetadata, Offset.fromIntSignExtend(avdOffset));
//            if (VM.VerifyAssertions) { VM._assert(result); }
//          }
          
          break;
        }
        // otherwise, we'll need to re-load the metadata (below)

      // for reads: check for a read-shared that this thread isn't up-to-date on
      } else if (isRead && isReadSharedPossiblyUnfenced(oldMetadata)) {
        // It should be unfenced because the fast path failed
        if (VM.VerifyAssertions) { VM._assert(isReadSharedUnfenced(oldMetadata)); }
        RVMThread.getCurrentThread().octetReadSharedCounter = oldMetadata;
        // A load fence here is needed to ensure happens-before between this point
        // and the last RdEx->RdSh transition on this object.
        Magic.readCeiling();
        Stats.RdSh_RdSh_Fence.inc();
        if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(true)) {
          // Let the client analysis handle the RdSh fence transition
          Octet.getClientAnalysis().handleRdShFenceTransition(oldMetadata, siteID);
        }
        break;

      // for reads: try upgrading from RdEx->RdSh
      } else if (isRead && getState(oldMetadata).EQ(READ_EXCL)) {
        if (VM.VerifyAssertions) { VM._assert(!isReadSharedPossiblyUnfenced(oldMetadata)); }

        // Octet: LATER: We can actually transition RdEx->RdEx if the thread has died.
        // For now, let's just count how often that happens.
        // It might not be worth doing because it might complicate client analyses.
        RVMThread oldThread = getThreadFromExclusive(oldMetadata); // Points to the last thread that read the object in exclusive state
        if (oldThread.getExecStatus() == RVMThread.TERMINATED) {
          //newMetadata = getExclusive(READ_EXCL);
          //boolean result = addr.attempt(oldMetadata, newMetadata, offset);
          //if (result) {
            Stats.rdExToRdShButOldThreadDied.inc();
            //break;
          //}
        }
        
        // RdEx->RdSh
        // First, put the global RdSh counter into a special locked state
        boolean result = false;
        Word oldValue = lockReadSharedCounter();
        // This thread currently has exclusive access to the lock
        Word newMetadata = oldValue.minus(READ_SHARED_DEC);
        check(newMetadata);
        
        // This CAS may fail
        result = baseAddr.attempt(oldMetadata, newMetadata, octetOffset);
        if (result) {
          // We can update this thread's read shared counter, i.e., a fence transition is not necessary
          // for this object on this thread
          RVMThread.getCurrentThread().octetReadSharedCounter = newMetadata;
          if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(true)) {
            Octet.getClientAnalysis().handleBeforeUpdatingGlobalRdShCounter();
          }
          // Octet: TODO: This assertion should not fail -- but apparently it's been failing at some point?
          if(VM.VerifyAssertions) {
            VM._assert(globalReadSharedCounter == READ_SHARED_COUNTER_LOCKED);
          }
          Magic.writeFloor(); // All pending stores should complete before executing this fence
          // Reset the lock variable, no need to use a CAS 
          // Word newMetadata = decReadSharedCounter();
          globalReadSharedCounter = newMetadata;
          // Now that we're out of the critical section, let the client analysis handle the transition
          Stats.RdEx_RdSh.inc();
          if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(true)) {
            Octet.getClientAnalysis().handleRdExToRdShUpgradingTransition(oldThread, newMetadata, siteID);
          }
          break;
        } else {
          // Reset the lock since the CAS failed
          globalReadSharedCounter = oldValue;
        }
        
      // Writes: Try for an upgrading transition from RdEx to WrEx for the same thread.
      } else if (!isRead && isExclForCurrentThread(oldMetadata)) {
        if (VM.VerifyAssertions) { VM._assert(getState(oldMetadata).EQ(READ_EXCL)); }
        Word newMetadata = getExclusive(WRITE_EXCL);
        check(newMetadata);
        
        boolean result = baseAddr.attempt(oldMetadata, newMetadata, octetOffset);
        if (result) {
          if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(true)) {
            Octet.getClientAnalysis().handleRdExToWrExUpgradingTransition(newMetadata, siteID);
          }
          Stats.RdEx_WrEx_Same.inc();
          break;
        }

      // Conflicting transition
      } else {
        boolean result = handleIntermediateState(baseAddr, octetOffset, oldMetadata, isRead ? READ_EXCL : WRITE_EXCL, fieldOrIndexInfo, siteID);
        if (result) {
          if (isRead) {
            Stats.WrEx_RdEx.inc();
          } else if (isReadSharedPossiblyUnfenced(oldMetadata)) {
            Stats.RdSh_WrEx.inc();
          } else if (getState(oldMetadata).EQ(WRITE_EXCL)) {
            Stats.WrEx_WrEx_Diff.inc();
          } else {
            if (VM.VerifyAssertions) { VM._assert(getState(oldMetadata).EQ(READ_EXCL)); }
            Stats.RdEx_WrEx_Diff.inc();
          }
          break;
        }
      }
      // If any of the above failed, we need to go back into the loop because the object state could change back to some read state

      if (VM.VerifyAssertions) {
        startTime = checkIfStuck(startTime, oldMetadata);
      }

      // Supports performance-testing configurations
      if (Octet.getConfig().noWaitForCommunication() || Octet.getConfig().noWaitOnIntermediateState()) {
        break;
      }
      
      // Octet: LATER: consider this policy of when and whether to yield
      if (iter > 3) {
        if (Octet.getConfig().doCommunication()) {
          Communication.blockCommunicationRequests(true);
        }
        RVMThread.yieldNoHandshake();
        if (Octet.getConfig().doCommunication()) {
          Communication.unblockCommunicationRequests();
        }
        // Octet: LATER: implement waiting instead of just yielding?
      } else {
        // decrease performance hit from spin-wait loops
        Magic.pause();
        
        // check for communication requests in case we're in this loop for a while (might actually be necessary to not get stuck)
        if (Octet.getConfig().doCommunication()) {
          Communication.checkForCommunicationRequests(false);
        }
      }
      
      // re-load the metadata in case it changed
      oldMetadata = baseAddr.prepareWord(octetOffset);
      check(oldMetadata);
      // And check for read shared (only an already-fenced one here) in case we don't actually need to change the state.
      if (isRead && isReadSharedAndFenced(oldMetadata)) {
        // It's actually possible to read a fenced RdSh here.
        // It seems pretty unlikely because it means that a lot has to happen on this thread (multiple barriers) between
        // when another thread updates the gRdShCtr and changes the object from RdEx to RdSh.
        // But a client analysis (like RR) that does a lot of work when incrementing the gRdShCtr, makes this case more likely.
        Stats.RdSh_RdSh_NoFence.inc();
        break;
      }
    } // end of loop
    Stats.logSlowPathIter.incBin(iter);
    Stats.logTimeSlowPath.incTime(startTime);
  }
  
  /** Handle an object that we'd like to put in the intermediate state; it might already be in the intermediate state.
      Return true iff we successfully put the object in the newState state and are thus ready to access the object. */
  @Inline
  static final boolean handleIntermediateState(Address baseAddr, Offset offset, Word oldMetadata, Word newState, int fieldOffset, int siteID) {
    
    // is it already in the intermediate state
    if (getState(oldMetadata).EQ(INTERMEDIATE)) {
      /* Octet: LATER: implement?
      // it's already in the intermediate state, so let's try to wait for it
      if (VM.VerifyAssertions) { verify inter-with-waiter is superset of intermediate; }
      Word metadata = oldMetadata.or(INTERMEDIATE_WITH_WAITER);
      */
    } else {
      // well, it's in the right state, so we'll try to steal by putting it in the intermediate state
      Word intermediateMetadata = getIntermediate();
      check(intermediateMetadata);
      boolean result = baseAddr.attempt(oldMetadata, intermediateMetadata, offset);
      if (result) {
        // Don't actually perform communication for a non-Octet thread.  Just allow the state to be changed.
        // Octet: TODO: trying out this change
        if (Octet.getConfig().doCommunication() &&
            RVMThread.getCurrentThread().isOctetThread()) {
          Stats.blockCommReceiveResponses.inc();
          if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(true)) {
            Octet.getClientAnalysis().handleEventBeforeCommunication(baseAddr, offset, oldMetadata, newState, fieldOffset, siteID);
          }
          Communication.blockCommunicationRequests(false);
          Communication.communicate(baseAddr, offset, oldMetadata, newState, fieldOffset, siteID);
          Communication.unblockCommunicationRequests();
          if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(true)) {
            Octet.getClientAnalysis().handleEventAfterCommunication(baseAddr, offset, oldMetadata);
          }
        }
        // Octet: LATER: CAS is probably overkill
        Word newMetadata = getExclusive(newState);
        check(newMetadata);
        result = baseAddr.attempt(intermediateMetadata, newMetadata, offset);
        // Octet: LATER: why is/was this CAS failing sometimes?
        if (VM.VerifyAssertions) { VM._assert(result); }
        Stats.conflictingTransitions.inc(siteID);
        return true;
      }
    }
    return false;
  }

  /** For debugging purposes */
  @NoInline
  static final long checkIfStuck(long startTime, Word oldMetadata) {
    if (Stats.nsElapsed(startTime) >= 5000L*1000*1000) {
      RVMThread.debugLock.lockNoHandshake();
      VM.sysWriteln("Got stuck trying to change to intermediate state");
      RVMThread.dumpStack();
      if (VM.VerifyAssertions) { VM._assert(getState(oldMetadata).EQ(INTERMEDIATE)); }
      RVMThread remoteThread = getThreadFromIntermediate(oldMetadata);
      VM.sysWriteln("Remote thread slot = ", remoteThread.threadSlot);
      Stats.tryToPrintStack(remoteThread.framePointer);
      
      //RVMThread.dumpVirtualMachine();
      for (RVMThread otherThread : RVMThread.threadBySlot) {
        if (Communication.isValidOtherThread(otherThread, RVMThread.getCurrentThread())) {
          VM.sysWriteln("Trying to print thread ", otherThread.threadSlot);
          Stats.tryToPrintStack(otherThread.framePointer);
        }
      }
      VM.sysWriteln();
      VM.sysWriteln();
      VM.sysWriteln();
      RVMThread.debugLock.unlock();

      VM.sysFail("Got stuck");
      //return Time.nanoTime();
    }
    return startTime;
  }

}
