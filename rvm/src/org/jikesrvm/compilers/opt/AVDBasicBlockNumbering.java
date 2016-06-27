package org.jikesrvm.compilers.opt;

import java.util.Enumeration;
import java.util.HashSet;

import org.jikesrvm.VM;
import org.jikesrvm.atomicity.AVD;
import org.jikesrvm.compilers.opt.driver.CompilerPhase;
import org.jikesrvm.compilers.opt.ir.BasicBlock;
import org.jikesrvm.compilers.opt.ir.IR;

public final class AVDBasicBlockNumbering extends CompilerPhase {

  public AVDBasicBlockNumbering(boolean before) {
  }
  
  @Override
  public String getName() {
    return "AVD check basic block numbering";
  }

  @Override
  public boolean shouldPerform(OptOptions options) {
    if (AVD.checkBasicBlockNumbering()) {
      return true;
    }
    return false;
  }
  
  // Let's just reuse the previous instance of this class
  @Override
  public CompilerPhase newExecution(IR ir) {
    return this;
  }
  
  @Override
  public void perform(IR ir) {
    prepareDFSColors(ir);
    HashSet<Integer> labels = new HashSet<Integer>();
    dfsCFG(ir.cfg.entry(), ir, labels);
  }
  
  private void prepareDFSColors(IR ir) {
    BasicBlock bb;
    for (Enumeration<BasicBlock> e = ir.getBasicBlocks(); e.hasMoreElements(); ) {
      bb = e.nextElement();
      bb.scratch = 0; // Mark white
    }
  }
  
  private void dfsCFG(BasicBlock bb, IR ir, HashSet<Integer> labels) {
    bb.scratch = 1; // Mark gray
    Integer in = new Integer(bb.getNumber());
    if (labels.contains(in)) {
      VM.sysWriteln("Duplicate number for method", ir.getMethod().getName());
      VM.sysFail("Failing");
    } else {
      labels.add(in);
    }
    Enumeration<BasicBlock> successors = bb.getOutNodes();
    while (successors.hasMoreElements()) {
      BasicBlock succBB = successors.nextElement();
      if (succBB.scratch == 0){
        dfsCFG(succBB, ir, labels);
      }
    }
    bb.scratch = 2; // Mark black
  }

}
