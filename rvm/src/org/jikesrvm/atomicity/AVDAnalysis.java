package org.jikesrvm.atomicity;

import static org.jikesrvm.ia32.StackframeLayoutConstants.INVISIBLE_METHOD_ID;
import static org.jikesrvm.ia32.StackframeLayoutConstants.STACKFRAME_SENTINEL_FP;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.baseline.ia32.AVDBaselineInstr;
import org.jikesrvm.compilers.baseline.ia32.OctetBaselineInstr;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.compilers.opt.AVDOptInstr;
import org.jikesrvm.compilers.opt.OctetOptInstr;
import org.jikesrvm.compilers.opt.OctetOptSelection;
import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.jikesrvm.compilers.opt.RedundantBarrierRemover.AnalysisLevel;
import org.jikesrvm.mm.mminterface.Barriers;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.octet.ClientAnalysis;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.octet.OctetState;
import org.jikesrvm.octet.Site;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.NoCheckStore;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/** This class contains methods to construct the cross-thread dependence graph. */
@Uninterruptible
public final class AVDAnalysis extends ClientAnalysis {

  public static boolean isVMFullyBooted = false;
  
  /* **********************************************************
   *  The following section overrides octet.ClientAnalysis methods 
   * **********************************************************/
  
  /** Allows the client analysis to do something when the VM is booting. This is called before the VM is fully booted.
   * <ol>
   * <li> Clean/remove earlier .png files if any.
   * <li> Create new graphviz file.
   * <li> Read method names from the file.
   * </ol>
   * */
  @Interruptible
  @Override
  protected void boot() {
    // AVD: Make boot conditional on whether transactions will be created. 
    if (AVD.methodsAsTransactions()) {
      AVD.boot();  
    }
    
    AVDPhase2Thread.boot(); // Start the PCD
  }

  /**
   * The responding thread is at a block point, so the requesting thread (i.e, the current thread) can safely create the
   * dependence edge without worrying about races, but there is actually no response required in this case -- implicit 
   * protocol
   *
   *  Conflicting transitions:
   *    WrEx -> WrEx|RdEx 
   *    RdEx -> WrEx  
   *    RdSh -> WrEx 
   */
  @Override
  public void handleConflictForBlockedThread(RVMThread respThread, Address baseAddr, Offset octetOffset, Word oldOctetMetadata,
      Word newState, boolean remoteThreadIsDead, int newRemoteRequests, int fieldOffset, int siteID) {
    RVMThread currentThread = RVMThread.getCurrentThread();
    AVDNode currentTrans = currentThread.currentTransaction;
    if (VM.VerifyAssertions) { VM._assert(currentTrans.nextNode == null); } // Current transaction should be ongoing
    if (VM.VerifyAssertions) { VM._assert(respThread.isOctetThread()); }
    AVDNode respTrans = respThread.currentTransaction;

    // First, create a boundary point for current transaction
    createOwnBoundaryPoint(currentThread, currentTrans, respThread, respTrans, respTrans.currentReadWriteLogIndex,
        /*recordConflict = */ true, /*isExplicitProtocolAndRespondingThread = */ false);
    // currentTrans might have been updated over here if the current thread is in a unary transaction
    currentTrans = currentThread.currentTransaction;
    // Check whether a boundary point needs to be created for the remote transaction
    if (!remoteThreadIsDead) {
      setRemoteBoundaryPointCreationRequest(currentThread, respThread, respTrans);
    }
    createEdgeHelper(respTrans, currentTrans, /*conflictType = */ 1);

    if (OctetState.isReadExcl(newState)) {
      // This has to be a WrEx -> RdEx transition
      if (VM.VerifyAssertions) { VM._assert(OctetState.isWriteExclForGivenThread(respThread, oldOctetMetadata)); }
      currentThread.lockForLastWrExToRdEx.lock();
      currentThread.lastTransToPerformWrExToRdEx = currentTrans;
      currentThread.lastLogIndexToPerformWrExToRdEx = currentTrans.currentReadWriteLogIndex;
      currentThread.lockForLastWrExToRdEx.unlock();
    }
  }

  /** Here we create a boundary point for the thread {@code currentThread}. For regular transactions:
   * <ol>
   * <li> Add conflict info, clear hash table, and increment bp counter
   * </ol>
   * For unary transactions:
   * <ol>
   * <li> Start new unary transaction, we are not recording any conflict info. This means that Phase 2 serializability 
   *      must process nodes/transactions in order.
   * <li> Clearing hash table is done as part of creating a new transaction
   * </ol>
   * @param currentThread
   * @param currentNode
   */
  private void createOwnBoundaryPoint(RVMThread currentThread, AVDNode currentNode, RVMThread remoteThread, AVDNode remoteTrans,
      int remoteIndex, boolean recordConflict, boolean isExplicitProtocolAndRespondingThread) {
    if (currentThread.inTransaction()) { // Regular transaction
      if (VM.VerifyAssertions) { VM._assert(!currentNode.isUnaryTrans); }

      // Record conflict access info
      if (AVD.recordConflictingAccess() && recordConflict) {
        AVDLogger.recordConflictAccess(remoteThread, remoteTrans, remoteIndex, currentThread);
      }

      if (AVD.recordAccessInfoInHashTable()) {
        AVDHashTable.clear(currentThread);
      }
    } else { // Unary transaction
      if (VM.VerifyAssertions) { VM._assert(currentNode.isUnaryTrans); }
      // Avoid creating multiple unary transactions for the responding thread in the explicit protocol when it is
      // potentially responding to more than one requesting thread
      if (isExplicitProtocolAndRespondingThread && currentThread.unaryTransactionAlreadyCreated) {
        return;
      }
      int siteID = currentNode.siteID;
      createUnaryTransaction(currentThread, siteID);
      currentThread.unaryTransactionAlreadyCreated = true;
    }
  }

