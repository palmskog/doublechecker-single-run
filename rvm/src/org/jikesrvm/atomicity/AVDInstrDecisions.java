package org.jikesrvm.atomicity;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.runtime.Entrypoints;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class AVDInstrDecisions implements Constants
{
  
  static NormalMethod chooseAVDBarrier(boolean isRead, boolean isField, boolean isResolved, boolean isStatic) {
    if (isField) { // scalar fields and statics
      if (isRead) {
        if (isResolved) {
          if (isStatic) {
            return Entrypoints.avdFieldReadBarrierStaticResolvedMethod;
          } else { // Non-static
            return Entrypoints.avdFieldReadBarrierResolvedMethod;
          }
        } else { // Unresolved
          if (isStatic) {
            return Entrypoints.avdFieldReadBarrierStaticUnresolvedMethod;
          } else { // Non-static
            return Entrypoints.avdFieldReadBarrierUnresolvedMethod;
          }          
        }
      } else { // Write
        if (isResolved) {
          if (isStatic) {
            return Entrypoints.avdFieldWriteBarrierStaticResolvedMethod;
          } else { // Non-static
            return Entrypoints.avdFieldWriteBarrierResolvedMethod;
          }
        } else { // Unresolved
          if (isStatic) {
            return Entrypoints.avdFieldWriteBarrierStaticUnresolvedMethod;
          } else { // Non-static
            return Entrypoints.avdFieldWriteBarrierUnresolvedMethod;
          }
        }
      }
    } else { // Arrays
      if (VM.VerifyAssertions) { VM._assert(isResolved); } // Array accesses can't be unresolved 
      if (isRead) {
        return Entrypoints.avdArrayReadBarrierMethod;        
      } else {
        return Entrypoints.avdArrayWriteBarrierMethod;
      }
    }
  }
  
  public static final boolean staticFieldHasAVDMetadata(RVMField field) {
    if (AVD.addPerFieldMetadata()) {
      boolean hasMetadata = field.hasAVDMetadataOffset();
      // at least for now, we expect the metadata to provide the same result as for an unresolved field,
      // except that the metadata should also be avoiding final fields
      if (VM.VerifyAssertions) { VM._assert(hasMetadata == (staticFieldMightHaveAVDMetadata(field.getMemberRef().asFieldReference()) && !field.isFinal())); }
      return hasMetadata;
    } 
    return false;
  }

  public static boolean staticFieldMightHaveAVDMetadata(FieldReference fieldRef) {
    if (AVD.addPerFieldMetadata()) {
      return AVD.shouldAddAVDMetadataForStaticField(fieldRef);
    } else {
      return false;
    }
  }
  
  @Inline
  public static boolean objectOrFieldHasMetadata(RVMField field) {
    return !field.isFinal() /*&& objectOrFieldMightHaveMetadata(field.getMemberRef().asFieldReference())*/; 
  }

  @Inline
  private static boolean objectOrFieldMightHaveMetadata(FieldReference asFieldReference) {
    return AVD.addPerFieldMetadata();
  }
  
}