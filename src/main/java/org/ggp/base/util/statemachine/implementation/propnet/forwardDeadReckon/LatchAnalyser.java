package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.PackedData;
import org.ggp.base.player.gamer.statemachine.sancho.RuntimeGameCharacteristics;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.MaskedStateGoalLatch;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateComponent;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateComponent.ContradictionException;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateComponent.Tristate;
import org.ggp.base.util.propnet.polymorphic.tristate.TristatePropNet;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateProposition;
import org.ggp.base.util.statemachine.Role;

/**
 * Latch analysis.
 *
 * Users should call analyse() once and then discard the latch analyser.
 */
@SuppressWarnings("synthetic-access")
public class LatchAnalyser
{
  private static final Logger LOGGER = LogManager.getLogger();

  // Results of latch analysis.
  private final Latches mLatches;

  // Temporary state, used during analysis.
  private final ForwardDeadReckonPropnetStateMachine mStateMachine;
  private final ForwardDeadReckonPropNet mSourceNet;

  private final TristatePropNet mTristateNet;
  private final Map<PolymorphicComponent, TristateComponent> mSourceToTarget;

  private final Set<ForwardDeadReckonProposition> mPositiveBaseLatches;
  private final Set<ForwardDeadReckonProposition> mNegativeBaseLatches;
  private final List<MaskedStateGoalLatch> mComplexPositiveGoalLatchList;
  private long mDeadline;

  /**
   * Latch analysis results.
   *
   * All public methods are thread-safe and read-only.
   */
  public static class Latches implements LatchResults
  {
    // Private variables are largely manipulated by the enclosing LatchAnalyser class.  Once an instance of this class
    // has been returned by the latch analyser, the only possible access is through the public methods of this class.
    //
    // WARNING: This class is saved in the game characteristics file.  If adding new state to this class, take care that
    //          the saving and loading function remains back-compatible.
    private boolean mAnalysisComplete;
    private boolean mFoundPositiveBaseLatches;
    private boolean mFoundNegativeBaseLatches;
    private boolean mFoundSimplePositiveGoalLatches;
    private boolean mFoundSimpleNegativeGoalLatches;
    private boolean mFoundComplexPositiveGoalLatches;
    private boolean mAllRolesHavePositiveGoalLatches;
    private final ForwardDeadReckonInternalMachineState mPositiveBaseLatchMask;
    private final ForwardDeadReckonInternalMachineState mNegativeBaseLatchMask;
    private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mSimplePositiveGoalLatches;
    private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mSimpleNegativeGoalLatches;
    private MaskedStateGoalLatch[] mComplexPositiveGoalLatches;
    private final Map<Role, ForwardDeadReckonInternalMachineState> mPerRolePositiveGoalLatchMasks;
    private transient final Map<Role,int[]> mStaticGoalRanges;
    private transient final List<Role> mRoles;
    // WARNING: This class is saved in the game characteristics file.  If adding new state to this class, take care that
    //          the saving and loading function remains back-compatible.

    /**
     * Create a set of latch analysis results.
     *
     * @param xiSourceNet - the source propnet.  Will NOT be referenced after the constructor returns.
     * @param xiStateMachine - the state machine.  Will NOT be referenced after the constructor returns.
     */
    public Latches(ForwardDeadReckonPropNet xiSourceNet,
                   ForwardDeadReckonPropnetStateMachine xiStateMachine)
    {
      // Create empty mappings for latched base propositions.
      mPositiveBaseLatchMask = xiStateMachine.createEmptyInternalState();
      mNegativeBaseLatchMask = xiStateMachine.createEmptyInternalState();

      // Create mappings for goal latches.
      mSimplePositiveGoalLatches = new HashMap<>();
      mSimpleNegativeGoalLatches = new HashMap<>();

      for (PolymorphicProposition lGoals[] : xiSourceNet.getGoalPropositions().values())
      {
        for (PolymorphicProposition lGoal : lGoals)
        {
          mSimplePositiveGoalLatches.put(lGoal, xiStateMachine.createEmptyInternalState());
          mSimpleNegativeGoalLatches.put(lGoal, xiStateMachine.createEmptyInternalState());
        }
      }

      mPerRolePositiveGoalLatchMasks = new HashMap<>();
      mStaticGoalRanges = new HashMap<>();

      // Store off the roles.
      mRoles = xiStateMachine.getRoles();
    }

