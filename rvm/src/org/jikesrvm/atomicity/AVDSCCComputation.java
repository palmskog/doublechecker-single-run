package org.jikesrvm.atomicity;

import org.jikesrvm.VM;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

@Uninterruptible
public class AVDSCCComputation {

  static int numCyclesDetected = 0;
  private static int consecutiveLinearTraversal = 0;
  // AVD: LATER: Higher values lead to stack overflow for xalan6 with static race results enabled.
  private static final int LINEAR_TRAVERSAL_THRESHOLD = 50;
  
  /** SCC invocation counter. This should always be reset/incremented after an SCC invocation 
   * is complete to prepare for the next invocation. Access to this should be protected. */
  static int sccInvocationCounter = 0;
  
  /** This is operated on as a stack */
  static AVDNodeList nodeStack = AVDNodeList.initialValue;
  static AVDNodeList nodeStackCopy = AVDNodeList.initialValue;
  
  @NoInline
  public static void findSCCHelper(AVDNode node) {
    if (VM.VerifyAssertions) { VM._assert(!MemoryManager.isHarnessEndReached()); }
    
    // AVD: LATER: Is it okay to skip SCC for unary transactions? Can we give a proof for this?
    if (node.isUnaryTrans) {
      return; // Start/dummy transaction is already unary
    }
    
    // This is bad, but we are planning to disable cycle detection from Thread 1 at 
    // least for the DaCapo benchmarks, but maybe we want to allow it for ETHZ/other benchmarks
    if ((AVD.bench.isDaCapoBenchmark() && node.octetThread.octetThreadID == AVD.DACAPO_DRIVER_THREAD_OCTET_ID)
        || (AVD.bench.getId() == BenchmarkInfo.ELEVATOR && node.octetThread.octetThreadID == AVD.ELEVATOR_DRIVER_THREAD_OCTET_ID)
        || (AVD.bench.getId() == BenchmarkInfo.MOLDYN && node.octetThread.octetThreadID == AVD.JAVA_GRANDE_DRIVER_THREAD_OCTET_ID)) {
      return;
    }

    // Helps reduce the overhead for few benchmarks
    if (node.octetThread.octetThreadID == AVD.FINALIZER_THREAD_OCTET_ID) {
      return;
    }
    
    RVMThread.phase1SCCLock.lockNoHandshake(); // Serialize access
    consecutiveLinearTraversal = 0;
    MemoryManager.startAllocatingInUninterruptibleCode(); // Disable GC
//    int sccCounterValue = computeSCC(node, node, sccInvocationCounter, sccInvocationCounter);
    int sccCounterValue = computeSCCWithLimitedLinearTraversal(node, node, sccInvocationCounter, sccInvocationCounter);
//    int sccCounterValue = computeSCCWithLimitedRecursion(node, node, sccInvocationCounter, sccInvocationCounter);
    MemoryManager.stopAllocatingInUninterruptibleCode(); // Enable GC
    // Reset stack for SCC
    nodeStack = AVDNodeList.initialValue;
    nodeStackCopy = AVDNodeList.initialValue;
    sccInvocationCounter = sccCounterValue + 1; // Increment SCC counter for next invocation
    RVMThread.phase1SCCLock.unlock();
  }
  
