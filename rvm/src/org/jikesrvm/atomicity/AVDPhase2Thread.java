package org.jikesrvm.atomicity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Stack;

import org.jikesrvm.SizeConstants;
import org.jikesrvm.VM;
import org.jikesrvm.atomicity.AVD.COLOR;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.octet.Site;
import org.jikesrvm.scheduler.Monitor;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.SystemThread;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.NonMoving;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * This class (run in a separate thread) implements the precise cycle detection in DoubleChecker. Basically, this 
 * thread takes as input a set/list of ICD transactions, and performs Velodrome's dynamic serializability analysis on 
 * the set of nodes  * to compute precise atomicity violation.
 * See <a href="http://dl.acm.org/citation.cfm?id=1375618">Velodrome</a> 
 */
// Phase 2 thread is both a system thread and a daemon thread, it does not seem to help if it is made a 
// non-daemon thread since the Phase 2 thread cannot make the RVM termination decision
@NonMoving
public final class AVDPhase2Thread extends SystemThread {

  private static final int cycleVerbosity = 1;
  
  private static String nameOfPhase2Thread = "AVDPhase2Thread";
  /** Monitor to pass information about cycles detected */
  private static Monitor phase2Lock;
  // Make sure that the Phase 2 thread is able to complete all required iterations before the RVM exits
  volatile public static boolean isRunning = false;
  /** Number of pending requests (cycles) from Phase 1 */
  volatile public static int numPendingRequests = 0;
  /** Current Phase 2 iteration */
  public volatile static int currentIterationCount = 0;
  
  private HashSet<AVDNode> nodesInPhase1Cycle = new HashSet<AVDNode>();
  private static int numNodesInPhase1Cycle;
  
  /** Data structure to store last read/write information */
  private HashMap<ObjectWrapper, HashMap<Integer, LastAccessInfo>> lastAccessLog
            = new HashMap<ObjectWrapper, HashMap<Integer, LastAccessInfo>>();
  
  /** This a precise transactional happens-before graph, represented using adjacency list notation */
  private HashMap<AVDNode, HashSet<BlameAssignmentEdge>> preciseEdges = new HashMap<AVDNode, HashSet<BlameAssignmentEdge>>();
  
  private HashMap<RVMThread, ArrayList<AVDNode>> serialDependence = new HashMap<RVMThread, ArrayList<AVDNode>>();
  
  /** Used to order edges when adding to the precise thbg, used for blame assignment. This is executed in a single-
   *  threaded context, so there is no need to synchronize. */
  int edgeCounter = 0;
  
  public AVDPhase2Thread() {
    super(nameOfPhase2Thread);
  }
  
  /** Ensure that this is called. */
  public static void boot() {
    phase2Lock = new Monitor();
    AVDPhase2Thread thread = new AVDPhase2Thread();
    thread.start();
  }
  
  /** Schedule Phase 2 thread to run. Called by Phase 1 methods. */
  @Uninterruptible
  public static void schedule() {
    if (VM.VerifyAssertions) { VM._assert(!MemoryManager.isHarnessEndReached()); }
    phase2Lock.lockNoHandshake();
    numPendingRequests++;
    phase2Lock.broadcast();
    phase2Lock.unlock();
  }
  
  @Override
  public void run() {
    int pending = 0;
    try {
      while (true) {
        phase2Lock.lockNoHandshake();        
        if (numPendingRequests == 0) {
          isRunning = false;
          phase2Lock.waitWithHandshake(); // Wait for a request
        }
        // Phase 2 thread owns the monitor
        pending = numPendingRequests;
        numPendingRequests = 0;
        if (VM.VerifyAssertions) { VM._assert(pending > 0); } // There must be a valid/active request
        isRunning = true;
        phase2Lock.unlock();
        
        // Now iterate over the pending requests and process them
        while (pending > 0) { 
          currentIterationCount++;
          if (AVD.recordAVDStats()) {
            AVDStats.numPhase2Iterations.inc(1L);
          }
          performVelodromeSerializabilityHelper(); // Perform Velodrome serializability analysis
          prepareForNextIteration();
          pending--;
        }
      }
    } catch(Exception e) {
      e.printStackTrace();
    }
  }
  