    @Override
    public boolean isComplete()
    {
      return mAnalysisComplete;
    }

    @Override
    public boolean hasPositivelyLatchedGoals()
    {
      return mFoundSimplePositiveGoalLatches || mFoundComplexPositiveGoalLatches;
    }

    @Override
    public boolean hasNegativelyLatchedGoals()
    {
      return mFoundSimpleNegativeGoalLatches;
    }

    @Override
    public boolean isPositivelyLatchedBaseProp(PolymorphicProposition xiProposition)
    {
      return mPositiveBaseLatchMask.contains(((ForwardDeadReckonProposition)xiProposition).getInfo());
    }

    @Override
    public boolean isNegativelyLatchedBaseProp(PolymorphicProposition xiProposition)
    {
      return mNegativeBaseLatchMask.contains(((ForwardDeadReckonProposition)xiProposition).getInfo());
    }

    @Override
    public ForwardDeadReckonInternalMachineState getPositiveBaseLatches()
    {
      return mFoundPositiveBaseLatches ? mPositiveBaseLatchMask : null;
    }

    @Override
    public long getNumPositiveBaseLatches()
    {
      return mFoundPositiveBaseLatches ? mPositiveBaseLatchMask.size() : 0;
    }

    @Override
    public long getNumNegativeBaseLatches()
    {
      return mFoundNegativeBaseLatches ? mNegativeBaseLatchMask.size() : 0;
    }

    @Override
    public boolean scoresAreLatched(ForwardDeadReckonInternalMachineState xiState)
    {
      if ((!mFoundComplexPositiveGoalLatches) && (!mAllRolesHavePositiveGoalLatches))
      {
        return false;
      }

      // Check for pair latches.  (If there are any, it must be a single-player game.)
      for (MaskedStateGoalLatch lMaskedState : mComplexPositiveGoalLatches)
      {
        if (lMaskedState.matches(xiState))
        {
          assert(mRoles.size() == 1) : "Pair latches only used in single-player games";
          return true;
        }
      }

      // It's only worth checking simple latches if all roles have latches.
      if (!mAllRolesHavePositiveGoalLatches)
      {
        return false;
      }

      // Check for single latches.
      for (Role lRole : mRoles)
      {
        ForwardDeadReckonInternalMachineState lRoleLatchMask = mPerRolePositiveGoalLatchMasks.get(lRole);

        if (!xiState.intersects(lRoleLatchMask))
        {
          return false;
        }
      }

      return true;
    }

    @Override
    public void getLatchedScoreRange(ForwardDeadReckonInternalMachineState xiState,
                                     Role xiRole,
                                     PolymorphicProposition[] xiGoals,
                                     int[] xoRange)
    {
      assert(xoRange.length == 2);

      // Initialise to sentinel values
      xoRange[0] = Integer.MAX_VALUE;
      xoRange[1] = -Integer.MAX_VALUE;
      int[] lStaticGoalRange = null;

      if ((mFoundSimplePositiveGoalLatches) ||
          (mFoundSimpleNegativeGoalLatches) ||
          (mFoundComplexPositiveGoalLatches) ||
          ((lStaticGoalRange = mStaticGoalRanges.get(xiRole)) == null))
      {
        // Check for pair latches.  (If there are any, it must be a single-player game.)
        for (MaskedStateGoalLatch lMaskedState : mComplexPositiveGoalLatches)
        {
          if (lMaskedState.matches(xiState))
          {
            int lValue = lMaskedState.getGoalValue();
            xoRange[0] = lValue;
            xoRange[1] = lValue;
            return;
          }
        }

        for (PolymorphicProposition goalProp : xiGoals)
        {
          ForwardDeadReckonInternalMachineState negativeMask = null;
          int latchedScore = Integer.parseInt(goalProp.getName().getBody().get(1).toString());

          if (mSimplePositiveGoalLatches != null)
          {
            ForwardDeadReckonInternalMachineState positiveMask = mSimplePositiveGoalLatches.get(goalProp);
            if (positiveMask != null && xiState.intersects(positiveMask))
            {
              xoRange[0] = latchedScore;
              xoRange[1] = latchedScore;
              break;
            }
          }
          if (mSimpleNegativeGoalLatches != null)
          {
            negativeMask = mSimpleNegativeGoalLatches.get(goalProp);
          }
          if (negativeMask == null || !xiState.intersects(negativeMask))
          {
            //  This is still a possible score
            if (latchedScore < xoRange[0])
            {
              xoRange[0] = latchedScore;
            }
            if (latchedScore > xoRange[1])
            {
              xoRange[1] = latchedScore;
            }
          }
        }

        if ((!mFoundSimplePositiveGoalLatches) && (!mFoundSimpleNegativeGoalLatches))
        {
          // There are no latches.  Cache the range so that we don't calculate it again.
          lStaticGoalRange = new int[2];

          lStaticGoalRange[0] = xoRange[0];
          lStaticGoalRange[1] = xoRange[1];

          mStaticGoalRanges.put(xiRole, lStaticGoalRange);
        }
      }
      else
      {
        xoRange[0] = lStaticGoalRange[0];
        xoRange[1] = lStaticGoalRange[1];
      }
    }

