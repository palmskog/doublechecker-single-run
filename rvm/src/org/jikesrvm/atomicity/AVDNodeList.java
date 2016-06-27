package org.jikesrvm.atomicity;

import org.vmmagic.pragma.Uninterruptible;

/**
 * This class is used to store dependent node information. This class is basically a list with insertions 
 * at the front of the list. Initially, starts with a dummy node.
 */
@Uninterruptible
public class AVDNodeList {
  
  public int description;
  public AVDNode node;
  public AVDNodeList next;

  public static final AVDNodeList initialValue = new AVDNodeList();

  /** Represents a dummy node. */
  private AVDNodeList() { // Sentinel
    node = null;
    description = -1; 
    next = null;
  }

  /** This is the ctor that is to be called when you want to create an outgoing edge */
  public AVDNodeList(AVDNode dest, int type) {
    description = type;
    node = dest;
    next = null;
  }
  
}