  @NoCheckStore
  private void setRemoteBoundaryPointCreationRequest(RVMThread currentThread, RVMThread remoteThread, AVDNode remoteNode) {
    // This is not a data race, since it is a volatile
    remoteThread.createBoundaryPoint = true;
    // Set own values which the remote thread can read later (if required) without data races
    int index = currentThread.threadSlot;
    remoteThread.txArrayForBPCreation[index] = currentThread.currentTransaction;
    remoteThread.logIndexArrayForBPCreation[index] = currentThread.currentTransaction.currentReadWriteLogIndex;
  }

  /** Callback for handling events after a (responding/requesting) thread unblocks.
   *  Only called if requests were received while blocked. */
  @Override
  public void handleEventsAfterUnblockIfRequestsReceivedWhileBlocked(int newRequestCounter) {
    RVMThread currentThread = RVMThread.getCurrentThread();
    if (currentThread.createBoundaryPoint) {
      AVDNode currentTrans = currentThread.currentTransaction;
      if (currentThread.inTransaction()) { // Regular transaction
        if (VM.VerifyAssertions) { VM._assert(!currentTrans.isUnaryTrans); }
        // Clear hash table, this is redundant for the requesting thread, but is required for the responding thread
        if (AVD.recordAccessInfoInHashTable()) {
          AVDHashTable.clear(currentThread);
        }
      } else { // Unary transaction
        if (VM.VerifyAssertions) { VM._assert(currentTrans.isUnaryTrans); }
        if (!currentThread.unaryTransactionAlreadyCreated) { // Unary transaction not already created as part of current communication
          int siteID = currentTrans.siteID;
          createUnaryTransaction(currentThread, siteID);
        }
      }
      currentThread.bpCount++;
      currentThread.createBoundaryPoint = false;
    } else { // Nothing to do
    }
  }

  @Override
  @Inline
  public void handleRequestSentToUnblockedThread(RVMThread remoteThread, int newRemoteRequests, int siteID) {
    // The current thread is already blocked at this point, so we cannot assume any assertions on createBoundaryPoint
    // for the current thread
  }

  /** Callback for a round-trip communication response at a safe point. */
  @Override
  public void handleRequestOnRespondingThread(RVMThread reqThread) {
    RVMThread respThread = RVMThread.getCurrentThread();
    AVDNode respTrans = respThread.currentTransaction;
    // First, create a boundary point for current transaction
    createOwnBoundaryPoint(respThread, respTrans, reqThread, reqThread.currentTransaction,
        reqThread.currentTransaction.currentReadWriteLogIndex, /*recordConflict = */ false,
        /*isExplicitProtocolAndRespondingThread = */ true);
    setRemoteBoundaryPointCreationRequest(respThread, reqThread, reqThread.currentTransaction);
    // Requesting thread will create the edge, since it might need to create a new unary transaction
  }

  /** This hook is executed in the context of a requesting thread, it creates its own bp. Then, it
   *  creates a cross-thread edge from the remote conflicting thread.
   *  This also tracks WrEx->RdEx transitions. */
  @NoCheckStore // Is this required?
  @Override
  public void handleReceivedResponse(RVMThread respThread, Word oldMetadata, Word newState) {
    RVMThread reqThread = RVMThread.getCurrentThread();
    AVDNode reqTrans = reqThread.currentTransaction;
    if (VM.VerifyAssertions) {
      VM._assert(respThread.isOctetThread());
      VM._assert(reqThread.createBoundaryPoint);
    }
    int slot = respThread.threadSlot;
    if (VM.VerifyAssertions) { VM._assert(respThread == reqThread.txArrayForBPCreation[slot].octetThread); }
    createOwnBoundaryPoint(reqThread, reqTrans, respThread, reqThread.txArrayForBPCreation[slot], 
        reqThread.logIndexArrayForBPCreation[slot], /*recordConflict = */ true, /*isExplicitProtocolAndRespondingThread = */ false);
    // currentTrans might have been updated over here if the current thread is in a unary transaction
    reqTrans = reqThread.currentTransaction;
    // Need to create dependence edge between the relevant transaction in responding thread and the current thread
    createEdgeHelper(reqThread.txArrayForBPCreation[slot], reqTrans, /*conflictType = */ 4);

    if (OctetState.isReadExcl(newState)) {
      // This has to be a WrEx -> RdEx transition
      if (VM.VerifyAssertions) { OctetState.isWriteExclForGivenThread(respThread, oldMetadata); }
      reqThread.lockForLastWrExToRdEx.lock();
      reqThread.lastTransToPerformWrExToRdEx = reqTrans;
      reqThread.lastLogIndexToPerformWrExToRdEx = reqTrans.currentReadWriteLogIndex;
      reqThread.lockForLastWrExToRdEx.unlock();
    }
  }

