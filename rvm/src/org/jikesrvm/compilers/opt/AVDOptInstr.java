package org.jikesrvm.compilers.opt;

import org.jikesrvm.VM;
import org.jikesrvm.atomicity.AVD;
import org.jikesrvm.atomicity.BenchmarkInfo;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.compilers.opt.driver.OptConstants;
import org.jikesrvm.compilers.opt.ir.ALoad;
import org.jikesrvm.compilers.opt.ir.AStore;
import org.jikesrvm.compilers.opt.ir.Call;
import org.jikesrvm.compilers.opt.ir.IR;
import org.jikesrvm.compilers.opt.ir.IRTools;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.compilers.opt.ir.Operators;
import org.jikesrvm.compilers.opt.ir.Prologue;
import org.jikesrvm.compilers.opt.ir.Return;
import org.jikesrvm.compilers.opt.ir.operand.LocationOperand;
import org.jikesrvm.compilers.opt.ir.operand.MethodOperand;
import org.jikesrvm.compilers.opt.ir.operand.Operand;
import org.jikesrvm.octet.InstrDecisions;
import org.jikesrvm.octet.Site;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.util.HashSetRVM;

/** AVD needs to instrument additional instructions in opt compiler. */
public class AVDOptInstr extends OctetOptInstr implements Operators, OptConstants {

  public AVDOptInstr(boolean late, RedundantBarrierRemover redundantBarrierRemover) {
    super(late, redundantBarrierRemover);
  }
  
  /** AVD wants to instrument few additional instructions over Octet */
  @Override
  public void instrumentOtherInstTypes(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    // position attribute may not always be set for non-read/write instructions, so cannot directly use
    // OctetOptSelection::shouldInstrumentInstPosition(), but then again getSite() will fail if position is not set
    
    if (OctetOptSelection.shouldInstrumentInstPosition(inst, ir)) {
      if (AVD.methodsAsTransactions() || (AVD.syncBlocksAsTransactions() && inst.position.getMethod().isSynchronized())) {
        NormalMethod method = inst.position.getMethod();
        
        if (Prologue.conforms(inst)) { // Method entry
          // Insert the debug method first
          if (AVD.checkMethodContextAtProlog() && Context.isApplicationPrefix(method.getDeclaringClass().getTypeRef())) {
            insertVerifyApplicationContext(inst);
          }
          // Insert at all prologs, this call instruction should get sandwiched between
          // the prolog and the "just above" context debug call 
          if (Context.isTRANSContext(method.getStaticContext()) && Context.isNONTRANSContext(method.getResolvedContext())) {
            instrumentTransactionEntry(inst, callsToInline, ir);
          }
        } else if (Return.conforms(inst)) { // Method exit
          if (Context.isTRANSContext(method.getStaticContext()) && Context.isNONTRANSContext(method.getResolvedContext())) {
            instrumentTransactionExit(inst, callsToInline, ir);
          }
        } 

      }
      if (AVD.syncBlocksAsTransactions()) {
        if (inst.getOpcode() == MONITORENTER_opcode) { // Monitor entry
          instrumentTransactionEntry(inst, callsToInline, ir);
        } else if (inst.getOpcode() == MONITOREXIT_opcode) { // Monitor exit
          instrumentTransactionExit(inst, callsToInline, ir);
        }
      }
    }
  }
  
  /**
   * Insert instrumentation at method entry, or monitor entry. This is per prolog.
   * @param inst
   * @param callsToInline
   * @param ir
   */
  void instrumentTransactionEntry(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    int methodID = inst.position.getMethod().getId();
    int siteID = InstrDecisions.passSite() ? Site.getSite(inst) : 0;
    NormalMethod barrierMethod = Entrypoints.avdStartTransactionMethod;
    Instruction barrierCall = Call.create2(CALL, 
                                            null, 
                                            IRTools.AC(barrierMethod.getOffset()), 
                                            MethodOperand.STATIC(barrierMethod), 
                                            IRTools.IC(siteID),
                                            IRTools.IC(methodID));
    barrierCall.bcIndex = inst.bcIndex;
    barrierCall.position = inst.position;
    inst.insertAfter(barrierCall); // Insert after the prolog instruction
    if (AVD.inlineStartEndTransactions()) {
      inlineAVDInstrs(barrierCall, inst, callsToInline);
    }
  }
  