  /** Prepare for the next iteration. */
  private void prepareForNextIteration() {
    resetNodeDFSColor();
    resetNodeLocalInfo();
    nodesInPhase1Cycle.clear();
    lastAccessLog.clear();
    serialDependence.clear();
    preciseEdges.clear();
    edgeCounter = 0;
    numNodesInPhase1Cycle = 0;
  }
  
  /** This method resets the node-local info stored for serializability analysis. */
  private void resetNodeLocalInfo() {
    for (AVDNode node : nodesInPhase1Cycle) {
      node.indexProcessedInCrossThreadLog = 0;
      node.indexProcessedInRdWrLog = 0;
      node.minIncomingEdge = -1;
      node.dfsColor = COLOR.WHITE;
      node.hasSerialPred = false;
    }
  }

  /** This method performs the Velodrome serializability analysis. */
  private void performVelodromeSerializabilityHelper() {
    constructListOfNodes(); // Populate the hash set with the nodes information
    computeSerialDependences();
    if (VM.VerifyAssertions) { VM._assert(debugSerialDependenceInfo()); }
    performSerializabilityAnalysisNew();
  }
  
  /** Prepare a hash set with transaction identifier and a reference to the node. Should help in quick lookup. */
  private void constructListOfNodes() {
    AVD.phase1CyclesLock.lock();
    AVDNodeList front = AVD.phase1Cycles.remove();
    AVD.phase1CyclesLock.unlock();
    AVDNodeList temp = front;
    if (VM.VerifyAssertions) { VM._assert(temp != null); }
    while (temp != null) {
      AVDNode tmp = temp.node;
      if (tmp == null) { // Sentinel node
        if (VM.VerifyAssertions) { VM._assert(temp.next == null); }
        break;
      }
      // Each transaction that is part of a cycle should have been completed so as to avoid  
      // races while reading the read-write set
      if (VM.VerifyAssertions) { VM._assert(tmp.nextNode != null); }
      nodesInPhase1Cycle.add(tmp);
      temp = temp.next;
      numNodesInPhase1Cycle++;
    }
    if (VM.VerifyAssertions) { VM._assert(numNodesInPhase1Cycle == nodesInPhase1Cycle.size()); }
  }
  
  /** Prepare a list where nodes in a thread i are sorted according to program order */
  @NoInline
  private void computeSerialDependences() {
    for (AVDNode node : nodesInPhase1Cycle) {
      ArrayList<AVDNode> list = serialDependence.get(node.octetThread);
      if (list == null) { // First node for current thread
        list = new ArrayList<AVDNode>();
        list.add(node);
        serialDependence.put(node.octetThread, list);
      } else {
        if (VM.VerifyAssertions) { VM._assert(!list.contains(node)); }
        list.add(node);
      }
    }
    int i = 0;
    for (Entry<RVMThread, ArrayList<AVDNode>> tmp : serialDependence.entrySet()) {
      ArrayList<AVDNode> list = tmp.getValue();
      Collections.sort(list, new AVDNodeComparator());
      for (AVDNode node : list) {
        if (VM.VerifyAssertions) { VM._assert(!node.hasSerialPred); }
        node.hasSerialPred = true;
        i++;
      }
      list.get(0).hasSerialPred = false; // Mark the first node to be processed for each thread
    }
    if (VM.VerifyAssertions) { VM._assert(i == numNodesInPhase1Cycle); }
  }
  
  @NoInline
  private boolean debugSerialDependenceInfo() {
    for (AVDNode node : nodesInPhase1Cycle) {
      if (!node.hasSerialPred) {
        if (VM.VerifyAssertions) {
          for (AVDNode node1 : nodesInPhase1Cycle) {
            if (node1 != node && node.octetThread == node1.octetThread) {
              VM._assert(node1.transactionID != node.transactionID);
              if (node1.transactionID < node.transactionID) {
                if (node1.hasSerialPred) {
                  VM.sysWriteln("Has serial predecessor:", extractNodeInfo(node1));
                  VM.sysWriteln(" Does not have serial predecessor:", extractNodeInfo(node));
                }
                VM._assert(false);
              } else {
                VM._assert(node1.hasSerialPred);
              }
            }
          }
        }
      }
    }
    return true;
  }
  
