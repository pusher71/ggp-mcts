package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

public class TristateTransition extends TristateComponent implements PolymorphicTransition
{
  public TristateTransition(TristatePropNet xiNetwork)
  {
    super(xiNetwork);
  }

  @Override
  public void changeInput(Tristate xiNewValue, int xiTurn) throws ContradictionException
  {
    // Only propagate through a transition if we haven't done so already.  We're only interested in the immediate
    // consequences of assuming some input proposition(s).
    if ((mState[xiTurn].mValue == Tristate.UNKNOWN) && xiNewValue != Tristate.UNKNOWN && (xiTurn < 2))
    {
      mState[xiTurn].mValue = xiNewValue;
      propagateOutput(xiTurn, true);
    }
  }

  @Override
  public void changeOutput(Tristate xiNewValue, int xiTurn) throws ContradictionException
  {
    assert(xiNewValue != Tristate.UNKNOWN);

    if (--xiTurn < 0)
    {
      // Don't keep going back forever.
      return;
    }

    if (mState[xiTurn].mValue == Tristate.UNKNOWN)
    {
      // We've learned our output value from downstream.
      mState[xiTurn].mValue = xiNewValue;

      // Tell any other downstream components.
      propagateOutput(xiTurn, true);

      // Tell the (single) upstream component.
      getSingleInput().changeOutput(xiNewValue, xiTurn);
    }
  }

}
