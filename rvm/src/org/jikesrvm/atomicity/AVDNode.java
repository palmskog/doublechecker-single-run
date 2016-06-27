package org.jikesrvm.atomicity;

import org.jikesrvm.Constants;
import org.jikesrvm.SizeConstants;
import org.jikesrvm.VM;
import org.jikesrvm.atomicity.AVD.COLOR;
import org.jikesrvm.octet.Site;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.WordArray;

/** This class represents a transaction. */ 
@Uninterruptible
public class AVDNode implements Constants {
  
  public RVMThread octetThread; // Thread reference
  int methodID; // Unary transactions and dummy start/end have method id = -1                                   
  public int transactionID; // A unique id for a transaction/node. Root node has id 0.  
  AVDNode nextNode; // Pointer to the next node in the list. 

  @Entrypoint
  AVDNodeList outgoingEdgesList = AVDNodeList.initialValue;
  
  // Each entry in the read/write set is of two words.
  WordArray logBufferStart;
  // A pair (log buffer array index, word array index) is required for partitioning, start and end of transactions. 
  int logBufferReferenceArrayStartIndex;
  int readWriteLogStartIndex;
  int logBufferReferenceArrayEndIndex;
  int readWriteLogEndIndex;
  
  /** Track the number of writes to the log buffer */
  int currentReadWriteLogIndex;
  
  /** Track cross-thread info */
  CrossThreadAccess[] crossThreadAccessLog;
  int crossThreadAccessLogIndex; 
  int currentCrossThreadAccessLogSize;
  
  int siteID; // Stores the site id corresponding to the transaction entry
  public boolean isUnaryTrans; // true: unary, false = regular (corresponds to an actual transaction (method))
  
  // Attributes introduced for SCC computation
  int index;
  int lowlink;
  
  // Per-node variables required for Phase 2 serializability analysis
  int indexProcessedInRdWrLog;
  int indexProcessedInCrossThreadLog;
  int minIncomingEdge;
  int minOutgoingEdge;
  boolean hasSerialPred;
  COLOR dfsColor; // Stores the status of whether the node has already been visited during DFS in Phase 2.
  
  /** 
   * Called to create the root (dummy) node only. This has a trans id 0.
   * Note that it is possible for {@code t} to be different from {@code RVMThread.getCurrentThread()}, if this 
   * ctor is called from RVMThread ctor
   */
  public AVDNode(RVMThread t, boolean isUnary) {
    this(t, AVD.START_TRANSACTION_ID, // Value indicates the dummy/header node
         /*site = */ AVD.UNINITIALIZED_SITE, isUnary, /*method = */ -1);
  }
  
  /** This ctor creates a node that represents a transaction. 
   *  Note that it is possible for t to be different from RVMThread.getCurrentThread(), if this
   *  ctor is called from RVMThread ctor
   *  
   *  GC should be disabled when this ctor is executed.
   * */
  public AVDNode(RVMThread thread, int transID, int site, boolean isUnary, int method) {
    if (VM.VerifyAssertions) { VM._assert(thread.isOctetThread()); }
    octetThread = thread;
    methodID = method;
    transactionID = transID;   
    nextNode = null;
    siteID = site;
    isUnaryTrans = isUnary;
    // This assertion does not work for the last dummy unary transaction created during thread termination for some 
    // benchmarks such as raytracer, since the transaction depth does not go down to zero
    if (VM.VerifyAssertions && isUnaryTrans && site != AVD.UNINITIALIZED_SITE) { VM._assert(!thread.inTransaction()); }
    if (VM.VerifyAssertions && !isUnaryTrans) { VM._assert(thread.inTransaction()); }
    if (VM.VerifyAssertions && !isUnaryTrans) { VM._assert(site >= 0); } // Should be a valid site
    if (VM.VerifyAssertions && siteID < 0) { VM._assert(isUnaryTrans); }
    
    // Each slot is of two words
    logBufferStart = null;
    
    logBufferReferenceArrayStartIndex = -1;
    readWriteLogStartIndex = -1;
    logBufferReferenceArrayEndIndex = -1;
    readWriteLogEndIndex = -1;
    currentReadWriteLogIndex = 0;
    
    crossThreadAccessLogIndex = 0;
    if (AVD.recordConflictingAccess() && !isUnary) { // Unary transactions don't need conflict access info
      currentCrossThreadAccessLogSize = AVD.MAX_CROSS_THREAD_ACCESS_SIZE;
      crossThreadAccessLog = new CrossThreadAccess[currentCrossThreadAccessLogSize];
    } else {
      currentCrossThreadAccessLogSize = 0;
      crossThreadAccessLog = null;
    }
    
    index = -1;
    lowlink = -1;
    
    indexProcessedInRdWrLog = 0;
    indexProcessedInCrossThreadLog = 0;
    minIncomingEdge = Integer.MAX_VALUE;
    minOutgoingEdge = Integer.MAX_VALUE;
    hasSerialPred = false;
    dfsColor = COLOR.WHITE;
    
    if (AVD.recordAVDStats()) {
      AVDStats.numNodes.inc(thread, 1L);
      if (isUnary) {
        AVDStats.numUnaryTransactions.inc(thread, 1L);
      } else {
        AVDStats.numRegularTransactions.inc(thread, 1L);
      }
    }
  }
  
