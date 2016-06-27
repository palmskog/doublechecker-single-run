package org.jikesrvm.atomicity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import org.jikesrvm.Callbacks;
import org.jikesrvm.Callbacks.ExitMonitor;
import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.Atom;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.MethodReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.RVMMember;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.compilers.opt.ir.operand.MethodOperand;
import org.jikesrvm.mm.mminterface.Barriers;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.SpinLock;
import org.jikesrvm.util.AVDLinkedListRVM;
import org.jikesrvm.util.HashSetRVM;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Untraced;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Word;

// Remember to make changes in RVM.txt if the class names of AVDNode and CrossThreadAccess change 
/** AVD: Class defining generic features and settings. */
@Uninterruptible
public final class AVD implements Constants {
  
  enum COLOR {WHITE, GRAY, BLACK} // GRAY IS A COLOR, GREY IS A COLOUR
  
  // An enumeration to distinguish the types of accesses
  public static final int REGULAR_READ = 0;
  public static final int LOCK_READ = 1;
  public static final int REGULAR_WRITE = 2;
  public static final int LOCK_WRITE = 3;

  @Inline
  public static boolean isRegularRead(int type) {
    return type == REGULAR_READ;
  }
  
  @Inline
  public static boolean isAnyRead(int type) {
    return type <= LOCK_READ;
  }
  
  @Inline
  public static boolean isAnyWrite(int type) {
    return type >= REGULAR_WRITE;
  }
  
  @Inline
  public static boolean isRegularWrite(int type) {
    return type == REGULAR_WRITE;
  }

  /** Current benchmark info */
  public static BenchmarkInfo bench = null;
  
  // Sync these uses with Velodrome
  static final int FINALIZER_THREAD_OCTET_ID = 0;
  static final int DACAPO_DRIVER_THREAD_OCTET_ID = 1;
  static final int JAVA_GRANDE_DRIVER_THREAD_OCTET_ID = 1;
  static final int ELEVATOR_DRIVER_THREAD_OCTET_ID = 1;
  static final int TSP_DRIVER_THREAD_OCTET_ID = 1;

  // Boundary point count is an always increasing number. Note that the maximum count is bounded by the definition of
  // AVDMetadataHelper.NUM_BITS_TO_STORE_BPNUM.
  // It is correct to start from 0. For the very first access, the metadata word is zero, but the fast path check would
  // fail since the thread bpCount is merged with the thread id. 
  public static final int INITIAL_BP_COUNT = 0;
  /** Initial size of the cross-thread access array, the buffer can grow based on the needs */
  static final int MAX_CROSS_THREAD_ACCESS_SIZE = 10;
  
  public static final Address DEAD = Address.fromIntSignExtend(1);

  // The location of the JTOC seems to change depending on whether array instrumentation is enabled or not
  public static final Address JTOC_ADDRESS = !instrumentArrays() ? Address.fromIntSignExtend(0x40080260) 
                                                                 : Address.fromIntSignExtend(0x40080270);
  
  // Variables that are needed for creating RdSh -> RdSh dependence edges. These variables should always be accessed
  // while holding the associated lock.
  // This reference is to be treated as a weak reference.
  @Entrypoint
  @Untraced
  public static AVDNode lastTransToUpdateGlobalRdShCounter = null;
  static int logIndexForLastTransToUpdateGlobalRdShCounter = -1;
  static final SpinLock lastTransToUpdateGlobalRdShCounterLock = new SpinLock();
  
  /** String ends with a / (e.g., /home/biswass/) */
  private static String homePrefix = System.getProperty("user.home") + "/";
  static String outputDirectoryName = homePrefix + "avd-output/"; 
  private static String directoryPrefix = homePrefix + "avdRvmRoot/avd/";
  
  /** Hash set of method signatures that are not transactions */
  public static final HashSetRVM<Atom> notTransactions = new HashSetRVM<Atom>();
  
  /** Special address value used to ensure mutually exclusive access */
  static final Address LOCK_ADDRESS = Address.fromIntSignExtend(1);
  
