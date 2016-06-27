package org.jikesrvm.util;

import org.jikesrvm.VM;
import org.jikesrvm.atomicity.AVDNodeList;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;

/** This is basically a queue (FIFO) implementation of AVDNodeList. In other words, a list of lists. */
@Uninterruptible
public class AVDLinkedListRVM {
  
  static class AVDLinkedListRVMNode {
    AVDNodeList list;
    AVDLinkedListRVMNode next;
  }
  
  /** Element count */
  private int count = 0;
  
  /** pointer to first element in the list */
  AVDLinkedListRVMNode head = null;
  
  /** pointer to last element in the list */
  AVDLinkedListRVMNode tail = null;
  
  /**
   *  Insert at the tail of the list
   * @param list
   * @return
   */
  public boolean add(AVDNodeList e) {
    AVDLinkedListRVMNode element = createNewLinkedListNode(); 
    if (element == null) {
      return false;
    }
    element.list = e;
    element.next = null;
    if (head == null) {
      if (VM.VerifyAssertions) { VM._assert(tail == null); }
      head = element;
    } else {
      tail.next = element;
    }
    tail = element;
    count++;
    return true;
  }

  @UninterruptibleNoWarn
  private AVDLinkedListRVMNode createNewLinkedListNode() {
    AVDLinkedListRVMNode temp;
    MemoryManager.startAllocatingInUninterruptibleCode();
    temp = new AVDLinkedListRVMNode();
    MemoryManager.stopAllocatingInUninterruptibleCode();
    return temp;
  }
  
  /**
   * Discard all entries in the list
   */
  public void clear() {
    head = tail = null;
    count = 0;
  }
  
  /** Test if the list is empty.
   * @return
   */
  public boolean isEmpty() {
    boolean flag = count == 0;
    if (flag) {
      if (VM.VerifyAssertions) { VM._assert(tail == null); }
    }
    return flag;
  }
  
  /** Returns the current size of the list
   * @return
   */
  public int size() {
    return count;
  }
  
  /** Removes the first element from the list
   * @return
   */
  public AVDNodeList remove() {
    if (head == null) {
      return null;
    } else if (head == tail) {
      AVDLinkedListRVMNode temp = head;
      if (VM.VerifyAssertions) { VM._assert(temp.next == null); }
      head = null;
      tail = null;
      return temp.list;
    } else {
      AVDLinkedListRVMNode temp = head;
      head = head.next;
      if (VM.VerifyAssertions) { VM._assert(head != null); }
      return temp.list;
    }
  }
  
}
