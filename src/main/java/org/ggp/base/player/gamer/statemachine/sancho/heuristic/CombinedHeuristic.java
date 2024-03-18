package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * Heuristic whose value comes from combining other heuristics.
 *
 * This class encapsulates the heuristic behaviour, leaving the rest of the code dealing with a single heuristic.
 */
public class CombinedHeuristic implements Heuristic
{
  private static final Logger LOGGER = LogManager.getLogger();

  private final List<Heuristic> mTuningHeuristics;
  private Heuristic[] mRuntimeHeuristics;
  private int mNumRoles;
  private HeuristicInfo mInfo = null;
  private boolean mApplyAsSimpleHeuristic = false;

  /**
   * Create a combined heuristic.
   *
   * @param xiHeuristics - the underlying heuristics.
   */
  public CombinedHeuristic(Heuristic... xiHeuristics)
  {
    mTuningHeuristics = new LinkedList<>();
    for (Heuristic lHeuristic : xiHeuristics)
    {
      LOGGER.debug("CombinedHeuristic: Adding " + lHeuristic.getClass().getSimpleName());
      mTuningHeuristics.add(lHeuristic);
    }
  }

  private CombinedHeuristic(CombinedHeuristic copyFrom)
  {
    mNumRoles = copyFrom.mNumRoles;
    mTuningHeuristics = new LinkedList<>();
    for (Heuristic lHeuristic : copyFrom.mTuningHeuristics)
    {
      mTuningHeuristics.add(lHeuristic.createIndependentInstance());
    }
    mRuntimeHeuristics = mTuningHeuristics.toArray(new Heuristic[mTuningHeuristics.size()]);
    mInfo = new HeuristicInfo(mNumRoles);
    mApplyAsSimpleHeuristic = copyFrom.mApplyAsSimpleHeuristic;
}

  /**
   * Remove all underlying heuristics
   */
  public void pruneAll()
  {
    mTuningHeuristics.clear();
  }

  @Override
  public boolean tuningInitialise(ForwardDeadReckonPropnetStateMachine xiStateMachine, RoleOrdering xiRoleOrdering)
  {
    boolean result = false;

    // Initialise all the underlying heuristics.
    for (Heuristic lHeuristic : mTuningHeuristics)
    {
      result |= lHeuristic.tuningInitialise(xiStateMachine, xiRoleOrdering);
    }

    // Remove any disabled heuristics from the list.
    prune();

    // Remember the number of roles.
    mNumRoles = xiStateMachine.getRoles().size();
    mInfo = new HeuristicInfo(mNumRoles);

    return result;
  }

  @Override
  public void tuningStartSampleGame()
  {
    // Tell all the underlying heuristics about the new game.
    for (Heuristic lHeuristic : mTuningHeuristics)
    {
      lHeuristic.tuningStartSampleGame();
    }
  }

