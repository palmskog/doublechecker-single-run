/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.mm.mmtk;

import org.jikesrvm.SizeConstants;
import org.jikesrvm.atomicity.AVD;
import org.jikesrvm.atomicity.AVDPhase2Thread;
import org.jikesrvm.atomicity.ReadWriteLog;
import org.jikesrvm.classloader.Atom;
import org.jikesrvm.classloader.RVMArray;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.mm.mminterface.DebugUtil;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.mm.mminterface.Selected;
import org.jikesrvm.objectmodel.JavaHeaderConstants;
import org.jikesrvm.objectmodel.TIB;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.mmtk.plan.CollectorContext;
import org.mmtk.plan.ParallelCollector;
import org.mmtk.plan.Plan;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.generational.immix.GenImmixCollector;
import org.mmtk.policy.Space;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

@Uninterruptible public final class ObjectModel extends org.mmtk.vm.ObjectModel implements org.mmtk.utility.Constants,
                                                                                           org.jikesrvm.Constants {

  @Override
  protected Offset getArrayBaseOffset() { return JavaHeaderConstants.ARRAY_BASE_OFFSET; }

  @Override
  @Inline
  public ObjectReference copy(ObjectReference from, int allocator) {
    TIB tib = org.jikesrvm.objectmodel.ObjectModel.getTIB(from);

    // AVD: Included this assertion
    if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(tib != null); }

    RVMType type = Magic.objectAsType(tib.getType());

    if (type.isClassType())
      return copyScalar(from, tib, type.asClass(), allocator);
    else
      return copyArray(from, tib, type.asArray(), allocator);
  }

  @Inline
  private ObjectReference copyScalar(ObjectReference from, TIB tib, RVMClass type, int allocator) {
    int bytes = org.jikesrvm.objectmodel.ObjectModel.bytesRequiredWhenCopied(from.toObject(), type);
    int align = org.jikesrvm.objectmodel.ObjectModel.getAlignment(type, from.toObject());
    int offset = org.jikesrvm.objectmodel.ObjectModel.getOffsetForAlignment(type, from);
    CollectorContext context = VM.activePlan.collector();
    allocator = context.copyCheckAllocator(from, bytes, align, allocator);
    Address region = MemoryManager.allocateSpace(context, bytes, align, offset,
                                                allocator, from);
    Object toObj = org.jikesrvm.objectmodel.ObjectModel.moveObject(region, from.toObject(), bytes, type);
    ObjectReference to = ObjectReference.fromObject(toObj);
    context.postCopy(to, ObjectReference.fromObject(tib), bytes, allocator);
    return to;
  }

  @Inline
  private ObjectReference copyArray(ObjectReference from, TIB tib, RVMArray type, int allocator) {
    int elements = Magic.getArrayLength(from.toObject());
    int bytes = org.jikesrvm.objectmodel.ObjectModel.bytesRequiredWhenCopied(from.toObject(), type, elements);
    int align = org.jikesrvm.objectmodel.ObjectModel.getAlignment(type, from.toObject());
    int offset = org.jikesrvm.objectmodel.ObjectModel.getOffsetForAlignment(type, from);
    CollectorContext context = VM.activePlan.collector();
    allocator = context.copyCheckAllocator(from, bytes, align, allocator);
    Address region = MemoryManager.allocateSpace(context, bytes, align, offset,
                                                allocator, from);
    Object toObj = org.jikesrvm.objectmodel.ObjectModel.moveObject(region, from.toObject(), bytes, type);
    ObjectReference to = ObjectReference.fromObject(toObj);
    context.postCopy(to, ObjectReference.fromObject(tib), bytes, allocator);
    if (type == RVMType.CodeArrayType) {
      // sync all moved code arrays to get icache and dcache in sync
      // immediately.
      int dataSize = bytes - org.jikesrvm.objectmodel.ObjectModel.computeHeaderSize(Magic.getObjectType(toObj));
      org.jikesrvm.runtime.Memory.sync(to.toAddress(), dataSize);
    }
    return to;
  }

  /**
   * Return the size of a given object, in bytes
   *
   * @param object The object whose size is being queried
   * @return The size (in bytes) of the given object.
   */
  static int getObjectSize(ObjectReference object) {
    TIB tib = org.jikesrvm.objectmodel.ObjectModel.getTIB(object);
    RVMType type = Magic.objectAsType(tib.getType());

    if (type.isClassType())
      return org.jikesrvm.objectmodel.ObjectModel.bytesRequiredWhenCopied(object.toObject(), type.asClass());
    else
      return org.jikesrvm.objectmodel.ObjectModel.bytesRequiredWhenCopied(object.toObject(), type.asArray(), Magic.getArrayLength(object.toObject()));
  }

  /**
   * @param region The start (or an address less than) the region that was reserved for this object.
   */
  @Override
  @Inline
  public Address copyTo(ObjectReference from, ObjectReference to, Address region) {
    TIB tib = org.jikesrvm.objectmodel.ObjectModel.getTIB(from);
    RVMType type = tib.getType();
    int bytes;

    boolean copy = (from != to);

    if (copy) {
      if (type.isClassType()) {
        RVMClass classType = type.asClass();
        bytes = org.jikesrvm.objectmodel.ObjectModel.bytesRequiredWhenCopied(from.toObject(), classType);
        org.jikesrvm.objectmodel.ObjectModel.moveObject(from.toObject(), to.toObject(), bytes, classType);
      } else {
      RVMArray arrayType = type.asArray();
        int elements = Magic.getArrayLength(from.toObject());
        bytes = org.jikesrvm.objectmodel.ObjectModel.bytesRequiredWhenCopied(from.toObject(), arrayType, elements);
        org.jikesrvm.objectmodel.ObjectModel.moveObject(from.toObject(), to.toObject(), bytes, arrayType);
      }
    } else {
      bytes = getCurrentSize(to);
    }

    Address start = org.jikesrvm.objectmodel.ObjectModel.objectStartRef(to);
    Allocator.fillAlignmentGap(region, start);

    return start.plus(bytes);
  }

  @Override
  public ObjectReference getReferenceWhenCopiedTo(ObjectReference from, Address to) {
    return ObjectReference.fromObject(org.jikesrvm.objectmodel.ObjectModel.getReferenceWhenCopiedTo(from.toObject(), to));
  }

  @Override
  public Address getObjectEndAddress(ObjectReference object) {
    return org.jikesrvm.objectmodel.ObjectModel.getObjectEndAddress(object.toObject());
  }

  @Override
  public int getSizeWhenCopied(ObjectReference object) {
    return org.jikesrvm.objectmodel.ObjectModel.bytesRequiredWhenCopied(object.toObject());
  }

  @Override
  public int getAlignWhenCopied(ObjectReference object) {
    TIB tib = org.jikesrvm.objectmodel.ObjectModel.getTIB(object);
    RVMType type = tib.getType();
    if (type.isArrayType()) {
      return org.jikesrvm.objectmodel.ObjectModel.getAlignment(type.asArray(), object.toObject());
    } else {
      return org.jikesrvm.objectmodel.ObjectModel.getAlignment(type.asClass(), object.toObject());
    }
  }

  @Override
  public int getAlignOffsetWhenCopied(ObjectReference object) {
    TIB tib = org.jikesrvm.objectmodel.ObjectModel.getTIB(object);
    RVMType type = tib.getType();
    if (type.isArrayType()) {
      return org.jikesrvm.objectmodel.ObjectModel.getOffsetForAlignment(type.asArray(), object);
    } else {
      return org.jikesrvm.objectmodel.ObjectModel.getOffsetForAlignment(type.asClass(), object);
    }
  }

  @Override
  public int getCurrentSize(ObjectReference object) {
    return org.jikesrvm.objectmodel.ObjectModel.bytesUsed(object.toObject());
  }

  @Override
  public ObjectReference getNextObject(ObjectReference object) {
    return org.jikesrvm.objectmodel.ObjectModel.getNextObject(object);
  }

  @Override
  public ObjectReference getObjectFromStartAddress(Address start) {
    return org.jikesrvm.objectmodel.ObjectModel.getObjectFromStartAddress(start);
  }

  @Override
  public byte [] getTypeDescriptor(ObjectReference ref) {
    Atom descriptor = Magic.getObjectType(ref).getDescriptor();
    return descriptor.toByteArray();
  }

  @Override
  @Inline
  public int getArrayLength(ObjectReference object) {
    return Magic.getArrayLength(object.toObject());
  }

  @Override
  public boolean isArray(ObjectReference object) {
    return org.jikesrvm.objectmodel.ObjectModel.getObjectType(object.toObject()).isArrayType();
  }

  @Override
  public boolean isPrimitiveArray(ObjectReference object) {
    Object obj = object.toObject();
    return (obj instanceof long[]   ||
            obj instanceof int[]    ||
            obj instanceof short[]  ||
            obj instanceof byte[]   ||
            obj instanceof double[] ||
            obj instanceof float[]);
  }

  /**
   * Tests a bit available for memory manager use in an object.
   *
   * @param object the address of the object
   * @param idx the index of the bit
   */
  public boolean testAvailableBit(ObjectReference object, int idx) {
    return org.jikesrvm.objectmodel.ObjectModel.testAvailableBit(object.toObject(), idx);
  }

  /**
   * Sets a bit available for memory manager use in an object.
   *
   * @param object the address of the object
   * @param idx the index of the bit
   * @param flag <code>true</code> to set the bit to 1,
   * <code>false</code> to set it to 0
   */
  public void setAvailableBit(ObjectReference object, int idx,
                                     boolean flag) {
    org.jikesrvm.objectmodel.ObjectModel.setAvailableBit(object.toObject(), idx, flag);
  }

  @Override
  public boolean attemptAvailableBits(ObjectReference object,
                                             Word oldVal, Word newVal) {
    return org.jikesrvm.objectmodel.ObjectModel.attemptAvailableBits(object.toObject(), oldVal,
                                               newVal);
  }

  @Override
  public Word prepareAvailableBits(ObjectReference object) {
    return org.jikesrvm.objectmodel.ObjectModel.prepareAvailableBits(object.toObject());
  }

  @Override
  public void writeAvailableByte(ObjectReference object, byte val) {
    org.jikesrvm.objectmodel.ObjectModel.writeAvailableByte(object.toObject(), val);
  }

  @Override
  public byte readAvailableByte(ObjectReference object) {
    return org.jikesrvm.objectmodel.ObjectModel.readAvailableByte(object.toObject());
  }

  @Override
  public void writeAvailableBitsWord(ObjectReference object, Word val) {
    org.jikesrvm.objectmodel.ObjectModel.writeAvailableBitsWord(object.toObject(), val);
  }

  @Override
  public Word readAvailableBitsWord(ObjectReference object) {
    return org.jikesrvm.objectmodel.ObjectModel.readAvailableBitsWord(object.toObject());
  }

  /* AJG: Should this be a variable rather than method? */
  @Override
  public Offset GC_HEADER_OFFSET() {
    return org.jikesrvm.objectmodel.ObjectModel.GC_HEADER_OFFSET;
  }

  @Override
  @Inline
  public Address objectStartRef(ObjectReference object) {
    return org.jikesrvm.objectmodel.ObjectModel.objectStartRef(object);
  }

  @Override
  public Address refToAddress(ObjectReference object) {
    return org.jikesrvm.objectmodel.ObjectModel.getPointerInMemoryRegion(object);
  }

  @Override
  @Inline
  public boolean isAcyclic(ObjectReference typeRef) {
    TIB tib = Magic.addressAsTIB(typeRef.toAddress());
    RVMType type = tib.getType();
    return type.isAcyclicReference();
  }

  @Override
  public void dumpObject(ObjectReference object) {
    DebugUtil.dumpRef(object);
  }
  
  // AVD: Changes for implementing treating read-write set entries as either strong/weak references
  
  private void checkLiveness(ObjectReference objRef, Address addr, Word value) {
    TraceLocal trace = ((ParallelCollector) VM.activePlan.collector()).getCurrentTrace();
    if (!trace.isLive(objRef)) { // Reference is dead
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(objRef.toAddress().NE(AVD.JTOC_ADDRESS));
        if (traceReadWriteSetAsStrongReferences()) {
          VM.assertions._assert(false, "Should not die with strong reference tracing enabled");
        }
      }
      value = ReadWriteLog.setGCCycleNumber(value, MemoryManager.getGCCount());
      addr.store(value, Offset.fromIntZeroExtend(SizeConstants.BYTES_IN_WORD));
    } else { // The reference object is live
      ObjectReference newReference = trace.getForwardedReference(objRef);
      VM.activePlan.global().storeObjectReference(addr, newReference);
    }
  }
  
  /** Allocate a new word array of size numWords words in AVD space. */
  public Object createAVDSpace(int numWords) {
    TypeReference tref = TypeReference.WordArray;
    RVMType type = tref.peekType();
    RVMArray array = type.asArray();
    int align = org.jikesrvm.objectmodel.ObjectModel.getAlignment(array);
    int offset = org.jikesrvm.objectmodel.ObjectModel.getOffsetForAlignment(array, false);
    int bytes = numWords * SizeConstants.BYTES_IN_WORD;
    bytes = org.jikesrvm.runtime.Memory.alignUp(bytes, MIN_ALIGNMENT);
    TIB tib = array.getTypeInformationBlock();
    int header = org.jikesrvm.objectmodel.ObjectModel.computeArrayHeaderSize(array);
    Selected.Mutator mutator = Selected.Mutator.get();
    Address newArray = mutator.createAVDSpace(bytes + header, align, offset);
    if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(newArray != null); }
    Object object = org.jikesrvm.objectmodel.ObjectModel.initializeArray(newArray, tib, numWords, bytes + header);
    mutator.postAlloc(ObjectReference.fromObject(object), ObjectReference.fromObject(tib), bytes + header, Plan.ALLOC_AVD_READ_WRITE_SPACE);
    return object;
  }

  @Inline
  public int getGCCollectionNumber() {
    return MemoryManager.getGCCount();
  }
  
  @Inline
  public final boolean traceReadWriteSetAsStrongReferences() {
    return AVD.traceReadWriteSetAsStrongReferences();
  }
  
	// Dump status of Octet threads
  public void printOctetThreadsInformation() {
    org.jikesrvm.VM.sysWriteln();
    org.jikesrvm.VM.sysWriteln("***********************AVD: START DUMPING OCTET THREADS INFORMATION***************************");
    for (int i = RVMThread.numThreads-1; i >= 0; i--) {
      Magic.sync();
      RVMThread t = RVMThread.threads[i];
      if (t.isOctetThread()) {
        org.jikesrvm.VM.sysWrite("OCTET THREAD ID:", t.octetThreadID);
        org.jikesrvm.VM.sysWrite(", No. of nodes: ", t.numberOfNodes);
        if (t.lastTransToPerformWrExToRdEx != null) {
          org.jikesrvm.VM.sysWriteln(", RVMThread:LastWrExToRdEx:TRANS", t.lastTransToPerformWrExToRdEx.transactionID);
        } else {
          org.jikesrvm.VM.sysWriteln();
        }
      }
    }
    if (AVD.lastTransToUpdateGlobalRdShCounter !=  null) {
      org.jikesrvm.VM.sysWriteln("AVD lastTransToUpdateGlobalRdShCounter:THREAD", AVD.lastTransToUpdateGlobalRdShCounter.octetThread.octetThreadID,
          "TRANS", AVD.lastTransToUpdateGlobalRdShCounter.transactionID);
    }
    RVMThread.dumpOctetThreads();
    org.jikesrvm.VM.sysWriteln("***********************END DUMPING OCTET THREADS INFORMATION***************************");
  }
  
  /** Expect this to be executed per-thread. The same thread shouldn't be processed more than once. 
   *  The threads are added to the queue for both nursery and full heap GCs. */
  // AVD: TODO: What about already terminated Octet threads? Is it meaningful to process them again?  
  // Doesn't scanThread() already handle it for us?
  public void addOctetThread(int threadSlot) {
    RVMThread thread = RVMThread.threadBySlot[threadSlot];
    ObjectReference objRef = ObjectReference.fromObject(thread);
    ((GenImmixCollector) VM.activePlan.collector()).avdOctetThreadsQueue.push(objRef);
    ((GenImmixCollector) VM.activePlan.collector()).avdOctetThreadsQueue.flushLocal();
    if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(((GenImmixCollector) VM.activePlan.collector()).avdOctetThreadsQueue.isFlushed()); }
  }
 
  /** {@code objRef} is supposed to point to an Octet thread. 
   *  This is executed for both nursery and full heap GCs. */
  @Override
  public void processOneOctetThread(ObjectReference threadRef) {
    if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(!threadRef.isNull()); }
    RVMThread thread = (RVMThread) threadRef.toObject();
    if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(thread.isOctetThread()); }
    TraceLocal trace = ((ParallelCollector) VM.activePlan.collector()).getCurrentTrace();
    
    // Test for liveness of two RVMThread references
    ObjectReference objRef = ObjectReference.fromObject(thread.lastTransToPerformWrExToRdEx);
    if (!objRef.isNull()) {
      Address addr = ObjectReference.fromObject(thread).toAddress().plus(Entrypoints.avdLastTransToPerformWrExToRdExField.getOffset());
      ObjectReference newRef;
      if (!trace.isLive(objRef)) { // Reference is dead
        newRef = ObjectReference.nullReference();
      } else {
        newRef = trace.getForwardedReference(objRef);
      }
      VM.activePlan.global().storeObjectReference(addr, newRef);
    } 
    
    // At present, we are ignoring checking for the following reference, since its used is mostly local 