  @NoInline
  private String extractNodeInfo(AVDNode node) {
    String temp;
    temp = "Thread" + node.octetThread.octetThreadID + "Trans" + node.transactionID;
    if (Octet.getConfig().needsSites()) {
      temp += " (" + Site.lookupSite(node.siteID) + ")";
    }
    return temp;
  }
  
  private void performSerializabilityAnalysisNew() {
    boolean change = false; // To break out of the following while loop
    boolean cycleDetected = false; 
    while (true) {
      change = false;
      for (Entry<RVMThread, ArrayList<AVDNode>> tmp : serialDependence.entrySet()) { // Iterate over the transactions
        if (tmp.getValue().size() == 0) { // Done processing all nodes for this RVMThread
          continue;
        }
        AVDNode node = tmp.getValue().get(0); // Always get the node at the beginning of each list
        if (VM.VerifyAssertions) { VM._assert(!node.hasSerialPred); }
        
        // Unary transactions should not have conflict access entries, but they could have non-trivial read/write logs
        if (VM.VerifyAssertions && node.isUnaryTrans) { VM._assert(node.crossThreadAccessLogIndex == 0); }
        int logIndexProcessed = node.indexProcessedInRdWrLog;
        int conflictIndexProcessed = node.indexProcessedInCrossThreadLog;
        
        if (logIndexProcessed == node.currentReadWriteLogIndex) { // Implies that the whole read/write set has been processed
          if (conflictIndexProcessed == node.crossThreadAccessLogIndex) {
            tmp.getValue().remove(0); // Remove current node
            if (tmp.getValue().size() > 0) {
              // Conceptually this means that the current transaction has completed execution, and the 
              // next transaction is about to start. So we here we need to add a precise sequential 
              // edge (ordered) to the Phase 2 graph, i.e., node --> next
              AVDNode next = tmp.getValue().get(0);
              BlameAssignmentEdge wrapper = new BlameAssignmentEdge(next, ++edgeCounter);
              HashSet<BlameAssignmentEdge> set = preciseEdges.get(node);
              if (set != null) {
                set.add(wrapper);
              } else {
                set = new HashSet<BlameAssignmentEdge>();
                set.add(wrapper);
                preciseEdges.put(node, set);
              }
              next.hasSerialPred = false;
            }              
          } else {
            if (VM.VerifyAssertions) { VM._assert(conflictIndexProcessed < node.crossThreadAccessLogIndex); }
            int i = conflictIndexProcessed;
            if (VM.VerifyAssertions) { // Remote index stored should be lesser than the total log index
              VM._assert(node.crossThreadAccessLog[i].remoteIndex <= node.crossThreadAccessLog[i].remoteTransaction.currentReadWriteLogIndex);
            }
            while (i < node.crossThreadAccessLogIndex) {
              if (VM.VerifyAssertions) { VM._assert(node.crossThreadAccessLog[i].ownIndex <= node.currentReadWriteLogIndex); } 
              AVDNode remoteNode = node.crossThreadAccessLog[i].remoteTransaction;
              if (nodesInPhase1Cycle.contains(remoteNode)) {
                // Need to wait for the remote node to complete processing the access before proceeding
                if (remoteNode.indexProcessedInRdWrLog >= node.crossThreadAccessLog[i].remoteIndex && 
                    remoteNode.hasSerialPred == false) {
                  i++;
                } else {
                  break;
                }
              } else { // Remote transaction is not part of the current cycle
                i++;
              }
            }
            node.indexProcessedInCrossThreadLog = i;
          }
          
        } else {
          change = true; // Needs to iterate further
          
          // We need to continue processing the read/wr set for this node
          // First determine the desired index in the conflicting set 
          while (conflictIndexProcessed < node.crossThreadAccessLogIndex) {
            int i = conflictIndexProcessed; 
            int ownIndex = node.crossThreadAccessLog[i].ownIndex;
            if (VM.VerifyAssertions) { // Remote index stored should be lesser than the total log index
              VM._assert(node.crossThreadAccessLog[i].remoteIndex <= node.crossThreadAccessLog[i].remoteTransaction.currentReadWriteLogIndex);
            }
            boolean flag = true; // To break out of the while loop after conflict index has been determined
            while (i < node.crossThreadAccessLogIndex && node.crossThreadAccessLog[i].ownIndex == ownIndex) {
              AVDNode remoteNode = node.crossThreadAccessLog[i].remoteTransaction;
              if (nodesInPhase1Cycle.contains(remoteNode)) { // Remote transaction is part of the cycle
                // Need to wait for the remote node to complete processing the access before proceeding
                if (remoteNode.indexProcessedInRdWrLog >= node.crossThreadAccessLog[i].remoteIndex &&
                    remoteNode.hasSerialPred == false) { // AVD: TODO: Check this condition
                  // Desired access in remote node has already been processed, nice, move to the next 
                  // index in the conflict set
                  i++;
                } else {
                  flag = false;
                  break;
                }
              } else { // Remote transaction is not part of the current cycle
                i++;
              }
            }
            if (flag) {
              conflictIndexProcessed = i;
            } else {
              break;
            }
          }
          // Process read/write log
          if (VM.VerifyAssertions) { VM._assert(!AVD.includeSiteIDinReadWriteSet()); }
          if (conflictIndexProcessed == node.crossThreadAccessLogIndex) { // All conflicts processed
            while (logIndexProcessed < node.currentReadWriteLogIndex) {
              cycleDetected = recordRdWrAccessNew(node, logIndexProcessed);
              logIndexProcessed++;
              // It should not affect correctness if we stop the current iteration
              if (cycleDetected) {
                return;
              }
            }
          } else {
            while (logIndexProcessed < node.crossThreadAccessLog[conflictIndexProcessed].ownIndex) {
              cycleDetected = recordRdWrAccessNew(node, logIndexProcessed);
              logIndexProcessed++;
              // It should not affect correctness if we stop the current iteration
              if (cycleDetected) {
                return;
              }
            }
          }
          node.indexProcessedInCrossThreadLog = conflictIndexProcessed;
          node.indexProcessedInRdWrLog = logIndexProcessed;
        }
      }
      
      if (!change) {
        break;
      }
      
    }
  }
  