  @Override
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState xiState,
                                       int xiChoosingRoleIndex)
  {
    // Tell all the underlying heuristics about the interim sample.
    for (Heuristic lHeuristic : mTuningHeuristics)
    {
      lHeuristic.tuningInterimStateSample(xiState, xiChoosingRoleIndex);
    }
  }

  @Override
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState xiState,
                                        int[] xiRoleScores)
  {
    // Tell all the underlying heuristics about the terminal sample.
    for (Heuristic lHeuristic : mTuningHeuristics)
    {
      lHeuristic.tuningTerminalStateSample(xiState, xiRoleScores);
    }
  }

  @Override
  public void tuningComplete()
  {
    // Tell all the underlying heuristics that tuning is complete.
    for (Heuristic lHeuristic : mTuningHeuristics)
    {
      lHeuristic.tuningComplete();
    }

    // Remove any disabled heuristics from the list.
    prune();

    // Log the final heuristics.
    Iterator<Heuristic> lIterator = mTuningHeuristics.iterator();
    if (!lIterator.hasNext())
    {
      LOGGER.info("No heuristics enabled");
    }

    while (lIterator.hasNext())
    {
      Heuristic lHeuristic = lIterator.next();
      LOGGER.info("Will use " + lHeuristic.getClass().getSimpleName());
    }

    // Convert the heuristics into an array (to avoid list iteration overheads during game-play).
    mRuntimeHeuristics = mTuningHeuristics.toArray(new Heuristic[mTuningHeuristics.size()]);
  }

  /**
   * Evaluate (based on the enabled constituents) whether the combined heuristic
   * should be applied simply or in advanced mode
   */
  public void evaluateSimplicity()
  {
    Iterator<Heuristic> lIterator = mTuningHeuristics.iterator();
    boolean foundAdvancedHeuristic = false;
    while (lIterator.hasNext())
    {
      Heuristic lHeuristic = lIterator.next();
      if ( lHeuristic.applyAsSimpleHeuristic() )
      {
        mApplyAsSimpleHeuristic = true;
      }
      else
      {
        foundAdvancedHeuristic = true;
      }
    }

    //  A mixture of constituents that want to be handled simply and in advanced
    //  mode is problematic.  Just warn for now - we plan to do a major rework of
    //  heuristics at some point anyway
    if ( foundAdvancedHeuristic && mApplyAsSimpleHeuristic )
    {
      LOGGER.warn("Combined heuristic contains both advanced and simple elements - application mechanism will be simple");
    }
    else
    {
      if ( mApplyAsSimpleHeuristic )
      {
        LOGGER.info("Using simplified heuristic mechanism");
      }
      else
      {
        LOGGER.info("Using advanced heuristic mechanism");
      }
    }
  }

  /**
   * Prune any disabled heuristics from the list.
   */
  private void prune()
  {
    Iterator<Heuristic> lIterator = mTuningHeuristics.iterator();
    while (lIterator.hasNext())
    {
      Heuristic lHeuristic = lIterator.next();
      if (!lHeuristic.isEnabled())
      {
        LOGGER.debug("CombinedHeuristic: Removing disabled " + lHeuristic.getClass().getSimpleName());
        lIterator.remove();
      }
    }
  }

  @Override
  public boolean isEnabled()
  {
    // This combined heuristic is enabled if it has any underlying heuristics left.
    return(mTuningHeuristics.size() != 0);
  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState, TreeNode xiNode)
  {
    // Tell all the underlying heuristics about the new turn.
    for (Heuristic lHeuristic : mRuntimeHeuristics)
    {
      lHeuristic.newTurn(xiState, xiNode);
    }
  }

  /**
   * Get the heuristic value for the specified state.
   *
   * @param xiState           - the state (never a terminal state).
   * @param xiPreviousState   - the previous state (can be null).
   * @param xiReferenceState  - state with which to compare to determine heuristic values
   */
  @Override
  public void getHeuristicValue(ForwardDeadReckonInternalMachineState xiState,
                                ForwardDeadReckonInternalMachineState xiPreviousState,
                                ForwardDeadReckonInternalMachineState xiReferenceState,
                                HeuristicInfo resultInfo)
  {
    double lTotalWeight = 0;
    double lMaxWeight = 0;

    resultInfo.treatAsSequenceStep = false;
    resultInfo.heuristicWeight = 0;
    for(int i = 0; i < resultInfo.heuristicValue.length; i++)
    {
      resultInfo.heuristicValue[i] = 0;
    }

    // Combine the values from the underlying heuristics by taking a weighted average.
    for (Heuristic lHeuristic : mRuntimeHeuristics)
    {
      lHeuristic.getHeuristicValue(xiState, xiPreviousState, xiReferenceState, mInfo);

      lTotalWeight += mInfo.heuristicWeight;
      for (int lii = 0; lii < mInfo.heuristicValue.length; lii++)
      {
        resultInfo.heuristicValue[lii] += (mInfo.heuristicValue[lii] * mInfo.heuristicWeight);
      }

      if (mInfo.heuristicWeight > lMaxWeight)
      {
        lMaxWeight = mInfo.heuristicWeight;
      }

      resultInfo.treatAsSequenceStep |= mInfo.treatAsSequenceStep;
    }

    if (lTotalWeight > 0)
    {
      for (int lii = 0; lii < resultInfo.heuristicValue.length; lii++)
      {
        resultInfo.heuristicValue[lii] /= lTotalWeight;
      }
    }

    // Take the maximum-weighted heuristic.
    //
    // We could do something more clever, whereby if all the heuristics were pointing in the same direction, we
    // increased the weight and if they were all pulling if different directions, we decreased the weight.
    // But hopefully this is good enough for now.
    resultInfo.heuristicWeight = lMaxWeight;
  }

  /**
   * @return whether the specified type of heuristic is included in the active list of heuristics.
   *
   * @param xiClass - the type of heuristic.
   */
  public boolean includes(Class<? extends Heuristic> xiClass)
  {
    for (Heuristic lHeuristic : mRuntimeHeuristics)
    {
      if (lHeuristic.getClass() == xiClass)
      {
        return true;
      }
    }
    return false;
  }

  @Override
  public Heuristic createIndependentInstance()
  {
    //  Return a suitable clone
    return new CombinedHeuristic(this);
  }

  @Override
  public boolean applyAsSimpleHeuristic()
  {
    return mApplyAsSimpleHeuristic;
  }
}
