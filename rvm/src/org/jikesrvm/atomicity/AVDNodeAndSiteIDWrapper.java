package org.jikesrvm.atomicity;

import org.jikesrvm.VM;

public class AVDNodeAndSiteIDWrapper {

  private AVDNode node;
  private int siteID;
  
  public AVDNodeAndSiteIDWrapper() {
    node = null;
    siteID = -3; // -1/-2 are used
  }
  
  public AVDNodeAndSiteIDWrapper(AVDNode n, int site) {
    node = n;
    siteID = site;
  }
  
  public AVDNode getAVDNode() {
    return node;
  }
  
  public int getSiteID() {
    return siteID;
  }
  
  public void setSiteID(int value) {
    siteID = value;
  }
  
  public void setAVDNode(AVDNode n) {
    if (VM.VerifyAssertions) { VM._assert(n != null); }
    node = n;
  }
  
}
