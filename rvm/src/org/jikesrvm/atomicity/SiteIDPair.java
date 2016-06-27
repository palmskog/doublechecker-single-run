package org.jikesrvm.atomicity;

import org.vmmagic.pragma.Inline;

public final class SiteIDPair {
  private int sourceSiteID;
  private int destSiteID;
  
  public SiteIDPair() {
    sourceSiteID = AVD.UNINITIALIZED_SITE;
    destSiteID = AVD.UNINITIALIZED_SITE;
  }
  
  public SiteIDPair(int source, int dest) {
    sourceSiteID = source;
    destSiteID = dest;
  }
  
  @Inline
  public int getSourceSiteID() {
    return sourceSiteID;
  }
  
  @Inline
  public int getDestSiteID() {
    return destSiteID;
  }
  
  public void modifySiteID(int value, boolean isSource) {
    if (isSource) {
      sourceSiteID = value;
    } else {
      destSiteID = value;
    }
  }
  
}