  /**
   * This is the scc computation method according to Tarjan's algorithm. Reference is Wikipedia,
   * {@link <a href="http://en.wikipedia.org/wiki/Tarjan's_strongly_connected_components_algorithm">Tarjan's SCC.</a>} 
   * This method invocation is currently serial.
   * @param startNode Node from which current SCC has been invoked
   * @param node  Node that is currently being explored
   * @param sccCounterStart  Counter value at the beginning of the SCC
   * @param sccCounterCurrent  Current counter value
   * @return
   */
  // We want to check for only completed transactions to avoid racy accesses to the read-write sets between
  // Phase 1 and Phase 2.
  // GC is disabled during SCC computation
  private static int computeSCC(AVDNode startNode, AVDNode node, int sccCounterStart, int sccCounterCurrent) {
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(ObjectReference.fromObject(node))); }
    if (VM.VerifyAssertions) { VM._assert(node.nextNode != null); } // Indicates current transaction is complete
    
    // Initialize node-local Phase 1 variables
    node.index = sccCounterCurrent;
    node.lowlink = sccCounterCurrent;
    sccCounterCurrent++;
    pushNodeToStack(node); // Push node onto the stack    
    
    // Consider cross-thread successors of node
    Address listHead = ObjectReference.fromObject(node).toAddress().plus(Entrypoints.avdOutgoingEdgesListField.getOffset());
    if (VM.VerifyAssertions) { VM._assert(!listHead.isZero()); }
    // Check whether access to outgoingEdgesList is blocked
    Address head;
    do {
      head = listHead.loadAddress();
    } while (head.EQ(AVD.LOCK_ADDRESS));
    // AVD: TODO: Is a fence required over here?
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(head.toObjectReference())); }
    AVDNodeList start = (AVDNodeList) head.toObjectReference().toObject();
    if (VM.VerifyAssertions) { VM._assert(start != null); }
    while (start.node != null) {
      AVDNode temp = start.node;
      // Check whether temp has terminated, i.e., the transaction has completed or not
      if (temp.nextNode != null) {
        // Check whether temp has been visited yet
        if (temp.index < sccCounterStart) { 
          // Indicates that temp has not yet been visited, so recurse on temp
          sccCounterCurrent = computeSCC(startNode, temp, sccCounterStart, sccCounterCurrent);
          node.lowlink = (node.lowlink <= temp.lowlink) ? node.lowlink : temp.lowlink; 
        } else { // temp has already been visited once, check if temp is in the stack          
          if (nodePresentInStack(temp, sccCounterStart)) {
            node.lowlink = (node.lowlink <= temp.index) ? node.lowlink : temp.index ;
          } 
        }
      } 
      start = start.next;
    }
    
    // No more outgoing cross-threaded edge, so now traverse along the one sequential edge
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(ObjectReference.fromObject(node.nextNode))); }
    if (node != startNode && node.nextNode.nextNode != null) { // Should not travel along start node
      AVDNode temp = node.nextNode;
      //        if (VM.VerifyAssertions) { VM._assert(node.nextNode.octetThread != startNode.octetThread); }
      // Check whether temp has been visited yet
      if (temp.index < sccCounterStart) {
        // Indicates that temp has not yet been visited, so recurse on temp
        sccCounterCurrent = computeSCC(startNode, temp, sccCounterStart, sccCounterCurrent);
        node.lowlink = (node.lowlink <= temp.lowlink) ? node.lowlink: temp.lowlink;
      } else { // temp has already been visited once, check if temp is in the stack
        if (nodePresentInStack(temp, sccCounterStart)) {
          node.lowlink = (node.lowlink <= temp.index) ? node.lowlink : temp.index;
        }
      }
    }
    
    // Check if node is the root node of a non-trivial SCC
    if (node.lowlink == node.index) {
      // node is the root node of an SCC (may be trivial)
      int length = popNodesFromStack(node);
      if (length > 1) {
        if (VM.VerifyAssertions) { VM._assert(length == getNodeListLength(nodeStackCopy)); }
        numCyclesDetected++;
        if (AVD.recordAVDStats()) {
          AVDStats.numPhase1Cycles.inc(1L);
        }
        // Report non-trivial SCC to Phase 2, no need to acquire locks since SCC computation is currently already serial
        if (AVD.performPhase2()) {
          AVD.phase1CyclesLock.lock();
          boolean flag = AVD.phase1Cycles.add(nodeStackCopy);
          if (VM.VerifyAssertions) { VM._assert(flag); }          
          AVDPhase2Thread.schedule();
          AVD.phase1CyclesLock.unlock();
        }
      }
      nodeStackCopy = AVDNodeList.initialValue;
    }
    
    return sccCounterCurrent;
  }

  /**
   * This is the scc computation method according to Tarjan's algorithm. Reference is Wikipedia,
   * {@link <a href="http://en.wikipedia.org/wiki/Tarjan's_strongly_connected_components_algorithm">Tarjan's SCC.</a>} 
   * This method invocation is currently serial.
   * @param startNode Node from which current SCC has been invoked
   * @param node  Node that is currently being explored
   * @param sccCounterStart  Counter value at the beginning of the SCC
   * @param sccCounterCurrent  Current counter value
   * @return
   */
  // We want to check for only completed transactions to avoid racy accesses to the read-write sets between
  // Phase 1 and Phase 2.
  // GC is disabled during SCC computation
  private static int computeSCCWithLimitedLinearTraversal(AVDNode startNode, AVDNode node, int sccCounterStart, int sccCounterCurrent) {
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(ObjectReference.fromObject(node))); }
    if (VM.VerifyAssertions) { VM._assert(node.nextNode != null); } // Indicates current transaction is complete
    
    // Initialize node-local Phase 1 variables
    node.index = sccCounterCurrent;
    node.lowlink = sccCounterCurrent;
    sccCounterCurrent++;
    pushNodeToStack(node); // Push node onto the stack    
    
    // Consider cross-thread successors of node
    Address listHead = ObjectReference.fromObject(node).toAddress().plus(Entrypoints.avdOutgoingEdgesListField.getOffset());
    if (VM.VerifyAssertions) { VM._assert(!listHead.isZero()); }
    // Check whether access to outgoingEdgesList is blocked
    Address head;
    do {
      head = listHead.loadAddress();
    } while (head.EQ(AVD.LOCK_ADDRESS));
    // AVD: TODO: Is a fence required over here?
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(head.toObjectReference())); }
    AVDNodeList start = (AVDNodeList) head.toObjectReference().toObject();
    if (VM.VerifyAssertions) { VM._assert(start != null); }
    while (start.node != null) {
      AVDNode temp = start.node;
      // Check whether temp has terminated, i.e., the transaction has completed or not
      if (temp.nextNode != null) {
        // Check whether temp has been visited yet
        if (temp.index < sccCounterStart) {
          consecutiveLinearTraversal = 0;
          // Indicates that temp has not yet been visited, so recurse on temp
          sccCounterCurrent = computeSCCWithLimitedLinearTraversal(startNode, temp, sccCounterStart, sccCounterCurrent);
          node.lowlink = (node.lowlink <= temp.lowlink) ? node.lowlink : temp.lowlink; 
        } else { // temp has already been visited once, check if temp is in the stack          
          if (nodePresentInStack(temp, sccCounterStart)) {
            node.lowlink = (node.lowlink <= temp.index) ? node.lowlink : temp.index ;
          } 
        }
      } 
      start = start.next;
    }
    
    // No more outgoing cross-threaded edge, so now traverse along the one sequential edge
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(ObjectReference.fromObject(node.nextNode))); }
    if (node != startNode && node.nextNode.nextNode != null // Should not travel along start node
        && consecutiveLinearTraversal < LINEAR_TRAVERSAL_THRESHOLD) { 
      AVDNode temp = node.nextNode;
      // Check whether temp has been visited yet
      if (temp.index < sccCounterStart) {
        // Indicates that temp has not yet been visited, so recurse on temp
        consecutiveLinearTraversal++;
        sccCounterCurrent = computeSCCWithLimitedLinearTraversal(startNode, temp, sccCounterStart, sccCounterCurrent);
        node.lowlink = (node.lowlink <= temp.lowlink) ? node.lowlink: temp.lowlink;
      } else { // temp has already been visited once, check if temp is in the stack
        if (nodePresentInStack(temp, sccCounterStart)) {
          node.lowlink = (node.lowlink <= temp.index) ? node.lowlink : temp.index;
        }
      }
    }
    
    // Check if node is the root node of a non-trivial SCC
    if (node.lowlink == node.index) {
      // node is the root node of an SCC (may be trivial)
      int length = popNodesFromStack(node);
      if (length > 1) {
        if (VM.VerifyAssertions) { VM._assert(length == getNodeListLength(nodeStackCopy)); }
        numCyclesDetected++;
        if (AVD.recordAVDStats()) {
          AVDStats.numPhase1Cycles.inc(1L);
        }
        // Report non-trivial SCC to Phase 2, no need to acquire locks since SCC computation is currently already serial
        if (AVD.performPhase2()) {
          AVD.phase1CyclesLock.lock();
          boolean flag = AVD.phase1Cycles.add(nodeStackCopy);
          if (VM.VerifyAssertions) { VM._assert(flag); }          
          AVDPhase2Thread.schedule();
          AVD.phase1CyclesLock.unlock();
        }
      }
      nodeStackCopy = AVDNodeList.initialValue;
    }
    
    return sccCounterCurrent;
  }
  
  /**
   * This is the scc computation method according to Tarjan's algorithm. Reference is Wikipedia,
   * {@link <a href="http://en.wikipedia.org/wiki/Tarjan's_strongly_connected_components_algorithm">Tarjan's SCC.</a>} 
   * This method invocation is currently serial.
   * @param startNode Node from which current SCC has been invoked
   * @param node  Node that is currently being explored
   * @param sccCounterStart  Counter value at the beginning of the SCC
   * @param sccCounterCurrent  Current counter value
   * @return
   */
  // We want to check for only completed transactions to avoid racy accesses to the read-write sets between
  // Phase 1 and Phase 2.
  // GC is disabled during SCC computation
  private static int computeSCCWithLimitedRecursion(AVDNode startNode, AVDNode node, int sccCounterStart, int sccCounterCurrent) {
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(ObjectReference.fromObject(node))); }
    if (VM.VerifyAssertions) { VM._assert(node.nextNode != null); } // Indicates current transaction is complete
    
    // Initialize node-local Phase 1 variables
    node.index = sccCounterCurrent;
    node.lowlink = sccCounterCurrent;
    sccCounterCurrent++;
    pushNodeToStack(node); // Push node onto the stack    
    
    // Consider cross-thread successors of node
    Address listHead = ObjectReference.fromObject(node).toAddress().plus(Entrypoints.avdOutgoingEdgesListField.getOffset());
    if (VM.VerifyAssertions) { VM._assert(!listHead.isZero()); }
    // Check whether access to outgoingEdgesList is blocked
    Address head;
    do {
      head = listHead.loadAddress();
    } while (head.EQ(AVD.LOCK_ADDRESS));
    // AVD: TODO: Is a fence required over here?
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(head.toObjectReference())); }
    AVDNodeList start = (AVDNodeList) head.toObjectReference().toObject();
    if (VM.VerifyAssertions) { VM._assert(start != null); }
    while (start.node != null) {
      AVDNode temp = start.node;
      // Check whether temp has terminated, i.e., the transaction has completed or not
      if (temp.nextNode != null) {
        // Check whether temp has been visited yet
        if (temp.index < sccCounterStart) { 
          // Indicates that temp has not yet been visited, so recurse on temp
          sccCounterCurrent = computeSCC(startNode, temp, sccCounterStart, sccCounterCurrent);
          node.lowlink = (node.lowlink <= temp.lowlink) ? node.lowlink : temp.lowlink; 
        } else { // temp has already been visited once, check if temp is in the stack          
          if (nodePresentInStack(temp, sccCounterStart)) {
            node.lowlink = (node.lowlink <= temp.index) ? node.lowlink : temp.index ;
          } 
        }
      } 
      start = start.next;
    }
    
    // No more outgoing cross-threaded edge, so now traverse along the one sequential edge
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(ObjectReference.fromObject(node.nextNode))); }
    if (node != startNode && node.nextNode.nextNode != null) { // Should not travel along start node
      AVDNode temp = node.nextNode;
      // Do not recurse over linearly-traversable nodes, these are being pushed to the stack, so they may be required to 
      // pop off the stack before returning from this call
      AVDNode prev = node;
      while (!doesNodeHaveCrossThreadEdge(temp)) {
        temp.index = sccCounterCurrent;
        temp.lowlink = sccCounterCurrent;
        sccCounterCurrent++;
        pushNodeToStack(temp);
        if (temp.index >= sccCounterStart) { // temp has already been visited during this SCC computation
          if (nodePresentInStack(temp, sccCounterStart)) {
            // "node" may no longer be the previous/parent node for temp
            prev.lowlink = (prev.lowlink <= temp.index) ? prev.lowlink : temp.index;
          }
        }

        if (temp.nextNode == null) { // Next node is still running, i.e., transaction has not completed
          break;
        }
        prev = temp;
        temp = temp.nextNode;
      }   

      // temp now has a cross-thread edge, so we will now recurse
      // Check whether temp has been visited yet
      if (temp.index < sccCounterStart) {
        // Indicates that temp has not yet been visited, so recurse on temp
        sccCounterCurrent = computeSCC(startNode, temp, sccCounterStart, sccCounterCurrent);
        // "node" may no longer be the previous/parent node for temp
        prev.lowlink = (prev.lowlink <= temp.lowlink) ? prev.lowlink: temp.lowlink;
      } else { // temp has already been visited once, check if temp is in the stack
        if (nodePresentInStack(temp, sccCounterStart)) {
          prev.lowlink = (prev.lowlink <= temp.index) ? prev.lowlink : temp.index;
        }
      }

    }
    
    // Check to see whether the linearly traversed nodes need to be popped off stack
    while (peekTopOfStack() != node) {
      if (VM.VerifyAssertions) { VM._assert(nodePresentInStack(node, sccCounterStart)); }
      if (peekTopOfStack().lowlink == peekTopOfStack().index) {
        AVDNode temp = peekTopOfStack();
        int length = popNodesFromStack(temp);
        if (VM.VerifyAssertions) { VM._assert(length == 1); }
      }
    }
    
    // Check if node is the root node of a non-trivial SCC
    if (node.lowlink == node.index) {
      // node is the root node of an SCC (may be trivial)
      int length = popNodesFromStack(node);
      if (length > 1) {
        if (VM.VerifyAssertions) { VM._assert(length == getNodeListLength(nodeStackCopy)); }
        numCyclesDetected++;
        if (AVD.recordAVDStats()) {
          AVDStats.numPhase1Cycles.inc(1L);
        }
        // Report non-trivial SCC to Phase 2, no need to acquire locks since SCC computation is currently already serial
        if (AVD.performPhase2()) {
          AVD.phase1CyclesLock.lock();
          boolean flag = AVD.phase1Cycles.add(nodeStackCopy);
          if (VM.VerifyAssertions) { VM._assert(flag); }          
          AVDPhase2Thread.schedule();
          AVD.phase1CyclesLock.unlock();
        }
      }
      nodeStackCopy = AVDNodeList.initialValue;
    }
    
    return sccCounterCurrent;
  }
  
  private static boolean doesNodeHaveCrossThreadEdge(AVDNode node) {
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(ObjectReference.fromObject(node))); }
    Address listHead = ObjectReference.fromObject(node).toAddress().plus(Entrypoints.avdOutgoingEdgesListField.getOffset());
    if (VM.VerifyAssertions) { VM._assert(!listHead.isZero()); }
    // Check whether access to outgoingEdgesList is blocked
    Address head;
    do {
      head = listHead.loadAddress();
    } while (head.EQ(AVD.LOCK_ADDRESS));
    // AVD: TODO: Is a fence required over here?
    if (VM.VerifyAssertions) { VM._assert(MemoryManager.validRef(head.toObjectReference())); }
    AVDNodeList start = (AVDNodeList) head.toObjectReference().toObject();
    if (VM.VerifyAssertions) { VM._assert(start != null); }
    if (VM.VerifyAssertions && start.node == null) { VM._assert(start.description == -1 && start.next == null); }
    return (start.node != null);
  }

  private static int getNodeListLength(AVDNodeList list) {
    if (VM.VerifyAssertions) { VM._assert(list != null); }
    int count = 0;
    AVDNodeList temp = list;
    while (temp.node != null) {
      count++;
      temp = temp.next;
    }
    return count;
  }

  private static int popNodesFromStack(AVDNode node) {
    AVDNodeList temp = nodeStack;
    if (VM.VerifyAssertions) { VM._assert(nodeStackCopy == AVDNodeList.initialValue); }
    AVDNodeList temp2 = null;
    int count = 0;
    while (temp.node != node) {
      temp2 = AVDAnalysis.createNewListHead(temp.node, 0);
      temp2.next = nodeStackCopy;
      nodeStackCopy = temp2;
      count++;
      temp = temp.next;
    }
    // Copy root node
    temp2 = AVDAnalysis.createNewListHead(temp.node, 0);
    temp2.next = nodeStackCopy;
    nodeStackCopy = temp2;
    nodeStack = temp.next; // Reset the pointer, i.e., popping off nodes
    count++;
    return count;
  }

  private static boolean nodePresentInStack(AVDNode node, int sccCounterStart) {
    AVDNodeList temp = nodeStack;
    while (temp != null) {
      if (temp.node == node) {
        if (VM.VerifyAssertions) { 
          VM._assert(temp.node.index >= sccCounterStart);
          VM._assert(temp.node.transactionID == node.transactionID &&
              temp.node.octetThread == node.octetThread);
        }
        return true;
      }
      temp = temp.next;
    }
    return false;
  }

  private static void pushNodeToStack(AVDNode node) {
    AVDNodeList newHeadValue = AVDAnalysis.createNewListHead(node, /*conflictType = */ 0);
    if (VM.VerifyAssertions) { VM._assert(newHeadValue != null); }
    newHeadValue.next = nodeStack;
    nodeStack = newHeadValue;
  }
  
  private static AVDNode peekTopOfStack() {
    if (VM.VerifyAssertions) { VM._assert(nodeStack != null); }
    return nodeStack.node;
  }
  
}