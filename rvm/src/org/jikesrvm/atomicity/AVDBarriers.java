package org.jikesrvm.atomicity;

import static org.jikesrvm.ia32.StackframeLayoutConstants.INVISIBLE_METHOD_ID;
import static org.jikesrvm.ia32.StackframeLayoutConstants.STACKFRAME_SENTINEL_FP;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.octet.AVDStateTransfers;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.octet.OctetBarriers;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;

// Inlining will happen first in FastAdaptive, and all the assertions would get inlined. They would get removed later.
// But it still effectively burdens the opt compiler. Ideally, it would be good to have separate barriers for performance
// and for debugging.
@Uninterruptible
public final class AVDBarriers implements Constants {
  
  @Entrypoint 
  public static final void fieldReadBarrierResolved(Object obj, int fieldOffset, int siteID, int avdOffset) {
    // AVD offset can be negative for statics and which for arrays since it is in the Misc header
    //if (VM.VerifyAssertions && AVD.addPerFieldMetadata()) { VM._assert(avdOffset >= 0); }  
    AVDStateTransfers.readResolved(ObjectReference.fromObject(obj).toAddress(), MiscHeader.OCTET_OFFSET, 
        fieldOffset, avdOffset, siteID);
    //if (VM.VerifyAssertions && AVD.verifyAVDBarrierCall()) { VM._assert(verifyAVDBarrierCall()); }
  }

  @Entrypoint
  public static final void fieldWriteBarrierResolved(Object obj, int fieldOffset, int siteID, int avdOffset) {
    // AVD offset can be negative for statics and which for arrays since it is in the Misc header
    //if (VM.VerifyAssertions && AVD.addPerFieldMetadata()) { VM._assert(avdOffset >= 0); }  
    AVDStateTransfers.writeResolved(ObjectReference.fromObject(obj).toAddress(), MiscHeader.OCTET_OFFSET, 
        fieldOffset, avdOffset, siteID);
    //if (VM.VerifyAssertions && AVD.verifyAVDBarrierCall()) { VM._assert(verifyAVDBarrierCall()); }
  }

  @Entrypoint 
  public static final void fieldReadBarrierStaticResolved(Offset octetOffset, int fieldOffset, int siteID, int avdOffset) {
    AVDStateTransfers.readResolved(Magic.getJTOC(), octetOffset, fieldOffset, avdOffset, siteID);
    //if (VM.VerifyAssertions && AVD.verifyAVDBarrierCall()) { VM._assert(verifyAVDBarrierCall()); }
  }

  @Entrypoint
  public static final void fieldWriteBarrierStaticResolved(Offset octetOffset, int fieldOffset, int siteID, int avdOffset) {
    AVDStateTransfers.writeResolved(Magic.getJTOC(), octetOffset, fieldOffset, avdOffset, siteID);
    //if (VM.VerifyAssertions && AVD.verifyAVDBarrierCall()) { VM._assert(verifyAVDBarrierCall()); }
  }
  
  // Octet: TODO: field might not actually be resolved here if inserted in the opt compiler during early instrumentation!
  
  // Octet: TODO: can we resolve a field?  is that interruptible?  how does that work in Jikes?
  
  @Entrypoint
  public static final void fieldReadBarrierUnresolved(Object o, int fieldID, int siteID, int avdOffset) {
    AVD_readUnresolvedField(o, fieldID, siteID, avdOffset);
    //if (VM.VerifyAssertions && AVD.verifyAVDBarrierCall()) { VM._assert(verifyAVDBarrierCall()); }
  }

  @Inline
  public static final void AVD_readUnresolvedField(Object obj, int fieldID, int siteID, int avdOffset) {
    int fieldOffset = OctetBarriers.getFieldInfo(fieldID);
    RVMField field = OctetBarriers.handleUnresolvedField(fieldID);
    if (field.hasAVDMetadataOffset()) {
      avdOffset = field.getAVDMetadataOffset().toInt();
      // AVD offset can be negative for statics and which for arrays since it is in the Misc header
      //if (VM.VerifyAssertions) { VM._assert(avdOffset >= 0); }
    }
    
    AVDStateTransfers.readUnresolved(ObjectReference.fromObject(obj).toAddress(), MiscHeader.OCTET_OFFSET, 
        fieldOffset, avdOffset, siteID, field.hasAVDMetadataOffset());
  }
  
  @Entrypoint
  public static final void fieldWriteBarrierUnresolved(Object o, int fieldID, int siteID, int avdOffset) {
    AVD_writeUnresolvedField(o, fieldID, siteID, avdOffset);
    //if (VM.VerifyAssertions && AVD.verifyAVDBarrierCall()) { VM._assert(verifyAVDBarrierCall()); }
  }
  
