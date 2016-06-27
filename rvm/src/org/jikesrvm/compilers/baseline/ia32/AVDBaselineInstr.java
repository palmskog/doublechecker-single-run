package org.jikesrvm.compilers.baseline.ia32;

import org.jikesrvm.ArchitectureSpecific.Assembler;
import org.jikesrvm.ArchitectureSpecific.BaselineConstants;
import org.jikesrvm.VM;
import org.jikesrvm.atomicity.AVD;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.octet.InstrDecisions;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.unboxed.Offset;

public final class AVDBaselineInstr extends OctetBaselineInstr implements BaselineConstants {
  
  /** Instrument the beginning of a transaction.
   * @param methodID method id that is to be passed, this varies depending on whether we are instrumenting the prolog or the 
   *        call site 
   *        */
  static final void insertInstrumentationAtTransactionBegin(NormalMethod method, int biStart, Assembler asm, 
      OctetBaselineInstr octetBaselineInstr, int methodID) {    
    int params = 0;
    // pass site info
    params += octetBaselineInstr.passSite(method, biStart, asm);
    // Parameter: Method id
    asm.emitPUSH_Imm(methodID);
    params += 1; // for method id
    // make the call
    BaselineCompilerImpl.genParameterRegisterLoad(asm, params);
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.avdStartTransactionMethod.getOffset()));
  }
  
  /** Instrument the end of a transaction.
   * @param methodID method id that is to be passed, this varies depending on whether we are instrumenting the prolog or the 
   *        call site
   *        */
  static final void insertInstrumentationAtTransactionEnd(NormalMethod method, int biStart, Assembler asm, 
      OctetBaselineInstr octetBaselineInstr, int methodID) {
    int params = 0; 
    // pass site info
    params += octetBaselineInstr.passSite(method, biStart, asm);
    // Parameter: Method id
    asm.emitPUSH_Imm(methodID);
    params += 1; // for method id
    // make the call
    BaselineCompilerImpl.genParameterRegisterLoad(asm, params);
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.avdEndTransactionMethod.getOffset()));
  }     
  
  /** Pass the AVD metadata field offset (per-field), and element size for arrays. */
  @Override
  int passExtra(NormalMethod method, int biStart, boolean isField, FieldReference fieldRef, TypeReference type, GPR offsetReg, Assembler asm) {
    // param: AVD metadata offset, note that zero is a valid offset
    int avdMetadataOffset = AVD.UNINITIALIZED_OFFSET;

    // fieldRef is null for arrays
    if (AVD.addPerFieldMetadata()) {
      if (fieldRef != null && fieldRef.getResolvedField() != null) { // Field is resolved
        // Possible for fields accessed to have no Velodrome metadata offset 
        //if (VM.VerifyAssertions) { VM._assert(fieldRef.getResolvedField().hasVelodromeMetadataOffset()); }
        if (fieldRef.getResolvedField().hasAVDMetadataOffset()) {
          avdMetadataOffset = fieldRef.getResolvedField().getAVDMetadataOffset().toInt();
        }
      }
    }
    asm.emitPUSH_Imm(avdMetadataOffset);
    int param = 1; // We always pass the metadata offset 
    
    // We pass the array element size from here, so that it helps in computing array index offset
    if (!isField) { // Array type
      if (VM.VerifyAssertions) { VM._assert(AVD.instrumentArrays()); }
      if (VM.VerifyAssertions) { VM._assert(type != null && fieldRef == null); }
      asm.emitPUSH_Imm(type.getMemoryBytes()); // Can be 1,2,4
      param += 1;
    }
    return param;
  }
  
  /** Barrier for unresolved non-static fields */
  @Override
  boolean insertFieldBarrierUnresolved(NormalMethod method, int biStart, boolean isRead, Offset numSlots, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    if (shouldInstrument(method, biStart) &&
        InstrDecisions.objectOrFieldMightHaveMetadata(fieldRef) &&
        Octet.shouldInstrumentFieldAccess(fieldRef)) {
      // we can just send isResolved==true because we can get the offset out of the register "offsetReg"
      // But we are instead passing false, so that we can extract the AVD metadata offset in the barriers
      NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(method, isRead, true, false, false, false, isSpecializedMethod(method));
      // save offset value on stack
      asm.emitPUSH_Reg(offsetReg);
      // start and finish call      
      int params = startFieldBarrierUnresolvedCall(numSlots, offsetReg, fieldRef, asm);
      finishCall(method, biStart, true, fieldRef, null, offsetReg, barrierMethod, params, asm);
      // restore offset value from stack
      asm.emitPOP_Reg(offsetReg);
      return true;
    }
    return false;
  }
  
  // It is important to override this method if we are using unresolved barriers 
  @Override
  int startFieldBarrierUnresolvedCall(Offset numSlots, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    // param: object reference -- add a slot because of the push above
    asm.emitPUSH_RegDisp(SP, numSlots.plus(WORDSIZE));
    // param: field info (either field ID or field offset)
    if (InstrDecisions.passFieldInfo()) {
      // We are ignoring the fact that offsets are what the client analysis has requested for, and instead 
      // we are still passing the field id
      asm.emitPUSH_Imm(fieldRef.getId());
    } else {
      asm.emitPUSH_Imm(0);
    }
    return 2;
  }
  
}