    @Override
    public void report()
    {
      if (!mAnalysisComplete)
      {
        LOGGER.warn("Latch analysis incomplete");
        return;
      }

      LOGGER.info("Latch analysis results");

      if (mFoundPositiveBaseLatches)
        LOGGER.info("  " + mPositiveBaseLatchMask.size() + " positive base latches: " + mPositiveBaseLatchMask);
      else
        LOGGER.info("  0 positive base latches");

      if (mFoundNegativeBaseLatches)
        LOGGER.info("  " + mNegativeBaseLatchMask.size() + " negative base latches: " + mNegativeBaseLatchMask);
      else
        LOGGER.info("  0 negative base latches");

      LOGGER.info("  " + (mFoundSimplePositiveGoalLatches ? mSimplePositiveGoalLatches.size() : "0") + " simple positive goal latches");
      LOGGER.info("  " + (mFoundSimpleNegativeGoalLatches ? mSimpleNegativeGoalLatches.size() : "0") + " simple negative goal latches");
      LOGGER.info("  " + mComplexPositiveGoalLatches.length + " complex positive goal latches");
      LOGGER.info("  " + (mAllRolesHavePositiveGoalLatches ? "A" : "Not a") + "ll roles have positive goal latches");
    }
    /**
     * Clear all latch state.
     */
    private void clear()
    {
      mAnalysisComplete = false;
      mFoundPositiveBaseLatches = false;
      mFoundNegativeBaseLatches = false;
      mFoundSimplePositiveGoalLatches = false;
      mFoundSimpleNegativeGoalLatches = false;
      mFoundComplexPositiveGoalLatches = false;
      mAllRolesHavePositiveGoalLatches = false;
      mSimplePositiveGoalLatches.clear();
      mSimpleNegativeGoalLatches.clear();
      mComplexPositiveGoalLatches = new MaskedStateGoalLatch[0];
      mPerRolePositiveGoalLatchMasks.clear();
      mStaticGoalRanges.clear();
    }

    /**
     * Save the latch analysis results.
     *
     * WARNING: State produced using this method is stored in game characteristic files.  Take care to ensure it remains
     *          back-compatible.
     *
     * @param xiStore - the game characteristics to store the latch results in.
     */
    private void save(RuntimeGameCharacteristics xiStore)
    {
      if (!mAnalysisComplete) return;

      xiStore.setLatchesBasePositive(packBaseLatches(mFoundPositiveBaseLatches, mPositiveBaseLatchMask));
      xiStore.setLatchesBaseNegative(packBaseLatches(mFoundNegativeBaseLatches, mNegativeBaseLatchMask));
      xiStore.setLatchesGoalPositive(packSimpleGoalLatches(mFoundSimplePositiveGoalLatches, mSimplePositiveGoalLatches));
      xiStore.setLatchesGoalNegative(packSimpleGoalLatches(mFoundSimpleNegativeGoalLatches, mSimpleNegativeGoalLatches));
      xiStore.setLatchesGoalComplex(packComplexGoalLatches(mFoundComplexPositiveGoalLatches, mComplexPositiveGoalLatches));
      xiStore.setLatchesGoalPerRole(packPerRoleGoalLatches(mAllRolesHavePositiveGoalLatches, mPerRolePositiveGoalLatchMasks));
    }