  final void printTransaction() {
    VM.sysWrite("Thread:", octetThread.octetThreadID, "Trans:", transactionID);
    VM.sysWrite(" Is Unary:", isUnaryTrans);
    VM.sysWrite(" Site id:", siteID);
    VM.sysWrite(" Size of read/write log:", currentReadWriteLogIndex);
    VM.sysWrite(" Size of conflict log:", crossThreadAccessLogIndex, " ");
    if (siteID >= 0) {
      Site.lookupSite(siteID).sysWriteln();
    } else {
      VM.sysWriteln();
    }
  }

  /** Get log buffer address that contains index */
  @Inline
  final Address getLogBufferSlotAddress(int in) {
    if (VM.VerifyAssertions) { VM._assert(in >= 0 && in < currentReadWriteLogIndex); }
    Address addr;
    if (/*logBufferReferenceArrayStartIndex == logBufferReferenceArrayEndIndex // Means the whole log is inside one, common case
        || (logBufferReferenceArrayEndIndex > logBufferReferenceArrayStartIndex && */ 
        in < ReadWriteLog.LOG_BUFFER_SLOTS - readWriteLogStartIndex) {
      addr = ObjectReference.fromObject(logBufferStart).toAddress().plus((readWriteLogStartIndex + in) << ReadWriteLog.LOG_CELL_SIZE_IN_BYTES);
    } else { // Log buffer does not fit in one WordArray
      if (VM.VerifyAssertions) { VM._assert(logBufferReferenceArrayEndIndex > logBufferReferenceArrayStartIndex); }
      if (VM.VerifyAssertions) { VM._assert(ReadWriteLog.LOG_BUFFER_SLOTS - readWriteLogStartIndex <= currentReadWriteLogIndex); }
      int i = logBufferReferenceArrayStartIndex + 1;
      in -= (ReadWriteLog.LOG_BUFFER_SLOTS - readWriteLogStartIndex);
      while (in - ReadWriteLog.LOG_BUFFER_SLOTS >= 0) {
        i++;
        in = in - ReadWriteLog.LOG_BUFFER_SLOTS;
      }
      Address logAddr = ObjectReference.fromObject(octetThread.logBufferReferenceArray).toAddress();
      ObjectReference ref = logAddr.plus(i << SizeConstants.LOG_BYTES_IN_ADDRESS).loadObjectReference();
      if (VM.VerifyAssertions) { VM._assert(!ref.isNull()); }
      addr = ref.toAddress().plus(in << ReadWriteLog.LOG_CELL_SIZE_IN_BYTES);
//      if (VM.VerifyAssertions) { VM._assert(!addr.isZero()); }
//      if (addr.loadWord().isZero()) {
//        VM.sysWriteln("Thread:", octetThread.octetThreadID, "Trans:", transactionID);
//        VM.sysWriteln("logbufferstartreferenceindex:", logBufferReferenceArrayStartIndex);
//        VM.sysWriteln("readwritelogstartindex", readWriteLogStartIndex);
//        VM.sysWriteln("logbufferendreferenceindex:", logBufferReferenceArrayEndIndex);
//        VM.sysWriteln("readwritelogendindex:", readWriteLogEndIndex);
//        VM.sysWriteln("currentreadwritelogindex:", currentReadWriteLogIndex);
//        VM.sysWriteln("Current input index:", in);
//        VM.sysWriteln("Value of i:", i);
//        VM.sysWriteln("Address of selected log buffer:", ref.toAddress());
//        VM.sysWriteln("Address of selected slot:", addr);
//        
////        Address tmp = ref.toAddress();
////        for (int t = 0; t < ReadWriteLog.LOG_BUFFER_SLOTS; t++) {
////          VM.sysWrite("Index:", t);
////          VM.sysWriteln("Object reference stored:", tmp.loadWord());
////          tmp = tmp.plus(ReadWriteLog.CELL_SIZE_IN_BYTES);
////        }
//        
//      }
//      if (VM.VerifyAssertions) { VM._assert(!addr.loadWord().isZero()); }
    }
    if (VM.VerifyAssertions) { VM._assert(!addr.isZero()); }
//    if (VM.VerifyAssertions) { VM._assert(!addr.loadWord().isZero()); }
    return addr;
  }
  