  /** Data structure to pass list of nodes involved in a Phase 1 cycle to Phase 2. 
   *  Accesses to this variable need to be protected by {@code phaseInterfaceLock}. */
  static AVDLinkedListRVM phase1Cycles = new AVDLinkedListRVM();
  /** This mutex is used to protect access to {@code phase1Cycles}. */
  static final SpinLock phase1CyclesLock = new SpinLock();

  public static final int UNINITIALIZED_METADATA = Word.zero().toInt();
  public static final int UNINITIALIZED_OFFSET = /*Integer.MIN_VALUE*/ RVMMember.NO_OFFSET;
  public static final int UNINITIALIZED_SITE = Integer.MIN_VALUE;

  public static final int START_TRANSACTION_ID = 1; 
  public static final int INVALID_TRANSACTION_ID = -1;
  
  // No. of words per field
  private final static int NUM_METADATA_FIELDS = 1;
  private static final int LOG_FIELD_SIZE = LOG_BYTES_IN_WORD;
  public static final int FIELD_SIZE = NUM_METADATA_FIELDS << LOG_FIELD_SIZE;

  // Build configuration methods 
  
  @Pure
  @Inline
  public static boolean useDebugNodes() {
    return Octet.getConfig().useDebugNodes();
  }
  
  @Pure
  @Inline
  public static boolean isAVDEnabled() {
    return Octet.getConfig().isAVDEnabled();
  }
  
  /** Returns true/false depending on whether methods should be treated as atomic blocks. */
  @Pure
  @Inline
  public static boolean methodsAsTransactions() {
    return Octet.getConfig().methodsAsTransactions();
  }
  
  /** Returns true/false depending on whether sync blocks are treated as transactions or not. */
  @Pure
  @Inline
  public static boolean syncBlocksAsTransactions() {
    return Octet.getConfig().syncBlocksAsTransactions();    
  }
  
  @Pure
  @Inline
  public static boolean inlineStartEndTransactions() {
    return Octet.getConfig().inlineStartEndTransactions();
  }
  
  @Pure
  @Inline
  public static boolean inlineBarriers() {
    return Octet.getConfig().inlineBarriers();
  }
  
  @Pure
  @Inline
  public static boolean trackSynchronizationPrimitives() {
    return Octet.getConfig().trackSynchronizationPrimitives();
  } 
  
  @Pure
  @Inline
  public static boolean trackThreadSynchronizationPrimitives() {
    return Octet.getConfig().trackThreadSynchronizationPrimitives();
  }
  
  @Pure
  @Inline
  public static boolean recordConflictingAccess() {
    return Octet.getConfig().recordConflictingAccess();
  }

  @Pure
  @Inline
  public static boolean crossThreadEdgeCreationEnabled() {
    return Octet.getConfig().crossThreadEdgeCreationEnabled();
  }

  /** Returns true/false depending on whether Phase 1 cycle detection is to be invoked or not. */
  @Pure
  @Inline
  public static boolean invokeCycleDetection() {
    return Octet.getConfig().invokeCycleDetection();
  }

  /** Returns true/false depending on whether read/write accesses are to be tracked or not. */
  @Pure
  @Inline
  public static boolean logRdWrAccesses() {
    return Octet.getConfig().logRdWrAccesses(); 
  }
  
  @Pure
  @Inline
  public static boolean addPerFieldMetadata() {
    return Octet.getConfig().addPerFieldMetadata();
  }

  @Pure
  @Inline
  public static boolean recordAccessInfoInHashTable() {
    return Octet.getConfig().recordAccessInfoInHashTable();
  }
  
  @Pure
  @Inline
  public static boolean oldGrowAndCopyHashSet() {
    return Octet.getConfig().oldGrowAndCopyHashSet();
  }
  
  @Pure
  @Inline
  public static boolean useLinearArrayOfIndices() {
    return Octet.getConfig().useLinearArrayOfIndices();
  }

  @Pure
  @Inline
  public static boolean traceReadWriteSetAsStrongReferences() {
    return Octet.getConfig().traceReadWriteSetAsStrongReferences();
  }
  
  @Pure
  @Inline
  public static boolean traceReadWriteSetDuringWeakReferencePhase() {
    return Octet.getConfig().traceReadWriteSetDuringWeakReferencePhase();
  }
  
