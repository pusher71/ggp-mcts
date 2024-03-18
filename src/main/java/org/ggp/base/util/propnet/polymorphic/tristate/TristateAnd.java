package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;

public class TristateAnd extends TristateComponent implements PolymorphicAnd
{
  public TristateAnd(TristatePropNet xiNetwork)
  {
    super(xiNetwork);
  }

  @Override
  public void changeInput(Tristate xiNewValue, int xiTurn) throws ContradictionException
  {
    if (xiNewValue == Tristate.TRUE)
    {
      mState[xiTurn].mNumTrueInputs++;
    }
    else
    {
      mState[xiTurn].mNumFalseInputs++;
    }

    if (mState[xiTurn].mValue == Tristate.UNKNOWN)
    {
      if (xiNewValue == Tristate.FALSE)
      {
        mState[xiTurn].mValue = Tristate.FALSE;
        propagateOutput(xiTurn, false);
      }
      else if (--(mState[xiTurn].mNumUnknownInputs) == 0)
      {
        mState[xiTurn].mValue = Tristate.TRUE;
        propagateOutput(xiTurn, false);
      }
    }

    checkReverseInference(xiTurn);
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

      if (xiNewValue == Tristate.TRUE)
      {
        // If TRUE, all the upstream components must be TRUE.  (This is an AND gate.)
        for (TristateComponent lInput : getInputs())
        {
          if ((xiTurn == 0) && (lInput.mState[xiTurn].mValue == Tristate.FALSE))
          {
            throw new ContradictionException();
          }
          assert(lInput.mState[xiTurn].mValue != Tristate.FALSE);
          lInput.changeOutput(Tristate.TRUE, xiTurn);
        }
      }

      checkReverseInference(xiTurn);
    }
  }

  private void checkReverseInference(int xiTurn) throws ContradictionException
  {
    // If the output of this AND gate is known to be FALSE, at least 1 input must be FALSE.  If there's exactly 1
    // UNKNOWN input and all the others are TRUE, the 1 remaining input must be FALSE.
    if ((mState[xiTurn].mValue == Tristate.FALSE) &&
        (mState[xiTurn].mNumUnknownInputs == 1) &&
        (mState[xiTurn].mNumFalseInputs == 0))
    {
      // Find the input component with UNKNOWN value.
      for (TristateComponent lInput : getInputs())
      {
        if (lInput.mState[xiTurn].mValue == Tristate.UNKNOWN)
        {
          // Back-propagate that this component must be FALSE.
          lInput.changeOutput(Tristate.FALSE, xiTurn);
        }
      }
    }
  }

}