  /** Callback before the global read share counter is updated. Here we store a reference to the
   *  current transaction which is about to update the global read-shared counter. Before actually updating,
   *  we copy the last transaction reference to a thread-local variable, since we will be creating edges
   *  from the that transaction reference to the current transaction. */
  @Override
  public void handleBeforeUpdatingGlobalRdShCounter() {
    RVMThread currentThread = RVMThread.getCurrentThread();
    AVD.lastTransToUpdateGlobalRdShCounterLock.lock();
    // lastTransToUpdateGlobalRdShCounter will be null for the first upgrading transition
    currentThread.penultimateTransToUpdateGlobalRdShCounter = AVD.lastTransToUpdateGlobalRdShCounter;
    // logIndex will be -1 for the first upgrading transition
    currentThread.logIndexForPenultimateTransToUpdateGlobalRdShCounter = AVD.logIndexForLastTransToUpdateGlobalRdShCounter;

    AVD.lastTransToUpdateGlobalRdShCounter = currentThread.currentTransaction;
    AVD.logIndexForLastTransToUpdateGlobalRdShCounter = currentThread.currentTransaction.currentReadWriteLogIndex;
    AVD.lastTransToUpdateGlobalRdShCounterLock.unlock();
  }

  /** Here we are doing two things:
   *  1. Creating cross thread edges between the last thread to update the global read shared counter and
   *     the current transaction, i.e., RdSh -> RdSh
   *  2. Create an edge for the RdEx -> RdSh upgrading transition
   */
  @Override
  public void handleRdExToRdShUpgradingTransition(RVMThread oldThread, Word newMetadata, int siteID) {
    RVMThread currentThread = RVMThread.getCurrentThread();
    boolean isFirstBPCreated = false;

    // First part
    
    // Create an edge from penultimateThreadToUpdateGlobalRdSharedCounter to the current node/transaction
    AVDNode temp = currentThread.penultimateTransToUpdateGlobalRdShCounter;
    currentThread.penultimateTransToUpdateGlobalRdShCounter = null; // Reset this to prevent holding on to the node

    if (temp != null && temp.octetThread != currentThread) { // temp is null for the very first upgrading transition
      if (VM.VerifyAssertions) { VM._assert(temp.octetThread.isOctetThread()); } // Trivial
      if (VM.VerifyAssertions) { VM._assert(currentThread.logIndexForPenultimateTransToUpdateGlobalRdShCounter != -1); }
      
      // First, create a boundary point
      createOwnBoundaryPoint(currentThread, currentThread.currentTransaction, temp.octetThread, temp,
          currentThread.logIndexForPenultimateTransToUpdateGlobalRdShCounter, /*recordConflict = */ true,
          /*isExplicitProtocolAndRespondingThread = */ false);
      isFirstBPCreated = true;
      createEdgeHelper(temp, currentThread.currentTransaction, /*conflictType = */ 2);
    }

    // Second part
    
    // There is a dependency from the remote transaction to the current transaction
    // Create an edge from the last read transaction of the oldThread to the last node of the
    // current thread, do nothing if you are the same thread
    // Since we are now tracking locks, therefore oldThread can be a non-Octet thread
    if (oldThread.isOctetThread()) {
      oldThread.lockForLastWrExToRdEx.lock();
      AVDNode tmp = oldThread.lastTransToPerformWrExToRdEx;
      int tmp1 = oldThread.lastLogIndexToPerformWrExToRdEx;
      oldThread.lockForLastWrExToRdEx.unlock();
      // AVD: LATER: We expect tmp to be not null, but sometimes tmp is null. Interestingly, this used to happen only 
      // for remote thread with octet id 1 whose lastTransToPerformWrExToRdEx is never set. Under what situations
      // is that possible? But I did see the failure for Octet thread id 2 with xalan6.
      if (VM.VerifyAssertions) { VM._assert(tmp != null); }
      // Calling createOwnBoundaryPoint() is conditional because we do not want to create two unary nodes
      // when !inTransaction() is true, we can reuse the last unary created
      if (!isFirstBPCreated) {
        if (VM.VerifyAssertions) { VM._assert(temp == null || temp.octetThread == currentThread); }
        createOwnBoundaryPoint(currentThread, currentThread.currentTransaction, oldThread, tmp, tmp1, true, false);
        // currentThread.currentTransaction may change if a boundary point is created for unary transactions
      } else if (currentThread.inTransaction()) {
        if (AVD.recordConflictingAccess()) {
          AVDLogger.recordConflictAccess(oldThread, tmp, tmp.currentReadWriteLogIndex, currentThread);
        }
        // Hash table is already cleared during first boundary point creation
      }
      createEdgeHelper(tmp, currentThread.currentTransaction, /*conflictType = */ 2);
    }
  }

  @Override
  public void handleRdShFenceTransition(Word oldOctetMetadata, int siteID) {
    AVD.lastTransToUpdateGlobalRdShCounterLock.lock();
    AVDNode tempTrans = AVD.lastTransToUpdateGlobalRdShCounter;
    int tempLogIndex = AVD.logIndexForLastTransToUpdateGlobalRdShCounter;
    AVD.lastTransToUpdateGlobalRdShCounterLock.unlock();

    // Every fence transition is preceded by a corresponding upgrading transition, so we might expect that
    // tempTrans should not be null. But the following assertion won't work always.
    //if (VM.VerifyAssertions) { VM._assert(tempTrans != null); }
    // tempTrans can be null if we allow state transitions for non-octet threads
    // This can happen when the "first" upgrading transition is from a non-octet thread for which the slow path
    // hook is not called, and the corresponding fence transition happens on an octet thread
    // Usually, it is lusearch9 that fails because of this in FastAdaptive, although it doesn't fail as often for
    // FullAdaptive.
    if (tempTrans != null) {
      if (VM.VerifyAssertions) { VM._assert(tempTrans.octetThread.isOctetThread()); }
      // This is where we need to create a cross-thread edge between the last thread to update the global
      // read-shared counter and the current thread executing the fence
      RVMThread currentThread = RVMThread.getCurrentThread();
      if (tempTrans.octetThread != currentThread) {
        // First, create a boundary point for the current thread
        createOwnBoundaryPoint(currentThread, currentThread.currentTransaction, tempTrans.octetThread, tempTrans, tempLogIndex,
            /*recordConflict = */ true, /*isExplicitProtocolAndRespondingThread = */ false);
        // AVD: LATER: Why don't we create a boundary point for the remote thread?
        createEdgeHelper(tempTrans, currentThread.currentTransaction, /*conflictType = */ 3);
      }
    }
  }

