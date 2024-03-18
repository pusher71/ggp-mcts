package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;

public class TristateOr extends TristateComponent implements PolymorphicOr
{
  public TristateOr(TristatePropNet xiNetwork)
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
      if (xiNewValue == Tristate.TRUE)
      {
        mState[xiTurn].mValue = Tristate.TRUE;
        propagateOutput(xiTurn, false);
      }
      else if (--(mState[xiTurn].mNumUnknownInputs) == 0)
      {
        mState[xiTurn].mValue = Tristate.FALSE;
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

      // If FALSE, all the upstream components must be FALSE.  (This is an OR gate.)
      if (xiNewValue == Tristate.FALSE)
      {
        for (TristateComponent lInput : getInputs())
        {
          if ((xiTurn == 0) && (lInput.mState[xiTurn].mValue == Tristate.TRUE))
          {
            throw new ContradictionException();
          }
          assert(lInput.mState[xiTurn].mValue != Tristate.TRUE);
          lInput.changeOutput(Tristate.FALSE, xiTurn);
        }
      }

      checkReverseInference(xiTurn);
    }
  }

  private void checkReverseInference(int xiTurn) throws ContradictionException
  {
    // If the output of this OR gate is known to be TRUE, at least 1 input must be TRUE.  If there's exactly 1
    // UNKNOWN input and all the others are FALSE, the 1 remaining input must be TRUE.
    if ((mState[xiTurn].mValue == Tristate.TRUE) &&
        (mState[xiTurn].mNumUnknownInputs == 1) &&
        (mState[xiTurn].mNumTrueInputs == 0))
    {
      // Find the input component with UNKNOWN value.
      for (TristateComponent lInput : getInputs())
      {
        if (lInput.mState[xiTurn].mValue == Tristate.UNKNOWN)
        {
          // Back-propagate that this component must be TRUE.
          lInput.changeOutput(Tristate.TRUE, xiTurn);
        }
      }
    }
  }

}