  /** Print the contents of the rd-wr set for the given transaction/node. */
  public final void printRdWrLog() {
    VM.sysWriteln("********************************************");
    VM.sysWrite("PRINTING READ/WRITE SET INFO FOR THREAD", octetThread.octetThreadID);
    VM.sysWrite(", TRANS:", transactionID);
    VM.sysWriteln(", Read/write LOG INDEX:", currentReadWriteLogIndex);
    int i = 0;
    while (i < currentReadWriteLogIndex) {
      VM.sysWrite("INDEX: ", i);
      int type = ReadWriteLog.getAccessType(this, i);
      VM.sysWrite(", Access Type:", type);
      VM.sysWrite(", OBJECT REFERENCE:", ReadWriteLog.getObjectReference(this, i));
      VM.sysWrite(", GC CYCLE NUMBER:", ReadWriteLog.getGCCycleNumber(this, i));
      VM.sysWrite(", FIELD OFFSET:", ReadWriteLog.getFieldOffset(this, i));
      VM.sysWrite(", Field ADDRESS: ", ReadWriteLog.getObjectReference(this, i).toAddress().plus(ReadWriteLog.getFieldOffset(this, i)));
      if (AVD.includeSiteIDinReadWriteSet()) {
        int siteID = ReadWriteLog.getSiteID(this, i);
        VM.sysWrite(", SITE ID:", siteID);
        if (siteID >= 0) {
          Site site = Site.lookupSite(siteID);
          if (VM.VerifyAssertions) { VM._assert(site != null); }
          site.sysWriteln();
        } else if (siteID == -1) {
          VM.sysWriteln(" LOCK ACQUIRE/RELEASE STATEMENT");
        } else if (siteID == -2) {
          VM.sysWriteln(" THREAD SYNCH PRIMITIVE");
        } else {
          if (VM.VerifyAssertions) { VM._assert(false); }
        }
      } else {
        VM.sysWriteln();
      }
      i++;
    }
  }
  
  /** Print conflicting access information for the given node */
  public final void printConflictSet() {
    if (VM.VerifyAssertions && isUnaryTrans) { VM._assert(crossThreadAccessLog == null && crossThreadAccessLogIndex == 0); }
    
    VM.sysWriteln();
    VM.sysWrite("PRINTING CONFLICT ACCESS SET INFO FOR THREAD", octetThread.octetThreadID);
    VM.sysWrite(", TRANS:", transactionID);
    VM.sysWriteln(", CONFLICT ACCESS INDEX:", crossThreadAccessLogIndex);
    int i = 0;
    while (i < crossThreadAccessLogIndex) {
      VM.sysWrite("INDEX: ", i);
      VM.sysWrite(", Remote Thread:", crossThreadAccessLog[i].remoteThread.octetThreadID, 
                  ", Remote transaction: " , crossThreadAccessLog[i].remoteTransaction.transactionID);
      VM.sysWriteln(", Remote Index:", crossThreadAccessLog[i].remoteIndex,  
                    ", Own Index:", crossThreadAccessLog[i].ownIndex);
      i++;
    }
  }
  
}
