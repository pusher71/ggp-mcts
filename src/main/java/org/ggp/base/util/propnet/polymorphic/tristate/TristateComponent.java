package org.ggp.base.util.propnet.polymorphic.tristate;

import java.io.Writer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;

/**
 * The abstract parent of all tri-state components.
 */
public abstract class TristateComponent implements PolymorphicComponent
{
  /**
   * Possible values of a tri-state component.
   */
  public enum Tristate
  {
    /**
     * Componnent is known to be true.
     */
    TRUE,

    /**
     * Component is known to be false.
     */
    FALSE,

    /**
     * Component value is unknown.
     */
    UNKNOWN;
  }

  /**
   * The state of a component on any given turn.
   */
  protected static class State
  {
    /**
     * The component's value - one of true/false/unknown.
     */
    protected Tristate mValue;

    /**
     * The number of input values that are UNKNOWN.
     */
    protected int mNumUnknownInputs;

    /**
     * The number of input values that are known to be TRUE.  (Only tracked for AND/OR which are the only multi-input
     * components.)
     */
    protected int mNumTrueInputs;

    /**
     * The number of input values that are known to be FALSE.  (Only tracked for AND/OR which are the only multi-input
     * components.)
     */
    protected int mNumFalseInputs;
  }

  /**
   * The propnet of which this component is a part.
   */
  protected final TristatePropNet mParent;

  /**
   * The input and output components connected to this one.
   */
  private final Set<TristateComponent> mInputs = new HashSet<>();
  private final Set<TristateComponent> mOutputs = new HashSet<>();

  /**
   * The state on the specified turn.  Turn 1 is the turn under consideration.  Turn 0 is the turn before and turn 2 is
   * the turn after.
   */
  protected State[] mState = new State[3];
  {
    mState[0] = new State();
    mState[1] = new State();
    mState[2] = new State();
  }

  //////////////////////////////////////////////////
  // Network use methods.
  //////////////////////////////////////////////////

  /**
   * Reset the component to its default state.
   */
  public void reset()
  {
    for (int ii = 0; ii < 3; ii++)
    {
      mState[ii].mValue = Tristate.UNKNOWN;
      mState[ii].mNumUnknownInputs = mInputs.size();
      mState[ii].mNumTrueInputs = 0;
      mState[ii].mNumFalseInputs = 0;
    }
  }

  /**
   * Inform a component that one of its inputs has changed (from UNKNOWN to the value specified here).
   *
   * @param xiNewValue - the new value, either TRUE or FALSE.
   * @param xiTurn - the turn (0 for previous, 1 for current, 2 for next).
   */
  public abstract void changeInput(Tristate xiNewValue, int xiTurn) throws ContradictionException;

  /**
   * Inform a component that its output value has changed - i.e. back propagation.
   *
   * @param xiNewValue - the new value, either TRUE or FALSE.
   * @param xiTurn - the turn (0 for previous, 1 for current, 2 for next).
   */
  public abstract void changeOutput(Tristate xiNewValue, int xiTurn) throws ContradictionException;

  /**
   * Propagate a change in output state through the network.  Components MUST NOT call this method unless their output
   * has actually changed.  Components must set mValue before calling this method.
   *
   * @param xiTurn - the turn for the component doing the propagation.
   * @param xiIncrement - whether to increment the turn for the next component(s).
   */
  protected void propagateOutput(int xiTurn, boolean xiIncrement) throws ContradictionException
  {
    for (TristateComponent lOutput : mOutputs)
    {
      lOutput.changeInput(mState[xiTurn].mValue, xiTurn + (xiIncrement ? 1 : 0));
    }
  }

  //////////////////////////////////////////////////
  // Network construction methods.
  //////////////////////////////////////////////////
  protected TristateComponent(TristatePropNet xiNetwork)
  {
    mParent = xiNetwork;
  }

  @Override
  public void addInput(PolymorphicComponent xiInput)
  {
    mInputs.add((TristateComponent)xiInput);
  }

  @Override
  public void addOutput(PolymorphicComponent xiOutput)
  {
    mOutputs.add((TristateComponent)xiOutput);
  }

  @Override
  public Collection<? extends TristateComponent> getInputs()
  {
    return mInputs;
  }

  @Override
  public TristateComponent getSingleInput()
  {
    return mInputs.iterator().next();
  }

  @Override
  public Collection<? extends TristateComponent> getOutputs()
  {
    return mOutputs;
  }

  @Override
  public TristateComponent getSingleOutput()
  {
    return mOutputs.iterator().next();
  }

  @Override
  public boolean getValue()
  {
    throw new RuntimeException("Can't call getValue() on tri-state component");
  }

  public Tristate getValue(int xiTurn)
  {
    return mState[xiTurn].mValue;
  }

  @Override
  public void renderAsDot(Writer xiOutput)
  {
    throw new RuntimeException("Not implemented");
  }

  @Override
  public void removeInput(PolymorphicComponent xiInput)
  {
    mInputs.remove(xiInput);
  }

  @Override
  public void removeAllInputs()
  {
    mInputs.clear();
  }

  @Override
  public void removeOutput(PolymorphicComponent xiOutput)
  {
    mOutputs.remove(xiOutput);
  }

  @Override
  public void removeAllOutputs()
  {
    mOutputs.clear();
  }

  @Override
  public void crystalize()
  {
    throw new RuntimeException("Not implemented");
  }

  @Override
  public void setSignature(long xiSignature)
  {
    throw new RuntimeException("Not implemented");
  }

  @Override
  public long getSignature()
  {
    throw new RuntimeException("Not implemented");
  }

  /**
   *  Exception thrown when a contradiction is arrived at during latch testing.
   */
  @SuppressWarnings("serial")
  public static class ContradictionException extends Exception
  {
    // Just an exception.  No extra detail.
  }
}