  /** Callback for upgrading transition from RdEx -> WrEx, called from the requester thread. */
  @Override
  public void handleRdExToWrExUpgradingTransition(Word newMetadata, int siteID) {
    // There is no cross-thread dependency over here
  }

  /** Handle events before starting communication, resetting certain thread-local variables */
  @Override
  public void handleEventBeforeCommunication(Address baseAddr, Offset offset, Word oldMetadata, Word newState,
                                int fieldOffset, int siteID) {
  }

  /** Handle events after finishing communication */
  @Override
  @Inline
  public void handleEventAfterCommunication(Address baseAddr, Offset offset, Word oldMetadata) {
    RVMThread currentThread = RVMThread.getCurrentThread();
    // Resetting variables
    currentThread.createBoundaryPoint = false;
    currentThread.unaryTransactionAlreadyCreated = false;
  }

  /** Callback for handling events after a thread (could be the responding/requesting) unblocks. */
  @Override
  public void handleEventsAfterUnblockCommunication() {
    // Don't think we need to do anything over here
  }

  /** This hook is called once after completing responding to all requests. */
  @Override
  public void handleResponsesUpdated(int oldRequestsCounter, int newResponsesCounter) {
    // Don't think we need to do anything over here
  }

  @Interruptible
  @Override
  public void handleThreadTerminationEarly() {
    // AVD: LATER: This assertion sometimes fail for few benchmarks: avrora9, raytracer
    if (VM.VerifyAssertions) {
      if (RVMThread.getCurrentThread().inTransaction()) {
        VM.sysWriteln("Current thread:", RVMThread.getCurrentThread().octetThreadID);
        RVMThread.dumpStack();
      }
      VM._assert(!RVMThread.getCurrentThread().inTransaction());
    }
    super.handleThreadTerminationEarly();
  }

  /** Callback from thread termination.
      Client analyses can override this, but then the overriding method probably needs to call super.handleThreadTermination()! */
  @Override
  public void handleThreadTerminationLate() {
    super.handleThreadTerminationLate(); // This needs to be invoked
    handleThreadTerminationHelper();
  }

  public static void handleThreadTerminationHelper() {
    RVMThread currentThread = RVMThread.getCurrentThread();
    AVDNode last = currentThread.currentTransaction;

    // First add a dummy node to indicate that the current transaction (unary) is done
    AVDNode dummyEnd = createNewNode(currentThread, /*site = */ AVD.UNINITIALIZED_SITE, /*isUnary = */ true);
    // We are not adding the new dummy end node to the list using addNode(), so that currentTransaction variable
    // is not updated. This will prevent the last dummy node from being considered in future analysis
    last.nextNode = dummyEnd;

    // Check for SCC from the "penultimate" node. We don't need to detect SCCs if harness end is 
    // already invoked, since we won't perform serializability analysis on them anyway.
    if (AVD.invokeCycleDetection() && !MemoryManager.isHarnessEndReached()) {
      AVDSCCComputation.findSCCHelper(last);
    }

    currentThread.accessInfoHashTable = null;
    currentThread.linearArray = null;
    currentThread.indexOfLinearArray= 0;
    currentThread.bpCount++;
    currentThread.txArrayForBPCreation = null;
    currentThread.logIndexArrayForBPCreation = null;
    currentThread.penultimateTransToUpdateGlobalRdShCounter = null;
  }

  static void printThreadInfo(RVMThread thread) {
    VM.sysWrite("Thread is terminating: ");
    VM.sysWrite("Thread id: ", thread.octetThreadID, ", No. of nodes: ", thread.numberOfNodes);
    VM.sysWrite(", In Trans:", thread.inTransaction());
    if (thread.logBufferReferenceArray != null) {
      VM.sysWrite(", Current size of log buffer:", thread.logBufferReferenceArray.length());
    }
    VM.sysWrite(", Current log buffer array index:", thread.logBufferReferenceArrayIndex);
    VM.sysWrite(", Current word array buffer index:", thread.wordArrayBufferIndex);

    // "accessInfoHashTable" and "linearArray" are already null for the last thread during exit, since handleThreadTerminationHelper()
    // is invoked from RVMThread::terminate()
    if (AVD.recordAccessInfoInHashTable() && thread.accessInfoHashTable != null) {
      VM.sysWriteln(", ENTRIES IN HASH TABLE:", thread.accessInfoHashTable.length() >>> AVDHashTable.LOG_NUM_WORDS_IN_CELL);
    } else {
      VM.sysWriteln();
    }
    if (AVD.useLinearArrayOfIndices() && thread.linearArray != null) {
      VM.sysWriteln(", SIZE OF LINEAR ARRAY:", thread.linearArray.length());
    } else {
      VM.sysWriteln();
    }
  }

  /** AVD uses the holding state */
  @Override
  @Inline
  protected boolean useHoldingState() {
    return true;
  }

  /** AVD will use the communication queue. */
  @Override
  @Inline
  public boolean needsCommunicationQueue() {
    return true;
  }

  /** AVD needs field info, therefore Octet barriers should pass field or array index info */
  @Override
  @Inline
  protected boolean needsFieldInfo() {
    return true;
  }
  
