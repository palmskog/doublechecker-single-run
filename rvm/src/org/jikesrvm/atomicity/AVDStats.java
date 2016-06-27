package org.jikesrvm.atomicity;

import org.jikesrvm.VM;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;

/**
 * This class is very similar to Octet Stats, but I want couple of different things, 
 * I want the methods to be uninterruptible always, and I want to print the values not at 
 * the end but at intermediate times during execution. 
 * 
 * We can introduce another build config separate from Octet::getConfig::stats(), but I am not sure
 * what difference inlining can make.
 *
 */
@Uninterruptible
public class AVDStats {

  // Phase 1 nodes
  public static final AVDThreadSafeCounter numNodes = new AVDThreadSafeCounter("AVDNodes");
  public static final AVDThreadSafeCounter numRegularTransactions = new AVDThreadSafeCounter("AVDNumRegularTransactions");
  public static final AVDThreadSafeCounter numUnaryTransactions = new AVDThreadSafeCounter("AVDNumUnaryTransactions");
  public static final AVDThreadSafeCounter numTransactionStartSlowPath = new AVDThreadSafeCounter("AVDNumTransactionStartSlowPath");
  public static final AVDThreadSafeCounter numTransactionEndSlowPath = new AVDThreadSafeCounter("AVDNumTransactionEndSlowPath");
  
  // Cross-thread edges
  public static final AVDThreadSafeCounter numPhase1Edges = new AVDThreadSafeCounter("AVDPhase1Edges");
  
  // Hash table
  public static AVDThreadSafeCounter numHashTableGrown = new AVDThreadSafeCounter("AVDNumHashTableGrown");
  public static AVDThreadSafeCounter numHTGrownFromCopy = new AVDThreadSafeCounter("AVDNumHTGrownFromCopy");
  public static AVDThreadSafeCounter numHTGrownDuringLogging = new AVDThreadSafeCounter("AVDNumHTGrownDuringLogging");
  public static AVDThreadSafeCounter numCollisions = new AVDThreadSafeCounter("AVDNumCollisions");
  public static AVDThreadSafeCounter numPrimaryHits = new AVDThreadSafeCounter("AVDPrimaryHits");
  public static AVDThreadSafeCounter numRdWrPatterns = new AVDThreadSafeCounter("AVDNumRdWrPatterns");
  public static AVDThreadSafeCounter numEmptySlots = new AVDThreadSafeCounter("AVDNumEmptySlots");
  public static AVDThreadSafeCounter numSecondaryHashCalled = new AVDThreadSafeCounter("AVDNumSecondaryHashCalled");
  public static AVDThreadSafeCounter numCopyCollisions = new AVDThreadSafeCounter("AVDNumCopyCollisions");
  public static AVDThreadSafeCounter numCopySecondaryHash = new AVDThreadSafeCounter("AVDNumCopySecondaryHash");
  public static AVDThreadSafeCounter numCopyPrimaryHits = new AVDThreadSafeCounter("AVDNumCopyPrimaryHits");
  public static AVDThreadSafeCounter numCopyCollisionsExceeding = new AVDThreadSafeCounter("AVDNumCopyCollisionsExceeding");
  public static AVDThreadSafeCounter numHTCopies = new AVDThreadSafeCounter("AVDNumHTCopies");
  public static AVDThreadSafeCounter numHashTableClears = new AVDThreadSafeCounter("AVDNumHashTableClears");
  public static AVDThreadSafeCounter numHTClearStartTxSP = new AVDThreadSafeCounter("AVDNumHTClearStartTxSP");
  public static AVDThreadSafeCounter numHTClearUnaryTx = new AVDThreadSafeCounter("AVDNumHTClearUnaryTx");
  public static AVDThreadSafeCounter numHTClearImplicit = new AVDThreadSafeCounter("AVDNumHTClearImplicit");
  public static AVDThreadSafeCounter numHTClearUpgrading = new AVDThreadSafeCounter("AVDNumHTClearUpgrading");
  public static AVDThreadSafeCounter numHTClearFence = new AVDThreadSafeCounter("AVDNumHTClearFence");
  public static AVDThreadSafeCounter numHTClearReceivedResponse = new AVDThreadSafeCounter("AVDNumHTClearReceivedResponse");
  public static AVDThreadSafeCounter numHTClearRequestOnRespondingThread = new AVDThreadSafeCounter("AVDNumHTClearRequestOnRespondingThread");
  public static AVDThreadSafeCounter numHTClearAfterUnblockIfRequests = new AVDThreadSafeCounter("AVDNumHTClearAfterUnblockIfRequests");
  
  // Read-write log
  public static AVDThreadSafeCounter numLogBufferGrown = new AVDThreadSafeCounter("AVDNumLogBufferGrown");
  
  // Phase 1 cycle
  public static final AVDThreadSafeCounter numPhase1CycleInvocation = new AVDThreadSafeCounter("AVDPhase1CycleInvocation");
  public static final AVDThreadSafeCounter numPhase1Cycles = new AVDThreadSafeCounter("AVDPhase1Cycles");
  public static final AVDUnsyncHistogram sizeOfPhase1Cycles = new AVDUnsyncHistogram("AVDSizeOfPhase1Cycles", false, 2048);
  
  // Phase 2 
  public static final AVDThreadSafeCounter numPhase2Iterations = new AVDThreadSafeCounter("AVDNumPhase2Iterations");
  
  // Linear Array
  public static AVDThreadSafeCounter numAverageLinearArraySizeForHTClear = new AVDThreadSafeCounter("AVDNumAverageLinearArraySize");
  public static AVDThreadSafeCounter numLinearArrayGrowths = new AVDThreadSafeCounter("AVDNumLinearArrayGrowths");
  