    private static String packBaseLatches(boolean xiFound, ForwardDeadReckonInternalMachineState xiLatches)
    {
      StringBuilder lOutput = new StringBuilder();
      lOutput.append('{');
      lOutput.append(xiFound);
      if (xiFound)
      {
        lOutput.append(',');
        xiLatches.save(lOutput);
      }
      lOutput.append('}');
      return lOutput.toString();
    }

    private static String packSimpleGoalLatches(
                                           boolean xiFound,
                                           Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> xiLatches)
    {
      StringBuilder lOutput = new StringBuilder();
      lOutput.append('{');
      lOutput.append(xiFound);
      if (xiFound)
      {
        lOutput.append(',');
        lOutput.append(xiLatches.size());
        for (Map.Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry : xiLatches.entrySet())
        {
          lOutput.append(',');
          lOutput.append(((ForwardDeadReckonProposition)(lEntry.getKey())).getInfo().index);
          lOutput.append(',');
          lEntry.getValue().save(lOutput);
        }
      }
      lOutput.append('}');
      return lOutput.toString();
    }

    private static String packComplexGoalLatches(boolean xiFound, MaskedStateGoalLatch[] xiLatches)
    {
      StringBuilder lOutput = new StringBuilder();
      lOutput.append('{');
      lOutput.append(xiFound);
      if (xiFound)
      {
        lOutput.append(',');
        lOutput.append(xiLatches.length);
        for (MaskedStateGoalLatch lLatch : xiLatches)
        {
          lOutput.append(',');
          lLatch.save(lOutput);
        }
      }
      lOutput.append('}');
      return lOutput.toString();
    }

    private String packPerRoleGoalLatches(boolean xiFound,
                                          Map<Role, ForwardDeadReckonInternalMachineState> xiLatches)
    {
      StringBuilder lOutput = new StringBuilder();
      lOutput.append('{');
      lOutput.append(xiFound);
      if (xiFound)
      {
        for (int lii = 0; lii < mRoles.size(); lii++)
        {
          lOutput.append(',');
          lOutput.append(lii);
          lOutput.append(',');
          xiLatches.get(mRoles.get(lii)).save(lOutput);
        }
      }
      lOutput.append('}');
      return lOutput.toString();
    }

    private boolean load(RuntimeGameCharacteristics xiStore,
                         ForwardDeadReckonPropnetStateMachine xiStateMachine)
    {
      // At the moment, we either save all latch analysis results or none, so check an arbitrary one to determine
      // whether we have saved latches.
      if (xiStore.getLatchesBasePositive() == null) return false;

      // Load all the saved latch state.
      mFoundPositiveBaseLatches = loadBaseLatches(new PackedData(xiStore.getLatchesBasePositive()),
                                                  mPositiveBaseLatchMask);
      mFoundNegativeBaseLatches = loadBaseLatches(new PackedData(xiStore.getLatchesBaseNegative()),
                                                  mNegativeBaseLatchMask);
      mFoundSimplePositiveGoalLatches = loadSimpleGoalLatches(new PackedData(xiStore.getLatchesGoalPositive()),
                                                              mSimplePositiveGoalLatches,
                                                              xiStateMachine);
      mFoundSimpleNegativeGoalLatches = loadSimpleGoalLatches(new PackedData(xiStore.getLatchesGoalNegative()),
                                                              mSimpleNegativeGoalLatches,
                                                              xiStateMachine);
      mComplexPositiveGoalLatches = loadComplexGoalLatches(new PackedData(xiStore.getLatchesGoalComplex()),
                                                           xiStateMachine);
      mFoundComplexPositiveGoalLatches = (mComplexPositiveGoalLatches.length > 0);
      mAllRolesHavePositiveGoalLatches = loadPerRoleGoalLatches(new PackedData(xiStore.getLatchesGoalPerRole()),
                                                                mPerRolePositiveGoalLatchMasks,
                                                                xiStateMachine);

      mAnalysisComplete = true;
      return true;
    }