  /** Should the Octet barrier pass the field ID (value = false) or the field offset (value = true)?
   *  Only relevant if needsFieldInfo() == true */
  @Inline
  @Override
  protected boolean useFieldOffset() {
    return true;
  }

  @Override
  @Inline
  public boolean needsSites() {
    return Octet.getConfig().needsSites();
  }

  /** AVD does not require site information to be unique */
  @Override
  @Inline
  protected boolean needsUniqueSites() {
    return false;
  }

  /** AVD does not support inserting IR instructions as barriers. */
  @Override
  public boolean supportsIrBasedBarriers() {
    return false;
  }

  /** Override Octet barriers. */
  @Override
  public NormalMethod chooseBarrier(NormalMethod method, boolean isRead, boolean isField, boolean isResolved,
        boolean isStatic, boolean hasRedundantBarrier, boolean isSpecialized) {
    return AVDInstrDecisions.chooseAVDBarrier(isRead, isField, isResolved, isStatic);
  }
  
  @Override
  public boolean incRequestCounterForImplicitProtocol() {
    return false;
  }
  
  /** Support overriding/customizing barrier insertion in the baseline compiler */
  @Interruptible
  @Override
  public OctetBaselineInstr newBaselineInstr() {
    return new AVDBaselineInstr();
  }

  /** Support overriding/customizing the choice of which instructions the opt compiler should instrument */
  @Interruptible
  @Override
  public OctetOptSelection newOptSelect() {
    return new OctetOptSelection();
  }

  /** Support overriding/customizing barrier insertion in the opt compiler */
  @Interruptible
  @Override
  public OctetOptInstr newOptInstr(boolean lateInstr, RedundantBarrierRemover redundantBarrierRemover) {
    return new AVDOptInstr(lateInstr, redundantBarrierRemover);
  }

  /** AVD wants to log access to every field. */
  @Interruptible
  @Override
  public RedundantBarrierRemover newRedundantBarriersAnalysis() {
    return new RedundantBarrierRemover(AnalysisLevel.NONE);
  }

  /* **********************************************************
   *  The following methods are for constructing the imprecise transactional happens-before graph 
   * **********************************************************/
  
  // AVD: LATER: We can possibly make the start/end transaction methods interruptible, then GC can take place.
  // Cycle detection should not run into problems, since all the nodes that are part of a
  // a cycle should be reachable, but what about nodes that are not part of cycles and we
  // are traversing it?
  
  /**
   * Entry point called from Baseline and Opt compilers. This is only called for regular
   * transactions, and not for unary transactions. This method should get inlined as it
   * is part of the fast path.
   * @param site site ID
   * @param methodID the ID of the current method
   * */
  @Entrypoint
  public static void startTransaction(int site, int methodID) {
    //if (VM.VerifyAssertions) { VM._assert(AVD.methodsAsTransactions() || AVD.syncBlocksAsTransactions()); }
    RVMThread currentThread = RVMThread.getCurrentThread();
    if (!currentThread.isOctetThread()) { // We do not want to process non-Octet threads
      return;
    }
    
    // Sanity checks
    //if (VM.VerifyAssertions) { VM._assert(!currentThread.inTransaction()); }
    AVDNode lastTrans = currentThread.currentTransaction;
    //if (VM.VerifyAssertions) { VM._assert(lastTrans.isUnaryTrans); } // Last transaction/node should always be a unary transaction
    
    // Perform book keeping for the current unary transaction 
    if (AVD.logRdWrAccesses()) {
      lastTrans.logBufferReferenceArrayEndIndex = currentThread.logBufferReferenceArrayIndex;
      lastTrans.readWriteLogEndIndex = currentThread.wordArrayBufferIndex - 1;
    }
    // Clear the hash table before updating the last pointer
    if (AVD.recordAccessInfoInHashTable()) {
      AVDHashTable.clear(currentThread);
    }

    currentThread.setInTransaction();

    // Create and add the new node
    AVDNode temp = createNewNode(currentThread, site, /*isUnary = */ false, methodID);
    addNode(currentThread, temp);
    currentThread.bpCount++;

    // Store the start log buffer references, "currentThread.currentTransaction" is now different from "lastTrans"
    if (AVD.logRdWrAccesses()) {
      AVDNode current = currentThread.currentTransaction;
      current.logBufferStart = (WordArray) currentThread.currentWordArrayStart.toObject();
      current.logBufferReferenceArrayStartIndex = currentThread.logBufferReferenceArrayIndex;
      //if (VM.VerifyAssertions) { VM._assert(current.logBufferReferenceArrayStartIndex >=0 && current.logBufferReferenceArrayStartIndex < ReadWriteLog.INITIAL_LOG_BUFFER_REFERENCE_SIZE); }
      current.readWriteLogStartIndex = currentThread.wordArrayBufferIndex;
      //if (VM.VerifyAssertions) { VM._assert(current.readWriteLogStartIndex >= 0); }
    }
    
    //if (VM.VerifyAssertions && AVD.checkStartTransactionInstrumentation()) { VM._assert(checkStartTransactionInstrumentation()); }
    
    // Invoke cycle detection on the last completed transaction. We don't need to detect SCCs if harness end is 
    // already invoked, since we won't perform serializability analysis on them anyway.
    if (AVD.invokeCycleDetection() && !MemoryManager.isHarnessEndReached()) {
      AVDSCCComputation.findSCCHelper(lastTrans);
    }
  }

