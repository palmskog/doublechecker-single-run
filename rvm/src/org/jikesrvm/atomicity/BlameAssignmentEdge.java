package org.jikesrvm.atomicity;

import java.util.ArrayList;

import org.jikesrvm.VM;
import org.vmmagic.pragma.Inline;

public final class BlameAssignmentEdge {

  /** Represents order in which the edge was created */
  private int edgeNumber; 
  private final AVDNode destNode;
  private ArrayList<SiteIDPair> sites;
  
  public BlameAssignmentEdge(AVDNode node, int number) {
    this(node, number, null);
  }
  
  public BlameAssignmentEdge(AVDNode node, int number, ArrayList<SiteIDPair> pair) {
    destNode = node;
    edgeNumber = number;
    sites = pair;
  }
  
  public BlameAssignmentEdge(AVDNode node, int number, int sourceID, int destID) {
    if (VM.VerifyAssertions) { VM._assert(node != null); }
    destNode = node;
    edgeNumber = number;
    SiteIDPair pair = new SiteIDPair(sourceID, destID);
    if (VM.VerifyAssertions) { VM._assert(sites == null); }
    sites = new ArrayList<SiteIDPair>();
    sites.add(pair);
  }  
  
  @Inline
  public int getEdgeNumber() {
    return edgeNumber;
  }
  
  @Inline
  public AVDNode getDestNode() {
    return destNode;
  }
  
  @Inline
  public void setEdgeNumber(int value) {
    edgeNumber = value;
  }
  
  @Inline
  public ArrayList<SiteIDPair> getSitesList() {
    return sites;
  }
  
  public void addSites(int sourceSiteID, int destSiteID) {
    if (VM.VerifyAssertions) { VM._assert(sites != null); }
    SiteIDPair pair = new SiteIDPair(sourceSiteID, destSiteID);
    sites.add(pair);
  }
  
}