//    objRef = ObjectReference.fromObject(thread.penultimateTransToUpdateGlobalRdShCounter);
//    if (!objRef.isNull()) {
//      if (!trace.isLive(objRef)) { // Reference is dead
//        VM.activePlan.global().storeObjectReference(objRef.toAddress(), ObjectReference.nullReference());
//      } else {
//        ObjectReference newRef = trace.getForwardedReference(objRef);
//        VM.activePlan.global().storeObjectReference(objRef.toAddress(), newRef);
//      }
//    }
    
    // The read/write logs are processed only during full heap GCs and if read/write logs are enabled
    if (!AVD.logRdWrAccesses() || VM.activePlan.global().isCurrentGCNursery()) {
      return;
    }
    traceLogBufferReferenceArray(thread, trace);
    traceWordArraysPerThread(thread, trace);
  }
  
  // Check for liveness of the global references that are part of AVD
  public void traceAVDGlobalReference() {
    ObjectReference objRef = ObjectReference.fromObject(AVD.lastTransToUpdateGlobalRdShCounter);
    if (!objRef.isNull()) {
      TraceLocal trace = ((ParallelCollector) VM.activePlan.collector()).getCurrentTrace();
      Address addr = Magic.getJTOC().plus(Entrypoints.avdLastTransToUpdateGlobalRdShCounter.getOffset());
      ObjectReference newRef;
      if (!trace.isLive(objRef)) { // Reference is dead
        newRef = ObjectReference.nullReference();
      } else {
        newRef = trace.getForwardedReference(objRef);
      }
      VM.activePlan.global().storeObjectReference(addr, newRef);
    }
  }
  
  private void traceLogBufferReferenceArray(RVMThread thread, TraceLocal trace) {
    //org.jikesrvm.VM.sysWriteln("traceLogBufferReferenceArray: Octet Thread id:", thread.octetThreadID);
    ObjectReference logArray = ObjectReference.fromObject(thread.logBufferReferenceArray);
    if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(MemoryManager.validRef(logArray)); }
    if (logArray.isNull()) { // GC can be triggered from RVMThread ctor(), before logBufferReferenceArray is initialized
      return;
    }

    Address logStart = logArray.toAddress();

    // AVD: TODO: Can potentially optimize the tracing by breaking if the first live WordArray buffer is found

    /* Some rules
     * 1) If only WordArray reference, keep live
     * 2) All WordArray references after the first live one should be kept live, i.e., 
     *    if ith reference is live ==> (i+1)th reference is live
     */
    if (thread.logBufferReferenceArrayIndex == 0) { // There is only WordArray reference, got to keep it alive
      trace.processEdge(logArray, logStart);
    } else {
      boolean atleastOneShouldBeLive = false;
      Address logEnd = logStart.plus((thread.logBufferReferenceArrayIndex + 1) << SizeConstants.LOG_BYTES_IN_ADDRESS);
      for (Address addr = logStart; addr.LT(logEnd); addr = addr.plus(SizeConstants.BYTES_IN_ADDRESS)) {
        ObjectReference objRef = addr.loadObjectReference();
        if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(MemoryManager.validRef(objRef)); }
        if (objRef.isNull()) {
          continue;
        }
        if (VM.VERIFY_ASSERTIONS) { 
          // objRef should point to an WordArray in AVD space
          VM.assertions._assert(Space.isInSpace(Plan.AVD_READ_WRITE_SPACE, objRef));
          WordArray temp = (WordArray) objRef.toObject(); // Test class cast exception
        }
        if (!trace.isLive(objRef) && !atleastOneShouldBeLive) { 
          // AVD: TODO: Seems no WordArrays are getting nulled out here
          addr.store(ObjectReference.nullReference());
        }  else {
          trace.processEdge(logArray, addr);
          atleastOneShouldBeLive = true;
        }
      }
      if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(atleastOneShouldBeLive); }
    }
  }
  
  private void traceWordArraysPerThread(RVMThread thread, TraceLocal trace) {
    if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(thread.isOctetThread()); }
    Address logStart = ObjectReference.fromObject(thread.logBufferReferenceArray).toAddress();
    if (logStart.EQ(Address.zero())) { // GC can be triggered from RVMThread ctor(), where logBufferReferenceArray can still be null
      return;
    }
    Address logEnd = logStart.plus((thread.logBufferReferenceArrayIndex + 1) << SizeConstants.LOG_BYTES_IN_ADDRESS);
    for (Address wAddr = logStart; wAddr.LT(logEnd); wAddr = wAddr.plus(SizeConstants.BYTES_IN_ADDRESS)) {
      ObjectReference waRef = VM.activePlan.global().loadObjectReference(wAddr);
      if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(MemoryManager.validRef(waRef)); }
      if (waRef.isNull()) {
        // The following assertion should hold in all cases except one, where GC is invoked from within RVMThread 
        // ctor before the first WordArray has been allocated and set in the logBufferReferenceArray
        //if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(t.logBufferReferenceArrayIndex > 0); }
        continue;
      }
      if (VM.VERIFY_ASSERTIONS) { 
        VM.assertions._assert(Space.isInSpace(Plan.AVD_READ_WRITE_SPACE, waRef)); // objRef should point to an WordArray in AVD space
        WordArray temp = (WordArray) waRef.toObject(); // Test class cast exception
      }
      
      Address waStart = waRef.toAddress();
      Address waEnd = waStart.plus(ReadWriteLog.LOG_BUFFER_SLOTS << ReadWriteLog.LOG_CELL_SIZE_IN_BYTES);
      for (Address waSlot = waStart; waSlot.LT(waEnd); waSlot = waSlot.plus(ReadWriteLog.CELL_SIZE_IN_BYTES)){
        ObjectReference objRef = VM.activePlan.global().loadObjectReference(waSlot);
        // This assertion is not valid for many reasons. Objects may already die, and the reference may no longer 
        // point to a valid object
        //if (VM.VERIFY_ASSERTIONS) { VM.assertions._assert(MemoryManager.validRefNew(objRef, k));}
        
        if (objRef.toAddress().EQ(AVD.JTOC_ADDRESS)) { // Statics are always expected to be alive
          continue;
        }
        if (objRef.isNull()) { // Null references indicate empty slots
          break;               
        }
        
        // Get the second word
        Word value = waSlot.loadWord(Offset.fromIntZeroExtend(SizeConstants.BYTES_IN_WORD));
        if (ReadWriteLog.getGCCycleNumber(value) == 0) { // Not already marked dead earlier
          checkLiveness(objRef, waSlot, value);
        } else { // Already dead, nothing to do
          // Ensure that gc cycle number is set
          if (VM.VERIFY_ASSERTIONS) {
            if (traceReadWriteSetAsStrongReferences()) {
              VM.assertions._assert(false, "Should not die with strong reference tracing enabled");
            } else {
              VM.assertions._assert(ReadWriteLog.getGCCycleNumber(value) > 0);
              // AVD: TODO: Shouldn't only < work? It should if we avoid data race where multiple collector threads
              // repeatedly process the same Octet threads.
              VM.assertions._assert(ReadWriteLog.getGCCycleNumber(value) <= MemoryManager.getGCCount());
            }
          }
        }
      }
    }
  }
  
  @Inline
  public boolean isAVDPhase2Enabled() {
    return AVD.performPhase2();
  }
  
  @Inline
  public boolean isAVDPhase2ThreadRunning() {
    return (AVDPhase2Thread.isRunning);
  }

}