    private static boolean loadBaseLatches(PackedData xiPacked,
                                           ForwardDeadReckonInternalMachineState xoLatches)
    {
      boolean lFound;
      xiPacked.checkStr("{");
      lFound = xiPacked.loadBool();
      if (lFound)
      {
        xiPacked.checkStr(",");
        xoLatches.load(xiPacked);
      }
      xiPacked.checkStr("}");
      return lFound;
    }

    private static boolean loadSimpleGoalLatches(PackedData xiPacked,
                                             Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> xoLatches,
                                             ForwardDeadReckonPropnetStateMachine xiStateMachine)
    {
      xiPacked.checkStr("{");
      boolean lFound = xiPacked.loadBool();
      if (lFound)
      {
        xiPacked.checkStr(",");
        int lSize = xiPacked.loadInt();
        for (int lii = 0; lii < lSize; lii++)
        {
          xiPacked.checkStr(",");
          PolymorphicProposition lProposition = xiStateMachine.getBaseProposition(xiPacked.loadInt());
          xiPacked.checkStr(",");
          ForwardDeadReckonInternalMachineState lState = xiStateMachine.createEmptyInternalState();
          lState.load(xiPacked);

          xoLatches.put(lProposition, lState);
        }
      }
      xiPacked.checkStr("}");
      return lFound;
    }

    private static MaskedStateGoalLatch[] loadComplexGoalLatches(PackedData xiPacked,
                                                                 ForwardDeadReckonPropnetStateMachine xiStateMachine)
    {
      MaskedStateGoalLatch[] lLatches;

      xiPacked.checkStr("{");
      if (xiPacked.loadBool())
      {
        xiPacked.checkStr(",");
        int lNumLatches = xiPacked.loadInt();
        lLatches = new MaskedStateGoalLatch[lNumLatches];
        for (int lii = 0; lii < lNumLatches; lii++)
        {
          xiPacked.checkStr(",");
          lLatches[lii] = new MaskedStateGoalLatch(xiStateMachine, 0);
          lLatches[lii].load(xiPacked);
        }
      }
      else
      {
        lLatches = new MaskedStateGoalLatch[0];
      }
      xiPacked.checkStr("}");
      return lLatches;
    }

    private boolean loadPerRoleGoalLatches(PackedData xiPacked,
                                           Map<Role, ForwardDeadReckonInternalMachineState> xiLatches,
                                           ForwardDeadReckonPropnetStateMachine xiStateMachine)
    {
      xiPacked.checkStr("{");
      boolean lFound = xiPacked.loadBool();
      if (lFound)
      {
        for (int lii = 0; lii < mRoles.size(); lii++)
        {
          xiPacked.checkStr(",");
          xiPacked.loadInt();
          xiPacked.checkStr(",");
          ForwardDeadReckonInternalMachineState lState = xiStateMachine.createEmptyInternalState();
          lState.load(xiPacked);
          xiLatches.put(mRoles.get(lii), lState);
        }
      }
      xiPacked.checkStr("}");
      return lFound;
    }
  }

  /**
   * Create a latch analyser.  Callers should call the analyse method and then discard the analyser.
   *
   * Once the analyser has been discarded, no references are retained to the input variables.
   *
   * @param xiSourceNet - the source propnet.
   * @param xiStateMachine - a state machine for use during analysis.
   */
  public LatchAnalyser(ForwardDeadReckonPropNet xiSourceNet,
                       ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    mStateMachine = xiStateMachine;

    // Create a tri-state network to assist with the analysis.
    mSourceNet = xiSourceNet;
    mTristateNet = new TristatePropNet(mSourceNet);

    // Clone the mapping from source to target.
    mSourceToTarget = new HashMap<>(PolymorphicPropNet.sLastSourceToTargetMap.size());
    for (PolymorphicComponent lSource : PolymorphicPropNet.sLastSourceToTargetMap.keySet())
    {
      mSourceToTarget.put(lSource, (TristateComponent)PolymorphicPropNet.sLastSourceToTargetMap.get(lSource));
    }

    // Create temporary state that is summarised in mLatches during post-processing.
    mPositiveBaseLatches = new HashSet<>();
    mNegativeBaseLatches = new HashSet<>();
    mComplexPositiveGoalLatchList = new ArrayList<>();

    mLatches = new Latches(mSourceNet, mStateMachine);
  }