  @NoInline
  private static void printTransaction(int site, int methodID, boolean isStart) {
    RVMThread currentThread = RVMThread.getCurrentThread();
    if (!currentThread.isOctetThread()) { // We are not bothered about non-Octet threads
      return;
    }
    VM.sysWrite(isStart ? "startTransaction(): " : "endTransaction(): ");
    VM.sysWrite("Thread id: ", currentThread.octetThreadID);
    VM.sysWrite("TRANS ID: ", currentThread.currentTransaction.transactionID);
    VM.sysWrite(", In Trans: ", currentThread.inTransaction());
    VM.sysWrite(", METHOD ID:", methodID);
    VM.sysWrite(", SITE:", site);
    if (Octet.getClientAnalysis().needsSites()) {
      if (VM.VerifyAssertions) { VM._assert(site >= 0); } // Site should be valid for method start/end
      Site.lookupSite(site).sysWrite();
    } 
    if (!isStart) {
      VM.sysWriteln(", RD/WR SET SIZE: ", currentThread.currentTransaction.currentReadWriteLogIndex,
          ", CONFLICT SET SIZE:", currentThread.currentTransaction.crossThreadAccessLogIndex);
    } else {
      VM.sysWriteln();
    }
  }

  /**
   * Entry point called from Baseline and Opt compilers. This is only called for true
   * transactions, and not for unary transactions. This method should get inlined as it
   * is part of the fast path.
   * @param methodID the ID of the current method
   * */
  @Entrypoint
  public static void endTransaction(int siteID, int methodID) {
    RVMThread currentThread = RVMThread.getCurrentThread();
    if (!currentThread.isOctetThread()) { // We do not want to process non-Octet threads
      return;
    }
    
    // Perform sanity checks
    if (VM.VerifyAssertions) {
      VM._assert(AVD.methodsAsTransactions() || AVD.syncBlocksAsTransactions());
      VM._assert(currentThread.inTransaction());
    }
//    if (VM.VerifyAssertions && AVD.checkStartTransactionInstrumentation()) { VM._assert(checkStartTransactionInstrumentation()); }
    
    // Perform book keeping operations on the currently completed regular transaction
    AVDNode last = currentThread.currentTransaction;
    //if (VM.VerifyAssertions) { VM._assert(last.methodID == methodID); }
    // Store log buffer information
    if (AVD.logRdWrAccesses()) {
      last.logBufferReferenceArrayEndIndex = currentThread.logBufferReferenceArrayIndex;
      last.readWriteLogEndIndex = currentThread.wordArrayBufferIndex - 1;
//      if (VM.VerifyAssertions && last.logBufferReferenceArrayStartIndex == last.logBufferReferenceArrayEndIndex) {
//        VM._assert(last.currentReadWriteLogIndex == (last.readWriteLogEndIndex - last.readWriteLogStartIndex + 1));
//      }
    }
    
    currentThread.resetInTransaction();
    
    // Add a unary node/transaction immediately after an actual method ends. SCC computation will be invoked after 
    // unary transaction creation.
    createUnaryTransaction(currentThread, siteID);
    currentThread.bpCount++;
  }
  
  /** Create a node corresponding to a unary transaction in the given thread. */
  private static void createUnaryTransaction(RVMThread thread, int site) {
    if (VM.VerifyAssertions) { VM._assert(thread.isOctetThread()); }
    if (VM.VerifyAssertions) { VM._assert(!thread.inTransaction()); }

    AVDNode lastTrans = thread.currentTransaction;
    if (AVD.logRdWrAccesses()) {
      lastTrans.logBufferReferenceArrayEndIndex = thread.logBufferReferenceArrayIndex;
      lastTrans.readWriteLogEndIndex = thread.wordArrayBufferIndex - 1;
    }
    // Clear the hash table before updating last pointer, since the readWriteLogIndex of the
    // last node is used to determine whether clearing will take place or not
    if (AVD.recordAccessInfoInHashTable()) {
      AVDHashTable.clear(thread);
    }

    AVDNode temp = createNewNode(thread, site, /*isUnary = */ true);
    addNode(thread, temp);

    if (AVD.logRdWrAccesses()) {
      AVDNode current = thread.currentTransaction;
      current.logBufferStart = (WordArray) thread.currentWordArrayStart.toObject();
      current.logBufferReferenceArrayStartIndex = thread.logBufferReferenceArrayIndex;
//      if (VM.VerifyAssertions) { VM._assert(current.logBufferReferenceArrayStartIndex >=0 && current.logBufferReferenceArrayStartIndex < ReadWriteLog.INITIAL_LOG_BUFFER_REFERENCE_SIZE); }
      current.readWriteLogStartIndex = thread.wordArrayBufferIndex;
//      if (VM.VerifyAssertions) { VM._assert(current.readWriteLogStartIndex >= 0); }
    }

    // Invoke cycle detection on the last node before updating the reference, we invoke SCC only on
    // completed transactions, so the new unary node is added first. We don't need to detect SCCs if harness end is 
    // already invoked, since we won't perform serializability analysis on them anyway.
    if (AVD.invokeCycleDetection() && !MemoryManager.isHarnessEndReached()) {
      AVDSCCComputation.findSCCHelper(lastTrans);
    }
  }

  @Inline
  public static AVDNode createNewNode(RVMThread thread, int site, boolean isUnary) {
    return createNewNode(thread, site, isUnary, -1); // Passing -1 as method id
  }

  /** Create a new AVDNode representing a transaction at the end of "thread". */
  @UninterruptibleNoWarn
  public static AVDNode createNewNode(RVMThread thread, int site, boolean isUnary, int methodID) {
    AVDNode temp;
    int transID = ++thread.numberOfNodes;
    MemoryManager.startAllocatingInUninterruptibleCode();
    temp = (!AVD.useDebugNodes()) ? new AVDNode(thread, transID, site, isUnary, methodID)
                                  : new AVDDebugNode(thread, transID, site, isUnary, methodID);
    MemoryManager.stopAllocatingInUninterruptibleCode();
    return temp;
  }