  /** Insert call to debug method to verify contexts */
  void insertVerifyApplicationContext(Instruction inst) {
    NormalMethod barrierMethod = Entrypoints.avdcheckMethodContextAtPrologMethod;
    Instruction barrierCall = Call.create0(CALL, null, IRTools.AC(barrierMethod.getOffset()), MethodOperand.STATIC(barrierMethod));
    barrierCall.bcIndex = inst.bcIndex;
    barrierCall.position = inst.position;
    inst.insertAfter(barrierCall);
  }
  
  // Inline if using Jikes inliner
  private void inlineAVDInstrs(Instruction barrierCall, Instruction inst, HashSetRVM<Instruction> callsToInline) {
    // Don't inline for Xalan9, it is very sensitive to the compilation time overheads
    if (AVD.bench.getId() == BenchmarkInfo.XALAN9 || AVD.bench.getId() == BenchmarkInfo.MONTECARLO) {
      return;
    }
    // AVD: LATER: Is it necessary to check for frequency?
    if (inliningType == InliningType.JIKES_INLINER && !inst.getBasicBlock().getInfrequent()) {
      callsToInline.add(barrierCall);
    }    
  }

  /**
   * Insert instrumentation before method exit, monitor exit
   * @param inst
   * @param callsToInline
   * @param ir
   */
  void instrumentTransactionExit(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    int methodID = inst.position.getMethod().getId();
    int siteID = InstrDecisions.passSite() ? Site.getSite(inst) : 0;
    NormalMethod barrierMethod = Entrypoints.avdEndTransactionMethod;
    Instruction barrierCall = Call.create2(CALL, 
                                            null, 
                                            IRTools.AC(barrierMethod.getOffset()), 
                                            MethodOperand.STATIC(barrierMethod), 
                                            IRTools.IC(siteID),
                                            IRTools.IC(methodID));
    barrierCall.bcIndex = inst.bcIndex;
    barrierCall.position = inst.position;
    inst.insertBefore(barrierCall); // Insert before the return instruction
    if (AVD.inlineStartEndTransactions()) {
      inlineAVDInstrs(barrierCall, inst, callsToInline);
    }
  }
  
  /** Pass the element size only for arrays */
  @Override
  void passExtra(Instruction inst, FieldReference fieldRef, Instruction barrier) {
    // param: AVD metadata offset, note that zero is a valid offset
    int avdMetadataOffset = AVD.UNINITIALIZED_OFFSET;
    if (AVD.addPerFieldMetadata()) {
      // fieldRef is null for arrays
      if (fieldRef != null && fieldRef.getResolvedField() != null) {
        RVMField field = fieldRef.getResolvedField();
        if (field.hasAVDMetadataOffset()) {
          avdMetadataOffset = field.getAVDMetadataOffset().toInt();
        }
      }
    }
    addParam(barrier, IRTools.IC(avdMetadataOffset));

    if (fieldRef == null) { // fieldRef is null for array accesses
      boolean isRead = ALoad.conforms(inst);
      LocationOperand loc = isRead ? ALoad.getLocation(inst) : AStore.getLocation(inst);
      if (VM.VerifyAssertions) { VM._assert(loc.isArrayAccess()); }
      Operand ref = isRead ? ALoad.getArray(inst) : AStore.getArray(inst);
      TypeReference elemType = ref.getType().getArrayElementType();
      addParam(barrier, IRTools.IC(elemType.getMemoryBytes()));
    }
  }
  
}