  /**
   * Analyse a propnet for latches.
   *
   * @param xiDeadline        - the (latest) time to run until.
   * @param xiCharacteristics - game characteristics.
   *
   * @return the results of the latch analysis.
   */
  public LatchResults analyse(long xiDeadline, RuntimeGameCharacteristics xiCharacteristics)
  {
    if (mLatches.load(xiCharacteristics, mStateMachine))
    {
      return mLatches;
    }

    // Analyse the propnet for latches.
    mDeadline = xiDeadline;
    try
    {
      analyse();
      mLatches.save(xiCharacteristics);
    }
    catch (TimeoutException lEx)
    {
      // Timed out whilst calculating latches.  Clear all the state.  (It's better to have none than for it to be
      // incomplete.)
      LOGGER.warn("Timed out whilst analysing latches");

      mPositiveBaseLatches.clear();
      mNegativeBaseLatches.clear();
      mComplexPositiveGoalLatchList.clear();

      mLatches.clear();
    }

    return mLatches;
  }

  private LatchResults analyse() throws TimeoutException
  {
    // Do per-proposition analysis on all the base propositions.
    for (PolymorphicComponent lSourceComp1 : mSourceNet.getBasePropositionsArray())
    {
      checkForTimeout();

      // Check if this proposition is a goal latch or a regular latch (or not a latch at all).
      tryLatch((ForwardDeadReckonProposition)lSourceComp1, true);
      tryLatch((ForwardDeadReckonProposition)lSourceComp1, false);
    }

    tryLatchPairs();

    postProcessLatches();
    mLatches.mAnalysisComplete = true;

    return mLatches;
  }

  /**
   * Test whether a proposition is a latch, adding it to the set of latches if so.
   *
   * @param xiProposition - the proposition to test.
   * @param xiPositive - the latch sense.
   */
  private void tryLatch(ForwardDeadReckonProposition xiProposition, boolean xiPositive)
  {
    TristateProposition lTristateProposition = getProp(xiProposition);
    Tristate lTestState = xiPositive ? Tristate.TRUE : Tristate.FALSE;
    Tristate lOtherState = xiPositive ? Tristate.FALSE : Tristate.TRUE;
    Set<ForwardDeadReckonProposition> lLatchSet = xiPositive ? mPositiveBaseLatches : mNegativeBaseLatches;

    try
    {
      mTristateNet.reset();
      lTristateProposition.assume(Tristate.UNKNOWN, lTestState, Tristate.UNKNOWN);
      if (lTristateProposition.getValue(2) == lTestState)
      {
        lLatchSet.add(xiProposition);

        if (xiPositive)
        {
          checkGoalLatch(xiProposition);
        }
        return;
      }

      mTristateNet.reset(); // !! ARR This shouldn't be necessary, but it is, which implies a tri-state propagation bug
      lTristateProposition.assume(lOtherState, lTestState, Tristate.UNKNOWN);
      if (lTristateProposition.getValue(2) == lTestState)
      {
        lLatchSet.add(xiProposition);

        if (xiPositive)
        {
          checkGoalLatch(xiProposition);
        }
      }
    }
    catch (ContradictionException lEx) { /* Do nothing */ }
  }

  /**
   * Check whether any goals are latched in the tri-state network.  If so, add the proposition which caused it to the
   * set of latches.
   *
   * @param xiProposition - the latching proposition which MUST itself be a +ve latch.
   */
  private void checkGoalLatch(ForwardDeadReckonProposition xiProposition)
  {
    Map<Role, PolymorphicProposition[]> lSourceGoals = mSourceNet.getGoalPropositions();
    Iterator<Entry<Role, PolymorphicProposition[]>> lIterator = lSourceGoals.entrySet().iterator();

    while (lIterator.hasNext())
    {
      Map.Entry<Role, PolymorphicProposition[]> lEntry = lIterator.next();
      for (PolymorphicProposition lGoal : lEntry.getValue())
      {
        Tristate lValue = getProp(lGoal).getValue(2);
        if (lValue == Tristate.TRUE)
        {
          addLatchingProposition((ForwardDeadReckonProposition)lGoal, xiProposition, true);
          mLatches.mFoundSimplePositiveGoalLatches = true;
        }
        else if (lValue == Tristate.FALSE)
        {
          addLatchingProposition((ForwardDeadReckonProposition)lGoal, xiProposition, false);
          mLatches.mFoundSimpleNegativeGoalLatches = true;
        }
      }
    }
  }