  /**
   * Record rd/wr accesses in Phase 2 data structures for serializability analysis. This will help
   * in looking up last (possibly many) rd/last write accesses. 
   * @param node
   * @param j Read-write set index which is being processed
   */
  private boolean recordRdWrAccessNew(AVDNode node, int j) {
    if (VM.VerifyAssertions) { VM._assert(j >= 0 && j < node.currentReadWriteLogIndex); }
    boolean cycleDetected = false;
    Address addr = node.getLogBufferSlotAddress(j);
    // AVD: LATER: Using an ObjectReference is causing failures during GC scanning of thread roots, in scanThread().
    Word objRef = addr.loadWord();
    if (VM.VerifyAssertions) { VM._assert(!objRef.isZero()); }
    Word secondWord = addr.loadWord(Offset.fromIntZeroExtend(SizeConstants.BYTES_IN_WORD));
    int off = ReadWriteLog.getFieldOffset(secondWord); 
    Integer offsetWrap = new Integer(off); // Wrap field offset as an Integer
    int val = ReadWriteLog.getAccessType(secondWord);
    if (VM.VerifyAssertions) { VM._assert(val >= 0 && val <= 3); }
    boolean isRead = (val >= 2) ? false : true; 
    int cycle = ReadWriteLog.getGCCycleNumber(secondWord);
    ObjectWrapper objWrap = new ObjectWrapper(objRef.toAddress().toInt(), cycle);
    
    HashMap<Integer, LastAccessInfo> offsetMap = lastAccessLog.get(objWrap); // Get obj information
    if (offsetMap != null) {
      LastAccessInfo li = offsetMap.get(offsetWrap); // Get last access info for (obj+offset)
      if (li != null) { // These should be the same (obj+offset) field 
        if (isRead) { // Read access
          
          // First, create edge from last write (if any) to current read
          AVDNode lastWrite = li.getLastWrite();
          if (lastWrite != null) {
            if (lastWrite.octetThread != node.octetThread) {
              // There is a last write to (obj+off), so need to create an edge for Wr->Rd true dependence
              // between lastWrite->node
              cycleDetected = addPreciseEdgeInfo(lastWrite, node); // Insert cycle info
              if (cycleDetected) {
                return cycleDetected;
              }
            }
          } else { // There was no last write to (obj+off), so no need to create an edge
          }
          
          // Now add read info
          // Add current read info for (obj+off), there should be only one entry for each thread
          li.appendLastToReadSet(node);
          
        } else { // Write access
          
          // Check all last reads to the field, and create Rd->Wr edges
          HashMap<RVMThread, AVDNode> lastReadSet = li.getLastReadSet();
          // Create edges between all nodes in lastReadSet to node
          Iterator<Entry<RVMThread, AVDNode>> it = lastReadSet.entrySet().iterator();
          if (VM.VerifyAssertions) { VM._assert(it != null); }
          
          while (it.hasNext()) {
            Map.Entry<RVMThread, AVDNode> pair = (Map.Entry<RVMThread, AVDNode>) it.next();
            RVMThread t = pair.getKey();
            AVDNode n = pair.getValue();
            if (VM.VerifyAssertions) { VM._assert(n != null && t == n.octetThread); }
            if (n.octetThread != node.octetThread) {
              if (val != 3) { // Don't add precise edges for lock releases
                cycleDetected = addPreciseEdgeInfo(n, node);
                if (cycleDetected) {
                  return cycleDetected;
                }
              }
            }
          }
          
          // Create an edge from the last write (if any) to the current one
          AVDNode lastWrite = li.getLastWrite();
          if (lastWrite != null) {
            if (lastWrite != node) {
              if (lastWrite.octetThread != node.octetThread) {
                // There is a last write to (obj+off), so need to create an edge for Wr->Wr output dependence
                // between lastWrite->node
                if (val != 3) { // Don't add edges for lock writes 
                  cycleDetected = addPreciseEdgeInfo(lastWrite, node);
                  if (cycleDetected) {
                    return cycleDetected;
                  }
                }
              }
            }
          } else { // There was no last write to (obj+off), so no need to create an edge
          }
          
          li.setLastWrite(node); // Now enter the new write information
          li.clearReadSetInfo(); // Clear all last read information to (obj+off), should no longer be required
          
        }
      } else { // No last access info for (obj+offset), construct last access info
        
        li = new LastAccessInfo();
        if (isRead) {
          li.appendLastToReadSet(node); 
        } else {
          li.setLastWrite(node);
        }
        offsetMap.put(offsetWrap, li);
        
      }
      
    } else { // No last access (both read/write) info corresponding to obj, construct last access info
      
      LastAccessInfo li = new LastAccessInfo();
      if (isRead) {
        li.appendLastToReadSet(node); // Record only read info, write is null
      } else {
        li.setLastWrite(node);
      }
      offsetMap = new HashMap<Integer, LastAccessInfo>();
      offsetMap.put(offsetWrap, li);
      lastAccessLog.put(objWrap, offsetMap);
      
    }
    return false;
  }

