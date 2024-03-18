package org.ggp.base.util.propnet.polymorphic.tristate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;

public class TristateProposition extends TristateComponent implements PolymorphicProposition
{
  private static final Logger LOGGER = LogManager.getLogger();

  private GdlSentence mName;

  public TristateProposition(TristatePropNet xiNetwork, GdlSentence xiName)
  {
    super(xiNetwork);
    mName = xiName;
  }

  @Override
  public GdlSentence getName()
  {
    return mName;
  }

  @Override
  public void setName(GdlSentence xiNewName)
  {
    mName = xiNewName;
  }

  @Override
  public void setValue(boolean xiValue)
  {
    throw new RuntimeException("Use isLatch instead");
  }

  @Override
  public void changeInput(Tristate xiNewValue, int xiTurn) throws ContradictionException
  {
    assert(xiNewValue != Tristate.UNKNOWN);

    if (mState[xiTurn].mValue == Tristate.UNKNOWN)
    {
      LOGGER.trace(mName + " has become " + xiNewValue + " in turn " + xiTurn + " by forward inference");

      mState[xiTurn].mValue = xiNewValue;
      propagateOutput(xiTurn, false);

      // If this is a LEGAL prop and it has become FALSE, set the corresponding DOES prop to FALSE in the SAME turn.
      if (mState[xiTurn].mValue == Tristate.FALSE)
      {
        TristateProposition lDoes = (TristateProposition)mParent.getLegalInputMap().get(this);
        if ((lDoes != null) && (xiTurn < 2))
        {
          lDoes.changeInput(Tristate.FALSE, xiTurn);
        }
      }
    }
  }

  @Override
  public void changeOutput(Tristate xiNewValue, int xiTurn) throws ContradictionException
  {
    assert(xiNewValue != Tristate.UNKNOWN);

    if (mState[xiTurn].mValue == Tristate.UNKNOWN)
    {
      LOGGER.trace(mName + " has become " + xiNewValue + " in turn " + xiTurn + " by backward inference");

      // We've learned our output value from downstream.
      mState[xiTurn].mValue = xiNewValue;

      // Tell any other downstream components.
      propagateOutput(xiTurn, false);

      // Tell the (single) upstream component (if any - DOES propositions don't have inputs).
      if (getInputs().size() == 1)
      {
        getSingleInput().changeOutput(xiNewValue, xiTurn);
      }
    }
  }

  public void assume(Tristate xiPrevious, Tristate xiCurrent, Tristate xiFuture) throws ContradictionException
  {
    assumeStateInTurn(xiPrevious, 0);
    assumeStateInTurn(xiCurrent,  1);
    assumeStateInTurn(xiFuture,   2);
  }

  private void assumeStateInTurn(Tristate xiValue, int xiTurn) throws ContradictionException
  {
    boolean lPropagationRequired = false;

    // Determine whether propagation is required (and whether we already have a contradiction).

    if (xiValue == Tristate.TRUE)
    {
      // If we already know the value is FALSE, it's a contradiction to assume it's TRUE.
      if (mState[xiTurn].mValue == Tristate.FALSE)
      {
        throw new ContradictionException();
      }

      // If we already know the value is TRUE, there's nothing to propagate.  Otherwise there is.
      lPropagationRequired = (mState[xiTurn].mValue == Tristate.UNKNOWN);
    }

    if (xiValue == Tristate.FALSE)
    {
      // If we already know the value is TRUE, it's a contradiction to assume it's FALSE.
      if (mState[xiTurn].mValue == Tristate.TRUE)
      {
        throw new ContradictionException();
      }

      // If we already know the value is FALSE, there's nothing to propagate.  Otherwise there is.
      lPropagationRequired = (mState[xiTurn].mValue == Tristate.UNKNOWN);
    }

    // Propagate if required.
    if (lPropagationRequired)
    {
      // Set the requested state.
      mState[xiTurn].mValue = xiValue;

      // Forwards.
      propagateOutput(xiTurn, false);

      // Backwards.
      getSingleInput().changeOutput(mState[xiTurn].mValue, xiTurn);
    }
  }

  /**
   * Test whether this proposition is a latch proposition.
   *
   * @param xiValue - the value to test.
   * @return whether the proposition is a latch (for the specified value).
   */
  public boolean isLatch(boolean xiValue)
  {
    LOGGER.debug("Checking whether " + getName() + " is a " + (xiValue ? "+" : "-") + "ve latch");

    try
    {
      assume(xiValue ? Tristate.FALSE : Tristate.TRUE,
             xiValue ? Tristate.TRUE : Tristate.FALSE,
             Tristate.UNKNOWN);
    }
    catch(ContradictionException e)
    {
      LOGGER.debug("Contradiction in latch detection - so discounting as potential latch");
      return false;
    }

    // Check whether a latch has been found.  We're only interested if the latch has the sense we were looking for.
    // (If a true value in this turn forces a false value in the next and false forces true, then this could be a turn
    // marker, but we aren't currently interested.)
    if (mState[2].mValue == (xiValue ? Tristate.TRUE : Tristate.FALSE))
    {
      return true;
    }

    return false;
  }
}