  private void addLatchingProposition(ForwardDeadReckonProposition xiGoal,
                                      ForwardDeadReckonProposition xiLatchingProposition,
                                      boolean xiPositive)
  {
    Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lGoalMap =
                                                                           (xiPositive ? mLatches.mSimplePositiveGoalLatches :
                                                                                         mLatches.mSimpleNegativeGoalLatches);
    ForwardDeadReckonInternalMachineState lExisting = lGoalMap.get(xiGoal);
    lExisting.add(xiLatchingProposition.getInfo());
  }

  private void postProcessLatches()
  {
    // Post-process base latches into a state mask for fast access.
    for (ForwardDeadReckonProposition lProp : mPositiveBaseLatches)
    {
      mLatches.mFoundPositiveBaseLatches = true;
      mLatches.mPositiveBaseLatchMask.add(lProp.getInfo());
    }

    for (ForwardDeadReckonProposition lProp : mNegativeBaseLatches)
    {
      mLatches.mFoundNegativeBaseLatches = true;
      mLatches.mNegativeBaseLatchMask.add(lProp.getInfo());
    }

    // Post-process the goal latches to remove any goals for which no latches were found.
    Iterator<Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState>> lIterator =
      mLatches.mSimplePositiveGoalLatches.entrySet().iterator();
    while (lIterator.hasNext())
    {
      Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry = lIterator.next();
      PolymorphicProposition lGoal = lEntry.getKey();
      ForwardDeadReckonInternalMachineState lLatches = lEntry.getValue();

      if (lLatches.size() != 0)
      {
        LOGGER.debug("Goal '" + lGoal.getName() + "' is positively latched by any of: " + lLatches);
      }
      else
      {
        lIterator.remove();
      }
    }

    if (mLatches.mSimplePositiveGoalLatches.isEmpty())
    {
      mLatches.mSimplePositiveGoalLatches = null;
    }

    lIterator = mLatches.mSimpleNegativeGoalLatches.entrySet().iterator();
    while (lIterator.hasNext())
    {
      Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry = lIterator.next();
      PolymorphicProposition lGoal = lEntry.getKey();
      ForwardDeadReckonInternalMachineState lLatches = lEntry.getValue();

      if (lLatches.size() != 0)
      {
        LOGGER.debug("Goal '" + lGoal.getName() + "' is negatively latched by any of: " + lLatches);
      }
      else
      {
        lIterator.remove();
      }
    }

    if (mLatches.mSimpleNegativeGoalLatches.isEmpty())
    {
      mLatches.mSimpleNegativeGoalLatches = null;
    }

    // On a per-role basis, calculate the state masks that imply some positively latched goal for the role.
    if (mLatches.mSimplePositiveGoalLatches != null)
    {
      // Assume that all roles have at least 1 positive latch until we learn otherwise.
      mLatches.mAllRolesHavePositiveGoalLatches = true;

      for (Role lRole : mStateMachine.getRoles())
      {
        ForwardDeadReckonInternalMachineState lRoleLatchMask = mStateMachine.createEmptyInternalState();

        for (PolymorphicProposition goalProp : mSourceNet.getGoalPropositions().get(lRole))
        {
          ForwardDeadReckonInternalMachineState goalMask = mLatches.mSimplePositiveGoalLatches.get(goalProp);

          if (goalMask != null)
          {
            lRoleLatchMask.merge(goalMask);
          }
        }

        if (lRoleLatchMask.size() > 0)
        {
          mLatches.mPerRolePositiveGoalLatchMasks.put(lRole, lRoleLatchMask);
        }
        else
        {
          mLatches.mAllRolesHavePositiveGoalLatches = false;
        }
      }
    }
    else
    {
      mLatches.mAllRolesHavePositiveGoalLatches = false;
    }

    mLatches.mComplexPositiveGoalLatches =
      mComplexPositiveGoalLatchList.toArray(new MaskedStateGoalLatch[mComplexPositiveGoalLatchList.size()]);
    mLatches.mFoundComplexPositiveGoalLatches = (!mComplexPositiveGoalLatchList.isEmpty());
  }