  @Inline
  public static final void AVD_writeUnresolvedField(Object obj, int fieldID, int siteID, int avdOffset) {
    int fieldOffset = OctetBarriers.getFieldInfo(fieldID);
    RVMField field = OctetBarriers.handleUnresolvedField(fieldID);
    if (field.hasAVDMetadataOffset()) {
      avdOffset = field.getAVDMetadataOffset().toInt();
      // AVD offset can be negative for statics and which for arrays since it is in the Misc header
      //if (VM.VerifyAssertions) { VM._assert(avdOffset >= 0); }
    }
    
    AVDStateTransfers.writeUnresolved(ObjectReference.fromObject(obj).toAddress(), MiscHeader.OCTET_OFFSET, 
        fieldOffset, avdOffset, siteID, field.hasAVDMetadataOffset());
  }
  
  @Entrypoint
  public static final void fieldReadBarrierStaticUnresolved(int fieldID, int siteID, int avdOffset) {
    RVMField field = OctetBarriers.handleUnresolvedField(fieldID);
    if (field.hasMetadataOffset()) { // Octet metadata check
      int fieldOffset = OctetBarriers.getFieldInfo(field, fieldID);
      if (AVD.addPerFieldMetadata()) {
        //if (VM.VerifyAssertions) { VM._assert(field.hasAVDMetadataOffset()); }
        avdOffset = field.getAVDMetadataOffset().toInt();
      }
      
      AVDStateTransfers.readUnresolved(Magic.getJTOC(), field.getMetadataOffset(), fieldOffset, avdOffset, siteID,
          field.hasAVDMetadataOffset());
      //if (VM.VerifyAssertions && AVD.verifyAVDBarrierCall()) { VM._assert(verifyAVDBarrierCall()); }
    }
  }
  
  @Entrypoint
  public static final void fieldWriteBarrierStaticUnresolved(int fieldID, int siteID, int avdOffset) {
    RVMField field = OctetBarriers.handleUnresolvedField(fieldID);
    if (field.hasMetadataOffset()) { // Octet metadata check
      int fieldOffset = OctetBarriers.getFieldInfo(field, fieldID);
      if (AVD.addPerFieldMetadata()) {
        //if (VM.VerifyAssertions) { VM._assert(field.hasAVDMetadataOffset()); }
        avdOffset = field.getAVDMetadataOffset().toInt();
      }
      
      AVDStateTransfers.writeUnresolved(Magic.getJTOC(), field.getMetadataOffset(), fieldOffset, avdOffset, siteID, 
          field.hasAVDMetadataOffset());
      //if (VM.VerifyAssertions && AVD.verifyAVDBarrierCall()) { VM._assert(verifyAVDBarrierCall()); }
    }
  }
  
  @Entrypoint
  public static final void arrayReadBarrier(Object obj, int arrayIndex, int siteID, int avdOffset, int arrayElementSize) {
    //if (VM.VerifyAssertions) { VM._assert(AVD.instrumentArrays()); }

    int arraySlotOffset = Offset.fromIntSignExtend(arrayIndex * arrayElementSize).toInt();
    AVDStateTransfers.readResolved(ObjectReference.fromObject(obj).toAddress(), MiscHeader.OCTET_OFFSET, arraySlotOffset, 
        MiscHeader.AVD_ARRAY_OFFSET.toInt(), siteID);
  }
  
  @Entrypoint
  public static final void arrayWriteBarrier(Object obj, int arrayIndex, int siteID, int avdOffset, int arrayElementSize) {
    //if (VM.VerifyAssertions) { VM._assert(AVD.instrumentArrays()); }

    int arraySlotOffset = Offset.fromIntSignExtend(arrayIndex * arrayElementSize).toInt();
    AVDStateTransfers.writeResolved(ObjectReference.fromObject(obj).toAddress(), MiscHeader.OCTET_OFFSET, arraySlotOffset, 
        MiscHeader.AVD_ARRAY_OFFSET.toInt(), siteID);
  }
  
  @NoInline
  public static final boolean verifyAVDBarrierCall() {
    if (VM.VerifyAssertions) { VM._assert(AVD.verifyAVDBarrierCall()); }
    RVMThread currentThread = RVMThread.getCurrentThread();
    if (VM.VerifyAssertions) { VM._assert(currentThread.isOctetThread()); }
    Address fp = Magic.getFramePointer();
    
    // Search for the topmost application frame/method
    while (Magic.getCallerFramePointer(fp).NE(STACKFRAME_SENTINEL_FP)) {
      int compiledMethodId = Magic.getCompiledMethodID(fp);
      if (compiledMethodId != INVISIBLE_METHOD_ID) {
        CompiledMethod compiledMethod = CompiledMethods.getCompiledMethod(compiledMethodId);
        RVMMethod method = compiledMethod.getMethod();
        if (!method.isNative() && Octet.shouldInstrumentMethod(method)) {
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
              } else {
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
          break;
        }
      }
      fp = Magic.getCallerFramePointer(fp);
    }
    return true;
  }
  
}
