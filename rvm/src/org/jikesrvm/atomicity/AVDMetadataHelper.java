package org.jikesrvm.atomicity;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Word;

@Uninterruptible
public final class AVDMetadataHelper implements Constants {

  // This bit distribution means that we cannot use AVD for workload sizes other than "small"
  // for the DaCapo benchmarks
  private static final int NUM_BITS_TO_STORE_ACCESS_TYPE = 1;
  private static final int NUM_BITS_TO_STORE_THREADID = 6;
  private static final int NUM_BITS_TO_STORE_BPNUM = 25;
  private static final int NUM_BITS_TO_SHIFT_FOR_ACCESS_TYPE = NUM_BITS_TO_STORE_THREADID + NUM_BITS_TO_STORE_BPNUM;
  
  static {
    if (VM.VerifyAssertions) { VM._assert(NUM_BITS_TO_STORE_ACCESS_TYPE + NUM_BITS_TO_SHIFT_FOR_ACCESS_TYPE == BITS_IN_WORD); }
  }
  
  // Write: MSB is 1 (set), 0x80000000
  public static final Word ACCESS_TYPE_BIT_MASK = Word.one().lsh(NUM_BITS_TO_STORE_THREADID + NUM_BITS_TO_STORE_BPNUM);
  // 0x7E000000
  private static final Word THREADID_BIT_MASK = Word.one().lsh(NUM_BITS_TO_STORE_THREADID).minus(Word.one()).lsh(NUM_BITS_TO_STORE_BPNUM);
  private static final int MAX_OCTET_THREADID = 1 << NUM_BITS_TO_STORE_THREADID;
  // 0x01FFFFFF
  private static final Word BPCOUNT_BIT_MASK = Word.one().lsh(NUM_BITS_TO_STORE_BPNUM).minus(Word.one());
  private static final int MAX_BPCOUNT_VALUE = 1 << NUM_BITS_TO_STORE_BPNUM;
  // 0111...111, i.e., 0x7FFFFFFF
  static final Word THREADID_BPCOUNT_MASK = Word.one().lsh(NUM_BITS_TO_SHIFT_FOR_ACCESS_TYPE).minus(Word.one());
  
  /** true = write, false = read */
  @Inline
  public static boolean getAccessType(Word metadata) {
    return !metadata.and(ACCESS_TYPE_BIT_MASK).isZero();
  }
  
  @Inline
  public static int getThreadID(Word metadata) {
    if (VM.VerifyAssertions) { VM._assert(metadata.and(THREADID_BIT_MASK).rsha(NUM_BITS_TO_STORE_BPNUM).toInt() <= MAX_OCTET_THREADID); }
    return metadata.and(THREADID_BIT_MASK).rsha(NUM_BITS_TO_STORE_BPNUM).toInt();
  }
  
  @Inline
  public static int getBPNumber(Word metadata) {
    // Check if 25 bits are sufficient
    if (VM.VerifyAssertions) { VM._assert(metadata.and(BPCOUNT_BIT_MASK).toInt() <= MAX_BPCOUNT_VALUE); }
    return metadata.and(BPCOUNT_BIT_MASK).toInt();
  }
  
  @Inline
  public static Word createMetadata(RVMThread thread, int bpNum, boolean isRead) {
    if (VM.VerifyAssertions) { VM._assert(thread.octetThreadID <= MAX_OCTET_THREADID); }
    if (VM.VerifyAssertions) { VM._assert(bpNum <= MAX_BPCOUNT_VALUE); } // Check if 25 bits are sufficient
    Word newMetadata = Word.fromIntSignExtend(bpNum);
    newMetadata = newMetadata.or(Word.fromIntSignExtend(thread.octetThreadID).lsh(NUM_BITS_TO_STORE_BPNUM));
    if (!isRead) {
      newMetadata = newMetadata.xor(ACCESS_TYPE_BIT_MASK);
    }
    return newMetadata;
  }
  
  /** The thread id is already encoded in {@code bpNum} */
  @Inline
  public static Word updateMetadata(RVMThread thread, int bpNum, boolean isRead) {
    Word newMetadata = Word.fromIntSignExtend(bpNum);
    if (!isRead) {
      newMetadata = newMetadata.xor(ACCESS_TYPE_BIT_MASK);
    }
    return newMetadata;
  }
  
}