  @Pure
  @Inline
  public static boolean performPhase2() {
    return Octet.getConfig().performPhase2();
  }
  
  @Pure
  @Inline
  public static boolean recordAVDStats() {
    return Octet.getConfig().recordAVDStats();
  }
  
  @Pure
  @Inline
  public static boolean generateDotFile() {
    return Octet.getConfig().generateDotFile();
  }
  
  @Pure
  @Inline
  public static boolean includeSiteIDinReadWriteSet() {
    return Octet.getConfig().includeSiteIDinReadWriteSet();
  }
  
  @Pure
  @Inline
  public static boolean writePhase2OutputToFile() {
    return Octet.getConfig().writePhase2OutputToFile();
  }
  
  /** Used only for debugging at start and end transaction instrumentation. */
  @Pure
  @Inline
  public static boolean checkStartTransactionInstrumentation() {
    return Octet.getConfig().checkStartTransactionInstrumentation();
  }
  
  /** Used only for debugging static context of methods at every prolog. */
  @Pure
  @Inline
  public static boolean checkMethodContextAtProlog() {
    return Octet.getConfig().checkMethodContextAtProlog();
  }
  
  /** Used for debugging that read/write barriers calls are invoked from desired places. */
  @Pure
  @Inline
  public static boolean verifyAVDBarrierCall() {
    return Octet.getConfig().verifyAVDBarrierCall();
  }
  
  @Pure
  @Inline
  public static boolean isPerformanceRun() {
    return Octet.getConfig().isPerformanceRun();
  }
  
  @Pure
  @Inline
  public static boolean instrumentArrays() {
    return Octet.getConfig().instrumentArrays();
  }

  @Pure
  @Inline
  public static boolean iterativeRefinementStart() {
    return Octet.getConfig().iterativeRefinementStart();
  }
  
  @Pure
  @Inline
  public static boolean iterativeRefinementHalfway() {
    return Octet.getConfig().iterativeRefinementHalfway();
  }

  @Pure
  @Inline
  public static boolean checkBasicBlockNumbering() {
    return Octet.getConfig().checkBasicBlockNumbering();
  }
  
  static {
    
    // Writing to Graphviz file is enabled only for debug nodes
    if (VM.VerifyAssertions) { VM._assert(generateDotFile() ? useDebugNodes() : true); }
    
    Callbacks.addExitMonitor(new ExitMonitor() { // MMTk harness is already stopped
      
      public void notifyExit(int value) {  
        RVMThread currentThread = RVMThread.getCurrentThread();  
        
        if (performPhase2()) {
          // Wait till Phase 2 thread completes its work. The current work performed by 
          // Phase 2 thread may not be included in stats for certain benchmarks like eclipse6
          // because SCCs are detected after harnessEnd() is called.
          while (AVDPhase2Thread.numPendingRequests > 0 || AVDPhase2Thread.isRunning);
        }
        
        if (isAVDEnabled() && currentThread.isOctetThread()) {
        }
        
        if (recordAVDStats()) {
          printAVDStats();
        }
      }
      
    } );
    
  }
  
  /** Perform activities that are supposed to be carried out during boot time. 
   * 1) Read the set of methods that are not to be treated as transactions
   */
  @Interruptible
  public static void boot() {
    bench = new BenchmarkInfo();

    // Prepare the hash set of non-transactional methods
    if (VM.VerifyAssertions) { VM._assert(methodsAsTransactions()); }
    String prefix = directoryPrefix;
    if (iterativeRefinementStart()) { // Measure overhead of iterative refinement, isPerformanceRun() is ignored
      prefix += "iterative-refinement-start/";
    } else if (iterativeRefinementHalfway()) {
      prefix += "iterative-refinement-halfway/";
    } else if (isPerformanceRun()) { // Performance run, or imprecise analysis in multi-run mode
      prefix += "atomicity-specifications/";
    } else { // Possibly generate new violations
      prefix += "exclusion-list/";
    }
    readGenericFile(prefix + "methodNames.txt");
    if (!bench.isMicroBenchmark()) {
      readIndividualFile(prefix);
    }
  }
  