  // Total accesses
  public static AVDThreadSafeCounter numAccessesFromOctetThreads = new AVDThreadSafeCounter("AVDNumAccessesFromOctetThreads");
  public static AVDThreadSafeCounter numAccessesFromSystemThreads = new AVDThreadSafeCounter("AVDNumAccessesFromSystemThreads");
  public static final AVDThreadSafeCounter numTotalPhase1Instructions = new AVDThreadSafeCounter("AVDNumTotalPhase1Instructions");
  public static final AVDThreadSafeCounter numPhase1InstructionsLogged = new AVDThreadSafeCounter("AVDNumPhase1InstructionsLogged");
  public static final AVDThreadSafeCounter numUniquePhase2Instructions = new AVDThreadSafeCounter("AVDNumUniquePhase2Instructions");
  public static final AVDThreadSafeCounter numBarrierCallsInVMContext = new AVDThreadSafeCounter("AVDNumBarrierCallsInVMContext");
  public static final AVDThreadSafeCounter numBarrierCallsInTotal = new AVDThreadSafeCounter("AVDNumBarrierCallsInTotal");
  public static final AVDThreadSafeCounter numBarrierCallsFromRegularTx = new AVDThreadSafeCounter("AVDNumBarrierCallsFromRegularTx");
  public static final AVDThreadSafeCounter numBarrierCallsFromUnaryTx = new AVDThreadSafeCounter("AVDNumBarrierCallsFromUnaryTx");
  
  @Uninterruptible
  public static abstract class Stat {
    
    public static final String SEPARATOR = ": ";
    public static final String LINE_PREFIX = "AVDSTATS" + SEPARATOR;
    
    final String name;
    
    Stat(String name) {
      this.name = name;
    }
    
    // AVD: LATER: Make this uninterruptible by using Atoms maybe 
    @Interruptible
    String outputLinePrefix() {
      return LINE_PREFIX + this.getClass().getName() + SEPARATOR;
    }
  }
  
  @Uninterruptible
  public static class AVDThreadSafeCounter extends Stat {
    
    private final long[] values;
    
    AVDThreadSafeCounter(String name) {
      super(name);
      values = Octet.getConfig().recordAVDStats() ? new long[RVMThread.MAX_THREADS] : null;
    }
    
    @Inline
    public void inc(long value) {
      if (VM.VerifyAssertions) { VM._assert(AVD.recordAVDStats()); }
      if (VM.runningVM) {
        // Do not increment dynamic stats if we are not in Harness, but inHarness() is false, why?
        if (MemoryManager.inHarness()) { // Remember to change this in two places
          values[RVMThread.getCurrentThreadSlot()] += value;
        } 
      }
    }
    
    @Inline
    public void inc(RVMThread thread, long value) {
      if (VM.VerifyAssertions) { VM._assert(AVD.recordAVDStats()); }
      if (VM.runningVM) {
        // Do not increment dynamic stats if we are not in Harness, but inHarness() is false, why?
        if (MemoryManager.inHarness()) { // Remeber to change this in two places
          values[thread.getGivenThreadSlot()] += value;
        }
      }
    }
    
    @Inline
    final long total() {
      long total = 0;
      for (long value : values) {
        total += value;
      }
      return total;
    }
    
    @Interruptible
    final long report() {
      VM.sysWrite(outputLinePrefix());
      VM.sysWrite(this.name);
      VM.sysWrite(SEPARATOR);
      long total = total(); 
      VM.sysWriteln(total, false); // Don't print hex value by default 
      return total;
    }
    
  }
  
  @Uninterruptible
  public static final class AVDUnsyncHistogram extends Stat {
    
    final boolean log2;
    private final int[] data;
    
    AVDUnsyncHistogram(String name, boolean log2, long maxX) {
      super(name);
      this.log2 = log2;
      this.data = Octet.getConfig().recordAVDStats() ? new int[log2 ? log2(maxX) : (int)maxX] : null;
    }
    
    @Inline
    public final void incBin(long x) {
      if (VM.VerifyAssertions) { VM._assert(Octet.getConfig().recordAVDStats()); }
      if (log2) {
        data[log2(x)]++;
      } else {
        data[(int)x]++;
      }
    }
    
    @Inline
    public final long total() {
      int i;
      long sum = 0;
      for (i = 0; i < data.length; i++) {
        sum += data[i];
      }
      return sum;
    }
    
    @Inline
    public final double arithmeticMean() { // Weighted arithmetic mean. Overestimated.
      int lastNonzeroIndex;
      for (lastNonzeroIndex = data.length - 1; lastNonzeroIndex >= 0 && data[lastNonzeroIndex] == 0; lastNonzeroIndex--) { 
      }
      double sum = 0;
      int weight = 0;
      if (lastNonzeroIndex > 0) {
        for (int x = 0; x <= lastNonzeroIndex; x++) {
          sum += data[x] * ((1 << (x + 1)) -1);
          weight += ((1 << (x + 1)) -1);
        }
        return sum / weight;
      }
      return 0;
    }
    
    @Interruptible
    final void report() {
      int lastNonzeroIndex; 
      // Don't print any tail of all 0s
      for (lastNonzeroIndex = data.length - 1; lastNonzeroIndex >= 1 && data[lastNonzeroIndex] == 0; lastNonzeroIndex--) { 
      }
      for (int x = 0; x <= lastNonzeroIndex; x++) {
        VM.sysWrite(outputLinePrefix());
        VM.sysWrite(name, "[", x);
        VM.sysWrite("] =", data[x]);
        if (log2) {
          VM.sysWrite("(", data[x] << x, ")");
        }
        VM.sysWriteln();
      }
    }
    
    static final int log2(long n) {
      if (n <= 1) {
        return 0;
      } else {
        return 1 + log2(n / 2);
      }
    }
    
  }
  
}
