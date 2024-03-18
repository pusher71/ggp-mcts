package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;

public class TristateNot extends TristateComponent implements PolymorphicNot
{
  public TristateNot(TristatePropNet xiNetwork)
  {
    super(xiNetwork);
  }

  @Override
  public void changeInput(Tristate xiNewValue, int xiTurn) throws ContradictionException
  {
    if (mState[xiTurn].mValue == Tristate.UNKNOWN)
    {
      if (xiNewValue == Tristate.FALSE)
      {
        mState[xiTurn].mValue = Tristate.TRUE;
      }
      else
      {
        mState[xiTurn].mValue = Tristate.FALSE;
      }
      propagateOutput(xiTurn, false);
    }
  }

  @Override
  public void changeOutput(Tristate xiNewValue, int xiTurn) throws ContradictionException
  {
    assert(xiNewValue != Tristate.UNKNOWN);

    if (mState[xiTurn].mValue == Tristate.UNKNOWN)
    {
      // We've learned our output value from downstream.
      mState[xiTurn].mValue = xiNewValue;

      // Tell any other downstream components.
      propagateOutput(xiTurn, false);

      // Tell our single upstream component.
      assert(getSingleInput().mState[xiTurn].mValue != xiNewValue);
      getSingleInput().changeOutput(xiNewValue == Tristate.TRUE ? Tristate.FALSE : Tristate.TRUE, xiTurn);
    }
  }

}
