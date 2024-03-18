package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;

public class ForwardDeadReckonPropositionCrossReferenceInfo extends ForwardDeadReckonPropositionInfo
{
  public ForwardDeadReckonProposition fullNetProp;
  public ForwardDeadReckonProposition xNetProp;
  public ForwardDeadReckonProposition oNetProp;
  public ForwardDeadReckonProposition goalsNetProp;
  public ForwardDeadReckonProposition terminalityNetProp;
  public Factor                       factor = null;
  //  We store the proposition ids directly for use with the fast statemachine
  //  animator.  In future this is a necessary step to allow us to free the
  //  underlying component network itself once the fast animator tables are built.
  //  For now it is just slightly more efficient
  public int                          xNetPropId;
  public int                          oNetPropId;
}