  /**
   * Find pairs of base latches which make a goal latch.
   *
   * @throws TimeoutException
   */
  private void tryLatchPairs() throws TimeoutException
  {
    // This is expensive.  Only do it for puzzles.
    if (mStateMachine.getRoles().size() != 1) return;

    // Only do it if we haven't found any goal latches so far.
    if (mLatches.mFoundSimplePositiveGoalLatches || mLatches.mFoundSimpleNegativeGoalLatches) return;

    // Only do it if there are 300 or fewer base latches (otherwise it gets out of hand for performance).
    if (mPositiveBaseLatches.size() > 300) return;

    // It's worth checking to see if any pairs of base proposition latches constitute a goal latch.  Many logic puzzles
    // contain constraints on pairs of propositions that might manifest in this way.
    //
    // Only consider positive base latches, simply because there aren't any games where we need to do this for negative
    // base latches.
    LOGGER.info("Checking for latch pairs of " + mPositiveBaseLatches.size() + " 1-proposition latches");

    ForwardDeadReckonProposition[] lBaseLatches = mPositiveBaseLatches.toArray(
                                                         new ForwardDeadReckonProposition[mPositiveBaseLatches.size()]);
    for (int lii = 0; lii < lBaseLatches.length; lii++)
    {
      ForwardDeadReckonProposition lBaseLatch1 = lBaseLatches[lii];
      checkForTimeout();

      // !! ARR Do the "assume" for the first state here and then save/reload as required.
      for (int ljj = lii + 1; ljj < lBaseLatches.length; ljj++)
      {
        ForwardDeadReckonProposition lBaseLatch2 = lBaseLatches[ljj];
        try
        {
          mTristateNet.reset();
          // !! ARR Ideally only set up as a basic latch if it is a basic latch.
          getProp(lBaseLatch1).assume(Tristate.FALSE, Tristate.TRUE, Tristate.UNKNOWN);
          getProp(lBaseLatch2).assume(Tristate.FALSE, Tristate.TRUE, Tristate.UNKNOWN);
          checkGoalLatchPair(lBaseLatch1, lBaseLatch2);
        }
        catch (ContradictionException lEx) { /* Oops */ }
      }
    }
  }

  /**
   * Check whether any goals are latched by a pair of propositions.  If so, add the pair to the set of pair latches.
   *
   * @param xiProposition1 - the 1st proposition which MUST itself be a positive latch.
   * @param xiProposition1 - the 2ns proposition which MUST itself be a positive latch.
   */
  private void checkGoalLatchPair(ForwardDeadReckonProposition xiProposition1,
                                  ForwardDeadReckonProposition xiProposition2)
  {
    Map<Role, PolymorphicProposition[]> lSourceGoals = mSourceNet.getGoalPropositions();
    Iterator<Entry<Role, PolymorphicProposition[]>> lIterator = lSourceGoals.entrySet().iterator();

    while (lIterator.hasNext())
    {
      Map.Entry<Role, PolymorphicProposition[]> lEntry = lIterator.next();
      for (PolymorphicProposition lGoal : lEntry.getValue())
      {
        Tristate lValue = getProp(lGoal).getValue(2);
        if (lValue == Tristate.TRUE)
        {
          LOGGER.debug(xiProposition1.getName() + " & " + xiProposition2.getName() + " are a +ve pair latch for " + lGoal.getName());

          MaskedStateGoalLatch lMaskedState =
                          new MaskedStateGoalLatch(mStateMachine, ((ForwardDeadReckonProposition)lGoal).getGoalValue());
          lMaskedState.add(xiProposition1, true);
          lMaskedState.add(xiProposition2, true);
          mComplexPositiveGoalLatchList.add(lMaskedState);
        }
        // We only care about +ve goal latches for now
      }
    }
  }

  private TristateProposition getProp(PolymorphicProposition xiSource)
  {
    return (TristateProposition)mSourceToTarget.get(xiSource);
  }

  private void checkForTimeout() throws TimeoutException
  {
    if (System.currentTimeMillis() > mDeadline)
    {
      throw new TimeoutException();
    }
  }
}