  /** Add node to the end of the graph (sequential list) for "thread". */
  @Inline
  static void addNode(RVMThread thread, AVDNode node) {
    if (VM.VerifyAssertions) { VM._assert(node != null); }
    thread.currentTransaction.nextNode = node;
    thread.currentTransaction = node;
  }

  /** Create a cross-thread edge in the transactional dependence graph from {@code source --> dest} */
  void createEdgeHelper(AVDNode source, AVDNode dest, int conflictType) {
    if (!AVD.crossThreadEdgeCreationEnabled()) {
      return;
    }
    if (VM.VerifyAssertions) {
      VM._assert(source.outgoingEdgesList != null && source.octetThread.isOctetThread());
      VM._assert(dest.nextNode == null && dest.octetThread.isOctetThread()); // dest transaction should be running
      VM._assert(source.octetThread.octetThreadID != dest.octetThread.octetThreadID);
    }
    
    // Keep these conditions in sync with SCC computation check
    if ((AVD.bench.isDaCapoBenchmark() &&
        (source.octetThread.octetThreadID == AVD.DACAPO_DRIVER_THREAD_OCTET_ID || 
         dest.octetThread.octetThreadID == AVD.DACAPO_DRIVER_THREAD_OCTET_ID)) || 
         destEdgeAlreadyPresent(source, dest)) {
      return;
    }
    // This is especially problematic for avrora9
    if (AVD.bench.isDaCapoBenchmark() && 
        (source.octetThread.octetThreadID == AVD.FINALIZER_THREAD_OCTET_ID || 
         dest.octetThread.octetThreadID == AVD.FINALIZER_THREAD_OCTET_ID)) {
      return;
    }
    if (AVD.bench.getId() == BenchmarkInfo.TSP && 
        (source.octetThread.octetThreadID == AVD.TSP_DRIVER_THREAD_OCTET_ID || 
         dest.octetThread.octetThreadID == AVD.TSP_DRIVER_THREAD_OCTET_ID)) {
      return;
    }

    createEdge(source, dest, conflictType);
  }