  /** This is for all methods that are generic. */
  @Interruptible
  static void readGenericFile(String path) {
    readFile(new File(path));
  }
  
  @Interruptible
  static void readIndividualFile(String path) {
    if (VM.VerifyAssertions) { VM._assert(methodsAsTransactions()); }
    String name = "";
    switch (bench.getId()) {
      case BenchmarkInfo.ELEVATOR: {
        name = "elevator.txt"; break;
      }
      case BenchmarkInfo.PHILO: {
        name = "philo.txt"; break;
      }
      case BenchmarkInfo.SOR: {
        name = "sor.txt"; break;
      }
      case BenchmarkInfo.TSP: {
        name = "tsp.txt"; break;
      }
      case BenchmarkInfo.HEDC: {
        name = "hedc.txt"; break;
      }
      case BenchmarkInfo.MOLDYN: {
        name = "moldyn.txt"; break;
      }
      case BenchmarkInfo.MONTECARLO: {
        name = "montecarlo.txt"; break;
      }
      case BenchmarkInfo.RAYTRACER: {
        name = "raytracer.txt"; break;
      }
      case BenchmarkInfo.ECLIPSE6: {
        name = "eclipse6.txt"; break;
      }
      case BenchmarkInfo.HSQLDB6: {
        name = "hsqldb6.txt"; break;
      }
      case BenchmarkInfo.XALAN6: {
        name = "xalan6.txt"; break;
      }
      case BenchmarkInfo.LUSEARCH6: {
        name = "lusearch6.txt"; break;
      }
      case BenchmarkInfo.AVRORA9: {
        name = "avrora9.txt"; break;
      }
      case BenchmarkInfo.PMD9: {
        name = "pmd9.txt"; break; 
      }
      case BenchmarkInfo.LUINDEX9: {
        name = "luindex9.txt"; break;
      }
      case BenchmarkInfo.LUSEARCH9_FIXED: {
        name = "lusearch9.txt"; break;
      }
      case BenchmarkInfo.XALAN9: {
        name = "xalan9.txt"; break; 
      }
      case BenchmarkInfo.SUNFLOW9: {
        name = "sunflow9.txt"; break; 
      }
      case BenchmarkInfo.JYTHON9: {
        name = "jython9.txt"; break;
      }
      case BenchmarkInfo.RAJA: {
        name = "raja.txt"; break;
      }
      default: {
        if (VM.VerifyAssertions) { VM._assert(NOT_REACHED); }
      }
    }
    readFile(new File(path + name));
  }

  /** Read method names from the given {@code file}. */
  @Interruptible
  private static void readFile(File file) {
    try {
      BufferedReader reader = new BufferedReader(new FileReader(file));
      String line = null;
      try {
        while ((line = reader.readLine()) != null) {      
          if (line.length() == 0 || line.charAt(0) == '#' /*Indicates a comment in the file, ignore*/) { 
            continue;
          }
          String newline = line.substring(0, line.lastIndexOf(":")); // Stripping line and bci information
          notTransactions.add(Atom.findOrCreateAsciiAtom(newline));
        } 
      } catch(IndexOutOfBoundsException e) {
        VM.sysWriteln("String:", line);
        VM.sysFail("Possibly wrong method name format in exclusion file");
      } finally {
        reader.close(); // Done with reading the file
      }
    } catch(IOException e) {
      VM.sysWrite("Cannot read exclusion list from file ");
      VM.sysWriteln(file.getAbsolutePath());
      VM.sysFail("Exiting");
    }
  }

  @Interruptible
  public static Atom constructMethodSignature(RVMMethod method) {
    if (VM.VerifyAssertions) { VM._assert(method != null && method.getDeclaringClass() != null); }
    String str = method.getDeclaringClass().getDescriptor().toString() + "." + method.getName().toString() + " " + 
                 method.getDescriptor().toString();
    Atom at = Atom.findOrCreateAsciiAtom(str);
    return at;
  }
  
  @Interruptible
  public static Atom constructMethodSignature(MethodReference methodRef) {
    if (VM.VerifyAssertions) { VM._assert(methodRef != null); }
    String str = methodRef.getType().getName().toString() + "." + methodRef.getName().toString() + " " + 
                 methodRef.getDescriptor().toString();
    Atom at = Atom.findOrCreateAsciiAtom(str);
    return at;
  }  
  