  /** Create a precise Phase 2 cross-thread edge from {@code source --> dest} */
  private boolean addPreciseEdgeInfo(AVDNode source, AVDNode dest) {
    if (VM.VerifyAssertions) { VM._assert(nodesInPhase1Cycle.contains(source) && nodesInPhase1Cycle.contains(dest)); }
    
    // Check whether there are already outgoing edges present from source, i.e., set is not null
    HashSet<BlameAssignmentEdge> set = preciseEdges.get(source);
    boolean isEdgeToDestAlreadyPresent = false;
    if (set != null) { // There are already outgoing edges from source
      // Check if edge source --> dest already exits, a hash set does not work over here since we are using new 
      // BlameAssignmentEdge objects
      Iterator<BlameAssignmentEdge> it = set.iterator();
      while (it.hasNext()) {
        AVDNode tgt = it.next().getDestNode();
        if (VM.VerifyAssertions) { VM._assert(tgt != null); }
        if (tgt == dest) { // Node already present
          isEdgeToDestAlreadyPresent = true;
          break;
        }
      }
      if (!isEdgeToDestAlreadyPresent) {
        BlameAssignmentEdge wrapper = new BlameAssignmentEdge(dest, ++edgeCounter);
        set.add(wrapper); // Add to the set
      }
    } else { // There are no outgoing edges from source
      set = new HashSet<BlameAssignmentEdge>();
      BlameAssignmentEdge wrapper = new BlameAssignmentEdge(dest, ++edgeCounter);
      set.add(wrapper);
      preciseEdges.put(source, set);
    }
    if (VM.VerifyAssertions) { VM._assert(preciseEdges.size() <= nodesInPhase1Cycle.size()); }
    if (!isEdgeToDestAlreadyPresent) { // An edge was added
      if (edgeCounter < dest.minIncomingEdge) {
        dest.minIncomingEdge = edgeCounter;
      }
      if (edgeCounter < source.minOutgoingEdge) {
        source.minOutgoingEdge = edgeCounter;
      }
      // Now check for cycles getting created in Phase 2 precise graph
      return computePhase2Cycles(source, dest, edgeCounter);
    }
    return false;
  }
  