  /**
   * Check whether there is already an edge of the form {@code source --> dest}
   * @return true if yes, else false
   */
  private boolean destEdgeAlreadyPresent(AVDNode source, AVDNode dest) {
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(ObjectReference.fromObject(source))); }
    Address oldHead;
    do {
      oldHead = ObjectReference.fromObject(source).toAddress().loadAddress(Entrypoints.avdOutgoingEdgesListField.getOffset());
    } while (oldHead.EQ(AVD.LOCK_ADDRESS));
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(oldHead.toObjectReference())); }
    AVDNodeList start = (AVDNodeList) oldHead.toObjectReference().toObject();
    if (VM.VerifyAssertions) { VM._assert(start != null); }
    
    while (start.node !=  null) {
      if (start.node == dest) {
        if (VM.VerifyAssertions) {
          VM._assert(start.node.transactionID == dest.transactionID && start.node.octetThread == dest.octetThread);
        }
        return true;
      }
      start = start.next;
    }
    return false;
  }

  /** Create edge in cross-thread graph between the two given AVDNodes.
   *  @param source the source/head node of the edge
   *  @param dest the dest/tail node of the edge
   *  @param conflictType denotes the caller
   * */
  void createEdge(AVDNode source, AVDNode dest, int conflictType) {
    // Updates to the dependent nodes information should be thread safe, since there could possibly
    // be more than one thread that is simultaneously reading/writing dependent node information
    Address oldHead;
    do {
      do {
        oldHead = ObjectReference.fromObject(source).toAddress().prepareAddress(Entrypoints.avdOutgoingEdgesListField.getOffset());
      } while (oldHead.EQ(AVD.LOCK_ADDRESS));
      if (ObjectReference.fromObject(source).toAddress().attempt(oldHead, AVD.LOCK_ADDRESS, Entrypoints.avdOutgoingEdgesListField.getOffset())) {
        break;
      }
    } while(true);
    // We how have exclusive access to the outgoing list
    AVDNodeList oldListHead = (AVDNodeList) oldHead.toObjectReference().toObject();
    if (VM.VerifyAssertions) { VM._assert(oldListHead != null); }

    AVDNodeList newListHead = createNewListHead(dest, conflictType);
    newListHead.next = oldListHead; // Adding dest to the front of the list

    boolean result = false;
    Address temp = ObjectReference.fromObject(source).toAddress().prepareAddress(Entrypoints.avdOutgoingEdgesListField.getOffset());
    if (VM.VerifyAssertions) { VM._assert(temp.EQ(AVD.LOCK_ADDRESS)); }
    // An MFENCE is required over here
    Magic.fence();
    Object fakeObject = temp.toObjectReference().toObject();
    if (AVD.useGenerationalBarriers()) {
      result = Barriers.objectTryCompareAndSwap(source, Entrypoints.avdOutgoingEdgesListField.getOffset(), fakeObject, newListHead);
    } else {
      result = ObjectReference.fromObject(source).toAddress().attempt(temp, ObjectReference.fromObject(newListHead).toAddress(), 
          Entrypoints.avdOutgoingEdgesListField.getOffset());
    }
    if (VM.VerifyAssertions) { VM._assert(result, "Accessing the list is expected to be mutually exclusive"); }
    if (AVD.recordAVDStats()) {
      AVDStats.numPhase1Edges.inc(1L);
    }
  }

  @UninterruptibleNoWarn
  public static AVDNodeList createNewListHead(AVDNode dest, int conflictType) {
    if (VM.VerifyAssertions) { VM._assert(dest != null); }
    MemoryManager.startAllocatingInUninterruptibleCode();
    AVDNodeList temp = new AVDNodeList(dest, conflictType);
    MemoryManager.stopAllocatingInUninterruptibleCode();
    return temp;
  }

  /**
   * Here we decrease transaction depth during stack unwinding. The caller method takes
   * care of the fact to avoid processing exceptions ***on behalf of VM*** and for native methods
   */
  public static void handleExceptionDuringStackUnwinding(RVMMethod method) {
    RVMThread currentThread = RVMThread.getCurrentThread();
    
    // AVD: LATER: This is the config we are bothered with for uncaught exceptions for now.
    // What do we do for exceptions from within sync blocks?
    if (!currentThread.isOctetThread() || !AVD.methodsAsTransactions()) {
      return;
    }
    
    // Sanity check
    if (VM.VerifyAssertions) { VM._assert(currentThread.inTransaction()); }
    // We are assuming exceptions are from regular transactions
    if (VM.VerifyAssertions) { VM._assert(!currentThread.currentTransaction.isUnaryTrans); }
    
    currentThread.resetInTransaction();
    // Add a unary node/transaction immediately after an actual method ends
    createUnaryTransaction(currentThread, currentThread.currentTransaction.siteID);
    currentThread.bpCount++;
  }

  /**
   * Decide whether to execute hooks in the slow path, by default we believe all client analysis will avoid invoking
   * hooks/processing when the slow path is entered due to library calls made on behalf of the VM
   */
  @Override
  public boolean shouldExecuteSlowPathHooks(boolean notInRespondingContext) {
    if (!Octet.getConfig().executeAVDSlowPathHooks() || !isVMFullyBooted /* Slow path hooks are called before VM is fully booted*/ ) {
      return false;
    }
    // Since AVD tracks locks, non-Octet threads can come into the slow path. Slow path hooks are not executed for 
    // locks from non-Octet threads.
    return RVMThread.getCurrentThread().isOctetThread();
  }
  
  /* **********************************************************
   *  Debugging methods
   * **********************************************************/
  
  @NoInline
  @UninterruptibleNoWarn
  public static boolean checkStartTransactionInstrumentation() {
    //VM.sysWriteln("*******************STARTING checkStartTransactionInstrumentation*********************");
    Address fp = Magic.getFramePointer();
    fp = Magic.getCallerFramePointer(fp);
    int depth = 0;
    while (Magic.getCallerFramePointer(fp).NE(STACKFRAME_SENTINEL_FP)) {
      int compiledMethodId = Magic.getCompiledMethodID(fp);
      if (compiledMethodId != INVISIBLE_METHOD_ID) {
        CompiledMethod compiledMethod = CompiledMethods.getCompiledMethod(compiledMethodId);
        RVMMethod method = compiledMethod.getMethod();
        if (!method.isNative() && Octet.shouldInstrumentMethod(method)) {
          if (Context.isTRANSContext(method.getStaticContext()) && Context.isNONTRANSContext(method.getResolvedContext())) {
            depth++;
          }
        }
      }
      fp = Magic.getCallerFramePointer(fp);
    }
    if (depth != 1) {
      RVMThread currentThread = RVMThread.getCurrentThread();
      VM.sysWriteln("Current thread:", currentThread.octetThreadID, "Trans:", currentThread.currentTransaction.transactionID);
      VM.sysWriteln("depth:", depth);
      RVMThread.dumpStack();
      VM.sysFail("Mismatch in transaction depth");
    }
    return true;
  }
  
  @Entrypoint
  public static void checkMethodContextAtProlog() {
    if (VM.VerifyAssertions) { VM._assert(AVD.checkMethodContextAtProlog()); }
    RVMThread currentThread = RVMThread.getCurrentThread();
    if (VM.VerifyAssertions) { VM._assert(currentThread.isOctetThread()); }
    Address fp = Magic.getFramePointer();
    int compiledMethodId = Magic.getCompiledMethodID(Magic.getCallerFramePointer(fp));
    if (compiledMethodId != INVISIBLE_METHOD_ID) {
      CompiledMethod compiledMethod = CompiledMethods.getCompiledMethod(compiledMethodId);
      RVMMethod method = compiledMethod.getMethod();
      if (VM.VerifyAssertions) { VM._assert(Context.isApplicationPrefix(method.getDeclaringClass().getTypeRef())); }
      if (VM.VerifyAssertions) {
        if (currentThread.inTransaction()) {
          if (method.getStaticContext() != Context.TRANS_CONTEXT) {
            VM.sysWriteln("Current Octet thread id:", currentThread.octetThreadID);
            VM.sysWriteln("Method name:", method.getName());
            VM.sysWriteln("Class name:", method.getDeclaringClass().getDescriptor());
            VM.sysWriteln("Static context:", method.getStaticContext());
            VM.sysWriteln("Resolved context:", method.getResolvedContext());
            VM.sysFail("Static context of called method is not TRANS");
          }
        } else {
          if (method.getStaticContext() != Context.NONTRANS_CONTEXT) {
            VM.sysWriteln("Current Octet thread id:", currentThread.octetThreadID);
            VM.sysWriteln("Method name:", method.getName());
            VM.sysWriteln("Class name:", method.getDeclaringClass().getDescriptor());
            VM.sysWriteln("Static context:", method.getStaticContext());
            VM.sysWriteln("Resolved context:", method.getResolvedContext());
            VM.sysFail("Static context of called method is not NONTRANS");
          }
        }
      }
    }
  }

}