  @Interruptible
  public static Atom constructMethodSignature(MethodOperand methOp) {
    if (VM.VerifyAssertions) { VM._assert(methOp != null); }
    TypeReference tRef = methOp.getMemberRef().getType();
    String str = tRef.getName().toString() + "." + methOp.getMemberRef().getName().toString() + " " + 
                 methOp.getMemberRef().getDescriptor().toString();
    Atom at = Atom.findOrCreateAsciiAtom(str);
    return at;
  }

  @Interruptible
  public static boolean isRVMMethod(RVMMethod method) {
    if (VM.VerifyAssertions) { VM._assert(method != null); }
    TypeReference tRef = method.getMemberRef().getType();
    String str = tRef.getName().toString();
    return (str.indexOf("Lorg/jikesrvm/") >= 0 || str.indexOf("Lorg/mmtk/") >= 0);
  }

  /** Check whether the method referenced by {@code methOp} is an RVM method, if yes return true */
  @Interruptible
  public static boolean isRVMMethod(NormalMethod method, MethodOperand methOp) {
    if (VM.VerifyAssertions) { VM._assert(method != null && methOp != null); }
    TypeReference tRef = methOp.getMemberRef().getType();
    String str = tRef.getName().toString();
    return (str.indexOf("Lorg/jikesrvm/") >= 0 || str.indexOf("Lorg/mmtk/") >= 0);
  }

	/** Check whether the method referenced by @code methOp} needs to be instrumented, or not */
  @Interruptible
  public static boolean instrumentCalleeMethod(NormalMethod method, MethodOperand methOp) {
    if (VM.VerifyAssertions) { Octet.shouldInstrumentMethod((RVMMethod)method); }
    if (VM.VerifyAssertions) { VM._assert(methOp != null); }
    TypeReference tRef = methOp.getMemberRef().getType();
    return Octet.shouldInstrumentClass(tRef);
  }

  @Inline
  public static int getNumFields(RVMField field) {
    return NUM_METADATA_FIELDS;
  }
  
  public static boolean shouldAddPerFieldAVDMetadata(RVMField field) {
    if (field.isFinal()) {
      return false;
    }
    return Octet.shouldInstrumentClass(field.getMemberRef().asFieldReference().getType());
  }

  /** Instrument a static field? The parameter is the parent class of the static field. */
  @Inline
  @Pure
  public static boolean shouldAddAVDMetadataForStaticField(FieldReference fieldRef) {
    return Octet.shouldAddMetadataForField(fieldRef);
  }
  
  @NoInline
  @Interruptible
  public static void printAVDStats() {
    VM.sysWriteln("/***************************************************************/");
    long total = AVDStats.numNodes.report();
    long reg = AVDStats.numRegularTransactions.report();
    long unary = AVDStats.numUnaryTransactions.report();
    if (VM.VerifyAssertions) { VM._assert(total == reg + unary); }
    AVDStats.numPhase1Edges.report();
    long tmp1 = AVDStats.numPhase1Cycles.report();
    long tmp2 = AVDStats.numPhase2Iterations.report();
    // These assertions do not work since the stats variables are not updated
    // outside of harness. And quite a few cycles are actually detected then.
    //    if (VM.VerifyAssertions) {
    //      if (Octet.getConfig().performPhase2()) {
    //        VM._assert(tmp1 == AVDSCCComputation.numCyclesDetected);
    //        VM._assert(tmp1 == tmp2);
    //      }        
    //    }
    AVDStats.numTotalPhase1Instructions.report();
    AVDStats.numPhase1InstructionsLogged.report();
    AVDStats.numHashTableGrown.report();
    AVDStats.numBarrierCallsFromRegularTx.report();
    AVDStats.numBarrierCallsFromUnaryTx.report();
    VM.sysWriteln("/***************************************************************/");
  }
  
  @Inline
  public static boolean useGenerationalBarriers() {
    return Barriers.NEEDS_OBJECT_PUTFIELD_BARRIER;
  }

}