  /** Compute Phase 2 cycles. This is similar to the blame assignment idea in Velodrome. 
   *  We want to check for possible cycles between {@code source} and {@code dest} after an 
   *  edge from {@code source --> dest} has been added. To do that, we start exploring from {@code dest}
   *  and check if we can reach {@code source}.
   *  */
  private final boolean computePhase2Cycles(AVDNode source, AVDNode dest, int currentEdgeCount) {
    if (dest.isUnaryTrans || dest.transactionID == AVD.START_TRANSACTION_ID) {
      return false;
    }
    if (!preciseEdges.containsKey(dest)) { 
      return false;
    }
    // There is atleast one outgoing edge from "dest"
    
    // Indicates a potentially blameable node, and make sure that the node has both outgoing and incoming edges
    if (dest.minOutgoingEdge < currentEdgeCount) {
      resetNodeDFSColor();
      if (dfsVisit(source, dest, dest, currentEdgeCount, /*firstCall = */ true)) {
        //if (VM.VerifyAssertions) { VM._assert(listOfNodesInPhase2Cycle.size() > 1); }
        if (cycleVerbosity > 0) {
          VM.sysWriteln("**************************************************");
          VM.sysWrite("ITERATION:" + currentIterationCount);
          VM.sysWrite(" SIZE OF PHASE 2 CYCLE:", nodesInPhase1Cycle.size());
          VM.sysWriteln(" BLAMED TRANSACTION:", extractNodeInfo(dest));
          VM.sysWriteln("**************************************************");
        }
        return true;
      }
    }
    return false;
  }

  /** (firstCall == true) ==> (current == dest) */
  private boolean dfsVisit(AVDNode source, AVDNode dest, AVDNode current, int currentEdgeCount, boolean firstCall) {
    current.dfsColor = COLOR.GRAY; // Mark gray
    HashSet<BlameAssignmentEdge> outgoingEdges = preciseEdges.get(current);
    if (outgoingEdges != null) {
      Iterator<BlameAssignmentEdge> it = outgoingEdges.iterator();
      while (it.hasNext()) {
        BlameAssignmentEdge be = it.next();
        AVDNode d = be.getDestNode();
        if (firstCall) {
          if (be.getEdgeNumber() < currentEdgeCount) { // Traverse along an older edge
            if (dest.dfsColor == COLOR.GRAY) { // Cycle detected
              return true;
            } else if (dest.dfsColor == COLOR.WHITE) { // Not already visited
              return dfsVisit(source, dest, d, be.getEdgeNumber(), false);
            }            
          } else { // No need to traverse along a more recent edge
          }
        } else {
          if (be.getEdgeNumber() > currentEdgeCount) { // Traverse along an increasing cycle
            if (dest.dfsColor == COLOR.GRAY) { // Cycle detected
              return true;
            } else if (dest.dfsColor == COLOR.WHITE) { // Not already visited
              return dfsVisit(source, dest, d, be.getEdgeNumber(), false);
            }       
          } else { // No need to traverse along older edges
          }
        }
      }
    }

    current.dfsColor = COLOR.BLACK; // Mark node black
    return false;
  }
  
  private void resetNodeDFSColor() {
    for (Map.Entry<AVDNode, HashSet<BlameAssignmentEdge>> entry : preciseEdges.entrySet()) {
      AVDNode node = entry.getKey();
      node.dfsColor = COLOR.WHITE;
    }
  }

}
