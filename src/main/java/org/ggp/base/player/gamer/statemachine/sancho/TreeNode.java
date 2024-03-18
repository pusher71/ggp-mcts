package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.File;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.OpenBitSet;
import org.ggp.base.player.gamer.statemachine.sancho.TreePath.TreePathElement;
import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool;
import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool.ObjectAllocator;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.NullStateMachineFilter;

/**
 * A node in an MCTS "tree" (actually a DAG).
 *
 * OCCUPANCY CRITICAL CLASS.
 */
public class TreeNode
{
  /**
   * @author steve
   *  Known status from local search for this node.  Note that these statuses are
   *  (strongly) advisory rather than 100% certain
   */
  public enum LocalSearchStatus
  {
    /**
     * This node has not been the subject of a local search
     */
    LOCAL_SEARCH_UNSEARCHED,
    /**
     * This node was local searched, but no definitive result was found
     */
    LOCAL_SEARCH_NO_RESULT,
    /**
     * Local search found this node to be a loss (for its chooser)
     */
    LOCAL_SEARCH_LOSS,
    /**
     * Local search found this node to be a win (for its chooser)
     */
    LOCAL_SEARCH_WIN;

    /**
     * @return Whether the value represents a definite local search result
     */
    boolean HasResult()
    {
      return (this == LOCAL_SEARCH_LOSS || this == LOCAL_SEARCH_WIN);
    }

    @Override
    public String toString()
    {
      switch(this)
      {
        case LOCAL_SEARCH_UNSEARCHED:
          return "Not local searched";
        case LOCAL_SEARCH_LOSS:
          return "Local search loss";
        case LOCAL_SEARCH_NO_RESULT:
          return "No local search result";
        case LOCAL_SEARCH_WIN:
          return "Local search win";
        default:
          break;
      }

      return super.toString();
    }
  }

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");

  /**
   * An arbitrary small number to cope with rounding errors
   */
  static final double         EPSILON = 1e-4;

  /**
   * Decay per-update of averages weights (to make update calculate a moving average)
   */
  static final double         SCORE_TEMPORAL_DECAY_RATE = 0.99;

  /**
   * Selections through a node before first periodic normalizations
   */
  static private final short  NORMALIZATION_WARMUP_PERIOD = 500;

  /**
   * Selections through a node between periodic normalizations
   */
  static private final short  NORMALIZATION_PERIOD = 100;

  /**
   * For debugging use only - enable assertion that the game is fixed sum
   */
  private static final boolean       ASSERT_FIXED_SUM = false;

  private static final DecimalFormat FORMAT_2DP = new DecimalFormat("####0.00");

  /**
   * Dummy reference value to use when a reference doesn't currently refer to a tree node.
   */
  public static final long NULL_REF = -1L;

  private static final double RAVE_BIAS = 0.05;

  /**
   * Utility class for allocating tree nodes from a CappedPool.
   */
  public static class TreeNodeAllocator implements ObjectAllocator<TreeNode>
  {
    private final MCTSTree mTree;

    /**
     * Create an allocator for nodes in the the specified MCTS tree.
     *
     * @param xiTree - the tree.
     */
    public TreeNodeAllocator(MCTSTree xiTree)
    {
      mTree = xiTree;
    }

    @Override
    public TreeNode newObject(int xiPoolIndex)
    {
      TreeNode lNode = new TreeNode(mTree, xiPoolIndex);
      return lNode;
    }

    @Override
    public void resetObject(TreeNode xiNode, boolean xiFree)
    {
      xiNode.reset(xiFree ? null : mTree);
    }

    @Override
    public boolean shouldReset(TreeNode xiNode)
    {
      // Only reset items from our own tree that haven't already been freed.
      return (xiNode.mTree == mTree) && (!xiNode.mFreed);
    }
  }

  /**
   * WARNING
   * -------
   *
   * This is an occupancy critical class.  Members should only be added when absolutely necessary and should use the
   * smallest available type.
   */

  /**
   * The tree in which we're a node.
   */
  MCTSTree mTree;

  // A node reference.  This is a combination of the node's index in the allocating pool & a sequence number.  The
  // sequence number is incremented each time the node is re-used (thereby invalidating old references).
  //
  // The index is in the low 32 bits.  The sequence number is in the high 32 bits.
  //
  // For performance we also keep the instance ID in its own field.  Having been set on allocation, this never changes.
  private long                          mRef                  = 0;
  private final int                     mInstanceID;

  public int                            mNumVisits            = 0;
  double                                mNumUpdates           = 0;
  final ForwardDeadReckonInternalMachineState mState;
  int                                   mDecidingRoleIndex;
  boolean                               mTerminal             = false;
  boolean                               mComplete             = false;
  private boolean                       mAllChildrenComplete  = false;
  LocalSearchStatus                     mLocalSearchStatus    = LocalSearchStatus.LOCAL_SEARCH_UNSEARCHED;
  short                                 mNumChildren          = 0;
  final ArrayList<TreeNode>             mParents              = new ArrayList<>(1);
  private int                           mSweepSeq;
  boolean                               mFreed                = false;
  private short                         mUpdatesToNormalization = NORMALIZATION_WARMUP_PERIOD;
  private short                         mLastSelectionMade    = -1;

  //  Note - the 'depth' of a node is an indicative measure of its distance from the
  //  initial state.  However, it is not an absolute count of the path length.  This
  //  is because in some games the same state can occur at different depths (English Draughts
  //  exhibits this), which means that transitions to the same node can occur at multiple
  //  depths.  This approximate nature good enough for our current usage, but should be borne
  //  in mind if that usage is expanded.  It is initialized to -1 so that a transposition
  //  to an existing node can be distinguished from a fresh allocation
  private short                         mDepth                = -1;
  short                                 mCompletionDepth;
  private double                        mHeuristicValue;
  private double                        mHeuristicWeight;

  //  To what depth is the hyper-linkage tree expanded from this node
  private short                         mHyperExpansionDepth  = 0;

  /**
   * WARNING: The following arrays are created for every node and are sized per max. branching factor.  Every byte here
   *          costs ~100MB (depending on the game).  It is absolutely vital that this is kept below 20 bytes.
   */
  // Children.  Before expansion, a ForwardDeadReckonLegalMoveInfo.  After expansion, a TreeEdge.
  Object[]                              mChildren   = null;

  short[]                               mPrimaryChoiceMapping = null;

  //  RAVE stats belong logically in EDGEs, but we need to maintain them for leaf nodes
  //  where it is undesirable to have to create EDGEs until they are expanded.  Consequently
  //  we store the RAVE stats in their own arrays directly in the parent node using the same
  //  child indexes as the corresponding edge
  private RAVEStats                     mRAVEStats = null;

  double                                mBestDecidingScore = 0;

  /**
   * Create a tree node.
   *
   * @param mTree        - the tree in which this node is to be found.
   * @param xiPoolIndex - the index in the pool from which this node was allocated.
   */
  TreeNode(MCTSTree xiTree, int xiPoolIndex)
  {
    mTree = xiTree;
    mRef = xiPoolIndex;
    mInstanceID = xiPoolIndex;
    mState = mTree.mUnderlyingStateMachine.createEmptyInternalState();

    int lMaxDirectChildren = mTree.mGameCharacteristics.getChoicesHighWaterMark(0);
    mChildren = new Object[lMaxDirectChildren];
  }

  /**
   * Set the game state represented by this node.
   *
   * @param xiState - the state.
   */
  public void setState(ForwardDeadReckonInternalMachineState xiState)
  {
    mState.copy(xiState);
    //assert(mNumChildren <= 1 || state.toString().contains("control o") == (decidingRoleIndex == 1));
  }

  /**
   * Retrieve the depth of this node from the initial state
   * @return node's depth
   */
  public int getDepth()
  {
    return mDepth;
  }

  /**
   * Set the depth of this node from the initial state
   * @param theDepth value to set
   */
  public void setDepth(short theDepth)
  {
    mDepth = theDepth;
  }

  /**
   * Retrieve the depth of the best-play terminal state known to
   * derive from this node.  Valid only if the node is complete
   * @return depth of the terminal state, from the initial state
   */
  public short getCompletionDepth()
  {
    assert(mComplete);

    return mCompletionDepth;
  }

  /**
   * Add the first parent to this tree node.
   *
   * Nodes can have multiple parents because the MCTS Tree isn't actually a tree at all.  It's a DAG.
   *
   * @param xiParent - the parent to add.
   */
  public void addParent(TreeNode xiParent)
  {
    assert(this != mTree.mRoot);
    mParents.add(xiParent);

    // Trim off any excess array slots from the last time this node was used.
    if (mParents.size() == 1)
    {
      mParents.trimToSize();
    }
  }

  private boolean checkFixedSum(double[] values)
  {
    if (!ASSERT_FIXED_SUM)
    {
      return true;
    }

    assert(values.length == mTree.mNumRoles);
    double total = 0;
    for (int lii = 0; lii < mTree.mNumRoles; lii++)
    {
      total += values[lii];
    }
    return (Math.abs(total-100) < EPSILON);
  }

  private boolean checkFixedSum()
  {
    if (!ASSERT_FIXED_SUM)
    {
      return true;
    }

    double total = 0;
    for (int lii = 0; lii < mTree.mNumRoles; lii++)
    {
      total += getAverageScore(lii);
    }
    return (Math.abs(total-100) < EPSILON);
  }

  private void correctParentsForCompletion()
  {
    //	Cannot do an a-priori correction of scores based on known child scores
    //	if heuristics are in use (at least not simply, so for now, just not)
    //if (pieceStateMaps == null)
    {
      TreeNode primaryPathParent = null;
      int mostSelectedRouteCount = 0;

      for (TreeNode parent : mParents)
      {
        if (parent.mNumUpdates > 0)
        {
          for (short index = 0; index < parent.mNumChildren; index++)
          {
            if (parent.mPrimaryChoiceMapping == null || parent.mPrimaryChoiceMapping[index] == index)
            {
              Object lChoice = parent.mChildren[index];

              TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
              if (edge != null && edge.getChildRef() != NULL_REF && get(edge.getChildRef()) == this)
              {
                if (edge.getNumChildVisits() > mostSelectedRouteCount)
                {
                  mostSelectedRouteCount = edge.getNumChildVisits();
                  primaryPathParent = parent;
                }
                break;
              }
            }
          }
        }
      }

      if (primaryPathParent != null && !primaryPathParent.mComplete)
      {
        boolean propagate = true;

        //validateScoreVector(primaryPathParent.averageScores);

        double totalWeight = 0;

        for (int lii = 0; lii < mTree.mNumRoles; lii++)
        {
          mTree.mCorrectedAverageScoresBuffer[lii] = 0;
        }

        for (short index = 0; index < primaryPathParent.mNumChildren; index++)
        {
          if (primaryPathParent.mPrimaryChoiceMapping == null || primaryPathParent.mPrimaryChoiceMapping[index] == index)
          {
            Object lChoice = primaryPathParent.mChildren[index];

            TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
            if (edge != null && edge.getChildRef() != NULL_REF && get(edge.getChildRef()) != null)
            {
              TreeNode lChild = get(edge.getChildRef());

              if (lChild.mNumUpdates > 0 || lChild.mComplete)
              {
                double exploitationUct = primaryPathParent.exploitationUCT(edge, lChild.mDecidingRoleIndex);

                double weight = (exploitationUct + 1 / Math.log(primaryPathParent.mNumVisits + 1)) * lChild.mNumVisits +
                                                                                                                  EPSILON;
                totalWeight += weight;
                for (int lii = 0; lii < mTree.mNumRoles; lii++)
                {
                  mTree.mCorrectedAverageScoresBuffer[lii] += weight * lChild.getAverageScore(lii);
                }
              }
            }
          }
        }

        for (int lii = 0; lii < mTree.mNumRoles; lii++)
        {
          mTree.mCorrectedAverageScoresBuffer[lii] /= totalWeight;
        }

        for (int lii = 0; lii < mTree.mNumRoles; lii++)
        {
          primaryPathParent.setAverageScore(lii, mTree.mCorrectedAverageScoresBuffer[lii]);
        }

        assert(primaryPathParent.checkFixedSum());

        if (propagate)
        {
          primaryPathParent.correctParentsForCompletion();
        }
        //validateScoreVector(primaryPathParent.averageScores);
      }
    }
  }

  private void validateCompletionValues(double[] values)
  {
    boolean matchesAll = true;
    boolean matchesDecider = false;

    for (short index = 0; index < mNumChildren; index++)
    {
      if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
      {
        Object lChoice = mChildren[index];

        TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
        if (edge != null && edge.getChildRef() != NULL_REF && get(edge.getChildRef()) != null)
        {
          TreeNode lChild = get(edge.getChildRef());
          if (lChild.mComplete)
          {
            if (lChild.getAverageScore(mDecidingRoleIndex) == values[mDecidingRoleIndex])
            {
              matchesDecider = true;
            }
            else
            {
              matchesAll = false;
            }
          }
          else
          {
            matchesAll = false;
          }
        }
      }
    }

    if (!matchesAll && !matchesDecider)
    {
      LOGGER.warn("Inexplicable completion!");
    }
  }

  void markAsLocalLoss(short atDepth, ForwardDeadReckonLegalMoveInfo withMove)
  {
    assert(atDepth>=0);
    if (!mComplete)
    {
      mLocalSearchStatus = LocalSearchStatus.LOCAL_SEARCH_LOSS;
      mCompletionDepth = (short)(mDepth + atDepth);
    }
  }

  void markComplete(double[] values, short atCompletionDepth)
  {
    if (!mComplete)
    {
      //validateCompletionValues(values);
      //validateAll();
      for (int lii = 0; lii < mTree.mNumRoles; lii++)
      {
        setAverageScore(lii, values[lii]);
      }

      enactMarkComplete(atCompletionDepth);
      //validateAll();
    }
  }

  public void markComplete(TreeNode fromDeciderNode, short atCompletionDepth)
  {
    if (!mComplete)
    {
      //assert(state.toString().contains("control o") == (decidingRoleIndex == 1));
      //validateCompletionValues(values);
      //validateAll();
      for (int lii = 0; lii < mTree.mNumRoles; lii++)
      {
        setAverageScore(lii, fromDeciderNode.getAverageScore(lii));
      }

      enactMarkComplete(atCompletionDepth);
      //validateAll();
    }
  }

  private void enactMarkComplete(short atCompletionDepth)
  {
    assert(checkFixedSum());
    assert(linkageValid());

    if (mNumUpdates > 0 && mTree.mGameCharacteristics.isSimultaneousMove)
    {
      correctParentsForCompletion();
    }

    mTree.mNumCompletedBranches++;
    mComplete = true;
    mCompletionDepth = atCompletionDepth;
    assert(mCompletionDepth >= mDepth);
    assert(mTerminal || (mCompletionDepth > mDepth)) : "Can't be immediately complete except in terminal node";

    //LOGGER.debug("Mark complete with score " + averageScore + (ourMove == null ? " (for opponent)" : " (for us)") + " in state: " + state);
    if (this == mTree.mRoot)
    {
      LOGGER.info("Mark root complete");

      // Get more information when puzzles fail overnight.
      if (mTree.mGameCharacteristics.numRoles == 1)
      {
        double lAvgScore = getAverageScore(0);
        LOGGER.debug("Score at root: " + lAvgScore);
        LOGGER.debug("State at root: " + mState);
        if (lAvgScore < (100 - EPSILON))
        {
          String lDumpName = "logs/puzzlefail." + System.currentTimeMillis() + ".txt";
          LOGGER.debug("Dumping losing tree to: " + lDumpName);
          dumpTree(lDumpName);
        }
      }
    }
    else
    {
      mTree.mCompletedNodeRefQueue.add(mRef);
    }
  }

  void processCompletion()
  {
    assert(linkageValid());
    //validateCompletionValues(averageScores);
    //validateAll();
    //	Children can all be freed, at least from this parentage
    if (MCTSTree.FREE_COMPLETED_NODE_CHILDREN)
    {
      boolean keepAll = false;
      boolean keepBest = false;

      if (MCTSTree.KEEP_BEST_COMPLETION_PATHS)
      {
        if (mDecidingRoleIndex == mTree.mNumRoles - 1)
        {
          //  Our choice from this node - just retain the best
          keepBest = true;
        }
        else
        {
          //  Opponent choice from this node - need to keep all since they might play any
          keepAll = true;
        }
      }

      if (!keepAll)
      {
        int keepIndex = -1;

        if (keepBest)
        {
          double bestScore = 0;

          for (int index = 0; index < mNumChildren; index++)
          {
            if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
            {
              Object lChoice = mChildren[index];

              TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
              if (edge != null)
              {
                //  Completion processing happens via the direct link tree only, so ignore hyper-edges
                if (edge.isHyperEdge())
                {
                  break;
                }

                TreeNode lChild = (edge.getChildRef() == NULL_REF ? null : get(edge.getChildRef()));

                if (lChild != null && lChild.mComplete && lChild.getAverageScore(0) > bestScore)
                {
                  bestScore = lChild.getAverageScore(0);
                  keepIndex = index;
                }
              }
            }
          }
        }

        if (keepIndex != -1 || this != mTree.mRoot)
        {
          for (int index = 0; index < mNumChildren; index++)
          {
            if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
            {
              Object lChoice = mChildren[index];

              TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
              if (edge != null)
              {
                if (keepIndex != index)
                {
                  TreeNode lChild = (edge.getChildRef() == NULL_REF || edge.isHyperEdge()) ? null : get(edge.getChildRef());

                  deleteEdge(index);
                  if (lChild != null)
                  {
                    lChild.freeFromAncestor(this, null);
                  }
                }
                else
                {
                  //  Must make sure the edge we retain becomes selectable if it was previously handled by
                  //  a hyper-edge alternative
                  edge.setIsSelectable(true);
                }
              }
            }
          }

          if (keepIndex == -1)
          {
            assert(this != mTree.mRoot);
            //  Actually retained nothing - can get rid of the children entirely
            mNumChildren = 0;
          }
        }
      }
    }

    for (TreeNode parent : mParents)
    {
      if (!parent.mComplete)
      {
        boolean decidingRoleWin = false;
        boolean mutualWin = true;
        //  Because we link directly through force-move sequences the deciding role in the completed
        //  node may not be the role that chose that path from the parent - we must check what the choosing
        //  role on the particular parent was
        int choosingRoleIndex = (parent.mDecidingRoleIndex + 1) % mTree.mNumRoles;

        for (int roleIndex = 0; roleIndex < mTree.mNumRoles; roleIndex++)
        {
          //  Don't take wins by a player other than us in a non fixed-sum >2 player game as auto-propagating
          //  or else we may search only on a win path that is not the pessimal win-path for us of those that are all
          //  wins for the opponent concerned (if it's our win just take it and don;t worry if we could possibly
          //  make them suffer worse-  better to converge quickly)
          mTree.mUnderlyingStateMachine.getLatchedScoreRange(parent.mState,
                                                             mTree.mRoleOrdering.roleIndexToRole(roleIndex),
                                                             mTree.mLatchedScoreRangeBuffer);
          if (mTree.mLatchedScoreRangeBuffer[1] > mTree.mLatchedScoreRangeBuffer[0] &&
              getAverageScore(roleIndex) > mTree.mLatchedScoreRangeBuffer[1] - EPSILON &&
              (choosingRoleIndex == 0 || (mTree.mGameCharacteristics.getIsFixedSum() && mTree.mNumRoles < 3)))
          {
            if (roleIndex == choosingRoleIndex &&
                (mTree.mRemoveNonDecisionNodes || roleIndex == 0 || hasSiblinglessParents()))
            {
              decidingRoleWin = true;
              if (mTree.mNumRoles == 1)
              {
                mutualWin = false;
              }
            }
          }
          else
          {
            mutualWin = false;
          }
        }

        if (decidingRoleWin && !mutualWin)
        {
          // Win for whoever just moved after they got to choose so parent node is also decided
          // Note that this node might be transposed to from parents at differing depths, so mark
          // the parent complete at a depth relative to its own
          short parentCompletionDepth = (short)(mCompletionDepth-mDepth + 1 + parent.mDepth);

          parent.markComplete(this, parentCompletionDepth);
        }
        else
        {
          //	If all children are complete then the parent is - give it a chance to
          //	decide
          parent.checkChildCompletion(true);
        }
      }
    }
    //validateAll();
  }

  /**
   * Delete references to all children.
   */
  public void freeChildren()
  {
    for (int lii = 0; lii < mNumChildren; lii++)
    {
      mChildren[lii] = null;
    }
    mNumChildren = 0;

    if (mTree != null)
    {
      //  If the child array was above a certain threshold (due to having been used for a large number
      //  of hyper-edges, which will not be a dominant case) reduce the allocation back to the high
      //  watermark for direct children
      int lMaxDirectChildren = mTree.mGameCharacteristics.getChoicesHighWaterMark(0);
      if (mChildren.length > lMaxDirectChildren * 2)
      {
        mChildren = new Object[lMaxDirectChildren];
      }

      // Dispose of RAVE results.  If there are as many as the high-water mark, free them back to the pool.  Otherwise,
      // just null out the because they aren't useful any longer.
      if (mRAVEStats != null)
      {
        detachRAVEStats();
      }
    }
  }

  private boolean validateHasChild(TreeNode child)
  {
    boolean result = false;

    for (int lii = 0; lii < mNumChildren; lii++)
    {
      if ((mChildren[lii] instanceof TreeEdge) && ((TreeEdge)mChildren[lii]).getChildRef() == child.getRef())
      {
        result = true;
        break;
      }
    }

    return result;
  }

  boolean validateHyperChain(TreeEdge edge)
  {
    if (mComplete)
    {
      return true;
    }

    if (edge.mParentRef != getRef())
    {
      //  Stale hyper-edge - can happen after trimming
      return true;
    }

    if (edge.hyperSuccessor == null)
    {
      if (edge.getChildRef() != NULL_REF)
      {
        TreeNode child = get(edge.getChildRef());

        if (child != null)
        {
          assert(child.mDepth/2 > mDepth/2);
          //assert(depth/2 == state.size()-3);
        }
      }
    }
    else
    {
      if (edge.getChildRef() != edge.hyperSuccessor.getChildRef())
      {
        //  Stale hyper-edge - can happen after trimming
        return true;
      }

      TreeNode child = get(edge.nextHyperChild);

      if (child == null)
      {
        assert(false) : "Null node link in the middle of a hyper chain";
        return false;
      }
      if (child.mDepth <= 115 && child.mDepth/2 != mDepth/2 + 1)
      {
        assert(false) : "Unexpected depth change across on link of hyper-chain";
        return false;
      }

      child.validateHyperChain(edge.hyperSuccessor);
    }

    return true;
  }

  boolean linkageValid()
  {
    if (mNumChildren > 0)
    {
      boolean hyperEdgeSeen = false;

      for (short lii = 0; lii < mNumChildren; lii++)
      {
        if (mChildren[lii] instanceof TreeEdge)
        {
          TreeEdge edge = (TreeEdge)mChildren[lii];

          if (edge.isHyperEdge())
          {
            hyperEdgeSeen = true;
          }
          else if (hyperEdgeSeen)
          {
            assert(false) : "normal edge after start of hyper edges";
            return false;
          }
          if (edge.getChildRef() != NULL_REF)
          {
            TreeNode child = get(edge.getChildRef());

            if (child != null)
            {
              if (edge.hyperSuccessor == null && child != mTree.mRoot && !child.mParents.contains(this))
              {
                assert(false) : "child link not reflected in back-link";
                return false;
              }

              if (!edge.isHyperEdge())
              {
                for (short ljj = 0; ljj < mNumChildren; ljj++)
                {
                  if (lii != ljj && mChildren[ljj] instanceof TreeEdge)
                  {
                    TreeEdge edge2 = (TreeEdge)mChildren[ljj];
                    TreeNode child2 = (edge2.getChildRef() == NULL_REF ? null : get(edge2.getChildRef()));

                    if (child == child2 && edge.isSelectable())
                    {
                      assert(false) : "multiply linked child";
                      return false;
                    }
                  }
                }
              }
              else
              {
                validateHyperChain(edge);
              }
            }
            else if (edge.hyperSuccessor == null)
            {
              assert(false) : "edge points to stale child";
              return false;
            }
          }
        }
      }
    }

    for (TreeNode parent : mParents)
    {
      if (!parent.validateHasChild(this))
      {
        assert(false) : "parent missing child link";
        return false;
      }
    }
    return true;
  }

  private void freeFromAncestor(TreeNode ancestor, TreeNode xiKeep)
  {
    assert(this == xiKeep || mParents.contains(ancestor));

    boolean keep = (xiKeep == null ? (mParents.size() > 1) : (mSweepSeq == mTree.mSweepInstance));

    if (keep)
    {
      // We're re-rooting the tree and have already calculated that this node (which we happen to have reached through
      // a part of the tree that's being pruned) is reachable from the new root.  Therefore, we know it needs to be
      // kept.
      assert(mParents.size() != 0 || this == xiKeep) : "Oops - no link left to new root";

      mParents.remove(ancestor);
      assert(linkageValid());
      return;
    }

    for (int index = 0; index < mNumChildren; index++)
    {
      if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
      {
        Object lChoice = mChildren[index];
        TreeNode lChild = null;

        TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
        if (edge != null)
        {
          lChild = (edge.getChildRef() == NULL_REF || edge.isHyperEdge()) ? null : get(edge.getChildRef());

          deleteEdge(index);

          //  No need to traverse hyper-edges in this process.  Also, since hyper-edges
          //  always follow the full set of direct edges in the array as soon as we see one
          //  we can stop looking
          if (edge.isHyperEdge())
          {
            break;
          }
        }

        // Free the child (at least from us) and free our edge to it.
        if (lChild != null && lChild != xiKeep)
        {
          lChild.freeFromAncestor(this, xiKeep);
        }
      }
    }

    freeNode();
  }

  private boolean hasSiblings()
  {
    for (TreeNode parent : mParents)
    {
      for (short index = 0; index < parent.mNumChildren; index++)
      {
        if (parent.mPrimaryChoiceMapping == null || parent.mPrimaryChoiceMapping[index] == index)
        {
          Object lChoice = parent.mChildren[index];

          //  An unexpanded edge or child node cannot be the same as this one
          TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
          if (edge == null || edge.getChildRef() == NULL_REF || get(edge.getChildRef()) != this)
          {
            return true;
          }
        }
      }
    }

    return false;
  }

  private boolean hasSiblinglessParents()
  {
    for (TreeNode parent : mParents)
    {
      if (parent == mTree.mRoot)
      {
        return false;
      }

      for (TreeNode grandParent : parent.mParents)
      {
        if (grandParent.mNumChildren > 1)
        {
          return false;
        }
      }
    }

    return true;
  }

  public boolean hasUnexpandedChoices()
  {
    if (mChildren == null)
    {
      return true;
    }

    for (Object lChoice : mChildren)
    {
      if (!(lChoice instanceof TreeEdge))
      {
        return true;
      }
    }

    return false;
  }

  private boolean allNephewsComplete()
  {
    for (TreeNode parent : mParents)
    {
      for (short index = 0; index < parent.mNumChildren; index++)
      {
        if (parent.mPrimaryChoiceMapping == null || parent.mPrimaryChoiceMapping[index] == index)
        {
          Object lChoice = parent.mChildren[index];

          TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
          if (edge == null || edge.getChildRef() == NULL_REF)
          {
            return false;
          }

          TreeNode child = get(edge.getChildRef());
          if (child != null)
          {
            if (!child.mComplete)
            {
              if (mNumChildren != 0)
              {
                for (short nephewIndex = 0; nephewIndex < child.mNumChildren; nephewIndex++)
                {
                  if (child.mPrimaryChoiceMapping == null || child.mPrimaryChoiceMapping[nephewIndex] == nephewIndex)
                  {
                    Object nephewChoice = child.mChildren[nephewIndex];

                    TreeEdge nephewEdge = (nephewChoice instanceof TreeEdge ? (TreeEdge)nephewChoice : null);
                    if (nephewEdge == null || nephewEdge.getChildRef() == NULL_REF)
                    {
                      return false;
                    }

                    TreeNode nephew = get(nephewEdge.getChildRef());

                    if (nephew == null || !nephew.mComplete)
                    {
                      return false;
                    }
                  }
                }
              }
              else
              {
                return false;
              }
            }
          }
          else
          {
            return false;
          }
        }
      }
    }

    return true;
  }

  /**
   * For each sibling of this node (from all parents), run the checkChildCompletion routine.
   */
  private void checkSiblingCompletion()
  {
    for (TreeNode lParent : mParents)
    {
      for (short lIndex = 0; lIndex < lParent.mNumChildren; lIndex++)
      {
        if (lParent.mPrimaryChoiceMapping == null || lParent.mPrimaryChoiceMapping[lIndex] == lIndex)
        {
          Object mChoice = lParent.mChildren[lIndex];

          TreeEdge lEdge = (mChoice instanceof TreeEdge ? (TreeEdge)mChoice : null);
          if (lEdge != null && lEdge.getChildRef() != NULL_REF)
          {
            TreeNode lChild = get(lEdge.getChildRef());
            if (lChild != null && lChild != this && lChild.mNumChildren != 0 && !lChild.mComplete)
            {
              lChild.checkChildCompletion(false);
            }
          }
        }
      }
    }
  }

  private boolean isBestMoveInAllUncles(Set<Move> moves, int roleIndex)
  {
    for (TreeNode parent : mParents)
    {
      for (short index = 0; index < parent.mNumChildren; index++)
      {
        if (parent.mPrimaryChoiceMapping == null || parent.mPrimaryChoiceMapping[index] == index)
        {
          Object lChoice = parent.mChildren[index];

          TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
          if (edge == null || edge.getChildRef() == NULL_REF)
          {
            return false;
          }

          TreeNode child = get(edge.getChildRef());
          if (child != this)
          {
            if (child == null || (child.mNumChildren == 0 && !child.mComplete))
            {
              return false;
            }

            if (!child.mComplete)
            {
              double bestOtherMoveScore = 0;
              double thisMoveScore = -Double.MAX_VALUE;
              for (short nephewIndex = 0; nephewIndex < child.mNumChildren; nephewIndex++)
              {
                if (child.mPrimaryChoiceMapping == null || child.mPrimaryChoiceMapping[nephewIndex] == nephewIndex)
                {
                  Object nephewChoice = child.mChildren[nephewIndex];

                  TreeEdge nephewEdge = (nephewChoice instanceof TreeEdge ? (TreeEdge)nephewChoice : null);
                  if (nephewEdge == null || nephewEdge.getChildRef() == NULL_REF)
                  {
                    if (moves.contains((nephewEdge == null ? (ForwardDeadReckonLegalMoveInfo)nephewChoice : nephewEdge.mPartialMove).mMove))
                    {
                      return false;
                    }
                    continue;
                  }
                  TreeNode nephew = get(nephewEdge.getChildRef());
                  if (nephew != null)
                  {
                    if (moves.contains(nephewEdge.mPartialMove.mMove))
                    {
                      if (nephew.getAverageScore(roleIndex) > thisMoveScore)
                      {
                        thisMoveScore = nephew.getAverageScore(roleIndex);
                      }
                    }
                    else
                    {
                      if (nephew.getAverageScore(roleIndex) > bestOtherMoveScore)
                      {
                        bestOtherMoveScore = nephew.getAverageScore(roleIndex);
                      }
                    }
                  }
                }
              }

              if (bestOtherMoveScore > thisMoveScore && thisMoveScore != -Double.MAX_VALUE)
              {
                return false;
              }
            }
            else if (child.getAverageScore(roleIndex) < 100-EPSILON)
            {
              return false;
            }
          }
        }
      }
    }

    return true;
  }

  private TreeNode worstCompleteCousin(Move move, int roleIndex)
  {
    TreeNode result = null;

    for (TreeNode parent : mParents)
    {
      for (short index = 0; index < parent.mNumChildren; index++)
      {
        if (parent.mPrimaryChoiceMapping == null || parent.mPrimaryChoiceMapping[index] == index)
        {
          Object lChoice = parent.mChildren[index];

          TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
          if (edge == null || edge.getChildRef() == NULL_REF)
          {
            return null;
          }
          TreeNode child = get(edge.getChildRef());

          if (child == null || (child.mNumChildren == 0 && !child.mComplete))
          {
            return null;
          }

          if (!child.mComplete)
          {
            for (short nephewIndex = 0; nephewIndex < child.mNumChildren; nephewIndex++)
            {
              Object nephewChoice = child.mChildren[nephewIndex];
              TreeEdge nephewEdge = (nephewChoice instanceof TreeEdge ? (TreeEdge)nephewChoice : null);
              ForwardDeadReckonLegalMoveInfo nephewMove = (nephewEdge == null ? (ForwardDeadReckonLegalMoveInfo)nephewChoice : nephewEdge.mPartialMove);

              if (move == nephewMove.mMove)
              {
                Object primaryChoice = (child.mPrimaryChoiceMapping == null ? nephewChoice : child.mChildren[child.mPrimaryChoiceMapping[nephewIndex]]);

                nephewEdge = (primaryChoice instanceof TreeEdge ? (TreeEdge)primaryChoice : null);
                if (nephewEdge == null || nephewEdge.getChildRef() == NULL_REF)
                {
                  return null;
                }

                TreeNode nephew = get(nephewEdge.getChildRef());
                if (nephew != null)
                {
                  if (!nephew.mComplete)
                  {
                    return null;
                  }
                  if (result == null ||
                      nephew.getAverageScore(roleIndex) < result.getAverageScore(roleIndex))
                  {
                    result = nephew;
                  }
                }
                else
                {
                  return null;
                }
              }
            }
          }
          else if (result == null ||
              child.getAverageScore(roleIndex) < result.getAverageScore(roleIndex))
          {
            result = child;
          }
        }
      }
    }

    return result;
  }

  @SuppressWarnings("null")
  public void checkChildCompletion(boolean xiCheckConsequentialSiblingCompletion)
  {
    int lRoleIndex = (mDecidingRoleIndex + 1) % mTree.mNumRoles;
    boolean lDecidingRoleWin = false;
    TreeNode lFloorDeciderNode = null;
    boolean lSiblingCheckNeeded = false;
    double selectedNonDeciderScore = Double.MAX_VALUE;
    boolean inhibitDecidingWinPropagation = false;

    for (int lii = 0; lii < mTree.mNumRoles; lii++)
    {
      mTree.mNodeAverageScores[lii] = 0;

      mTree.mUnderlyingStateMachine.getLatchedScoreRange(mState,
                                                         mTree.mRoleOrdering.roleIndexToRole(lii),
                                                         mTree.mLatchedScoreRangeBuffer);
      mTree.mRoleMaxScoresBuffer[lii] = mTree.mLatchedScoreRangeBuffer[1];
    }

    // Loop over all children, checking for completion.

    boolean lAllImmediateChildrenComplete = true;

    short lDeterminingChildRelativeCompletionDepth = Short.MAX_VALUE;

    double lBestValue = -1000;
    TreeNode lBestValueNode = null;
    boolean lMultipleBestChoices = false;

    int lNumUniqueChildren = 0;

    for (int index = 0; index < mNumChildren; index++)
    {
      if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
      {
        Object lChoice = mChildren[index];

        // Freed edges can occur when reconnecting complete nodes for a new root, of after stale hyper-edges have been
        // freed - just skip
        if (lChoice == null)
        {
          continue;
        }

        TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
        if (edge == null)
        {
          // We've found an unexpanded edge.  This almost certainly means that the child isn't complete.  Just need to
          // check that it isn't a pseudo-noop.  Pseudo-noops are not searched and do not form 'real' moves in this
          // factor so ignore them for completion propagation purposes.
          assert(lChoice instanceof ForwardDeadReckonLegalMoveInfo);

          if (!((ForwardDeadReckonLegalMoveInfo)lChoice).mIsPseudoNoOp)
          {
            // This is a real unexplored move.  The immediate children of this node aren't all complete.
            lAllImmediateChildrenComplete = false;
          }
        }
        else if (edge.isHyperEdge())
        {
          // We don't need to process hyper-edges to determine completion.  Furthermore, hyper-edges always come after
          // all real edges, so we're safe to break out of the loop completely now.
          break;
        }
        else
        {
          if ((edge.getChildRef() == NULL_REF) ||
              (get(edge.getChildRef()) == null))
          {
            // A child is missing, therefore they aren't all complete.
            lAllImmediateChildrenComplete = false;
          }
          else
          {
            // This is the normal case of a child that has been explored to some extent.
            TreeNode lNode = get(edge.getChildRef());
            lNumUniqueChildren++;

            if (!lNode.mComplete)
            {
              // This child isn't complete.  Therefore, we have at least 1 incomplete child.
              lAllImmediateChildrenComplete = false;
            }
            else
            {
              // This child is complete.  Check to see if it's the best result for the deciding role.
              //
              // In the event of several choices having the same score for the deciding role assume that the one with
              // the lowest sum of opponent scores will be chosen.  If several turn out equal we will construct a
              // blended value later that is somewhat pessimistic for us (but not completely so).
              double lDeciderScore = lNode.getAverageScore(lRoleIndex);
              double lNonDeciderScore = getSumOfOpponentScores(lRoleIndex);

              if ((lDeciderScore > lBestValue) ||
                  ((lDeciderScore == lBestValue) && (lNonDeciderScore <= selectedNonDeciderScore)))
              {
                // We've found a node that's at least as good as the best found so far.
                selectedNonDeciderScore = lNonDeciderScore;
                lBestValue = lDeciderScore;
                lBestValueNode = lNode;

                // If it's just the same as the best so far, note that there are multiple best choices.  If this child
                // is better than anything seen yet, then reset the multiple best choices flag.
                lMultipleBestChoices = ((lDeciderScore == lBestValue) &&
                                        (lNonDeciderScore == selectedNonDeciderScore));

                if (lBestValue > mTree.mRoleMaxScoresBuffer[lRoleIndex] - EPSILON)
                {
                  // Win for deciding role which they will choose unless it is also	a mutual win.
                  boolean lMutualWin = true;

                  for (int lii = 0; lii < mTree.mNumRoles; lii++)
                  {
                    if (lNode.getAverageScore(lii) < mTree.mRoleMaxScoresBuffer[lii] - EPSILON)
                    {
                      // This isn't a win for at least one other player and it is a win for the deciding role.  Assume
                      // that the deciding player will take this choice.
                      //
                      // When setting the completion depth, assume that the deciding player will take the shortest path
                      // amongst all forced wins.  (This is corrected later for simultaneous move games.)
                      if (lDeterminingChildRelativeCompletionDepth > (lNode.getCompletionDepth() - lNode.getDepth()))
                      {
                        lDeterminingChildRelativeCompletionDepth = (short)(lNode.getCompletionDepth() - lNode.getDepth());
                      }
                      lMutualWin = false;
                      break;
                    }
                  }

                  if (!lDecidingRoleWin)
                  {
                    lDecidingRoleWin |= !lMutualWin;

                    if (lDecidingRoleWin && mTree.mGameCharacteristics.isSimultaneousMove)
                    {
                      // Only complete on this basis if this move is our choice (complete info)	or wins in ALL cousin
                      // states also.
                      if (lRoleIndex != 0 && hasSiblings())
                      {
                        Set<Move> equivalentMoves = new HashSet<>();

                        if (mPrimaryChoiceMapping == null)
                        {
                          equivalentMoves.add(edge.mPartialMove.mMove);
                        }
                        else
                        {
                          for (short lSiblingIndex = 0; lSiblingIndex < mNumChildren; lSiblingIndex++)
                          {
                            if (mPrimaryChoiceMapping[lSiblingIndex] == index)
                            {
                              if (lSiblingIndex == index)
                              {
                                assert(mChildren[lSiblingIndex] instanceof TreeEdge);
                                equivalentMoves.add(((TreeEdge)mChildren[lSiblingIndex]).mPartialMove.mMove);
                              }
                              else
                              {
                                assert(mChildren[lSiblingIndex] instanceof ForwardDeadReckonLegalMoveInfo);
                                equivalentMoves.add(((ForwardDeadReckonLegalMoveInfo)mChildren[lSiblingIndex]).mMove);
                              }
                            }
                          }
                        }
                        if (!isBestMoveInAllUncles(equivalentMoves, lRoleIndex))
                        {
                          lDecidingRoleWin = false;
                        }
                        else
                        {
                          if (xiCheckConsequentialSiblingCompletion)
                          {
                            lSiblingCheckNeeded = true;
                          }
                        }
                      }
                    }
                  }
                  else if ((!lMutualWin) &&
                           (lRoleIndex != 0) &&
                           ((!mTree.mGameCharacteristics.getIsFixedSum()) ||
                            (mTree.mNumRoles >= 3)))
                  {
                    // If there are more than one choices that are decider wins for someone other than us and the game
                    // is non-fixed-sum with 3+ roles then inhibit completion propagation.  This is because in
                    // non-fixed-sum games it may be that a player has several winning moves, and we want to evaluate
                    // the worst one for us which may not be the first one to complete.
                    inhibitDecidingWinPropagation = true;
                  }
                }
              }

              if (mTree.mGameCharacteristics.isSimultaneousMove &&
                  !lDecidingRoleWin &&
                  lRoleIndex != 0 &&
                  (lFloorDeciderNode == null ||
                   lFloorDeciderNode.getAverageScore(lRoleIndex) < lNode.getAverageScore(lRoleIndex)))
              {
                //	Find the highest supported floor score for any of the moves equivalent to this one
                TreeNode lWorstCousinValueNode = null;
                short lFloorRelativeCompletionDepth = Short.MAX_VALUE;

                for (short siblingIndex = 0; siblingIndex < mNumChildren; siblingIndex++)
                {
                  if (siblingIndex == index || (mPrimaryChoiceMapping != null && mPrimaryChoiceMapping[siblingIndex] == index))
                  {
                    Object siblingChoice = mChildren[siblingIndex];
                    ForwardDeadReckonLegalMoveInfo siblingMove =
                      (siblingChoice instanceof ForwardDeadReckonLegalMoveInfo) ?
                                                                         (ForwardDeadReckonLegalMoveInfo)siblingChoice :
                                                                         ((TreeEdge)siblingChoice).mPartialMove;
                    TreeNode moveFloorNode = worstCompleteCousin(siblingMove.mMove, lRoleIndex);

                    if (moveFloorNode != null)
                    {
                      if (lWorstCousinValueNode == null ||
                          lWorstCousinValueNode.getAverageScore(lRoleIndex) < moveFloorNode.getAverageScore(lRoleIndex))
                      {
                        lWorstCousinValueNode = moveFloorNode;
                        if (lFloorRelativeCompletionDepth > lNode.getCompletionDepth() - lNode.getDepth())
                        {
                          lFloorRelativeCompletionDepth = (short)(lNode.getCompletionDepth() - lNode.getDepth());
                        }
                      }
                    }
                  }
                }

                if (lWorstCousinValueNode != null &&
                    (lFloorDeciderNode == null ||
                     lFloorDeciderNode.getAverageScore(lRoleIndex) < lWorstCousinValueNode.getAverageScore(lRoleIndex)))
                {
                  lFloorDeciderNode = lWorstCousinValueNode;
                  lDeterminingChildRelativeCompletionDepth = lFloorRelativeCompletionDepth;
                }
              }
            }

            for (int lii = 0; lii < mTree.mNumRoles; lii++)
            {
              mTree.mNodeAverageScores[lii] += lNode.getAverageScore(lii);
            }
          }
        }
      }
    }

    for (int lii = 0; lii < mTree.mNumRoles; lii++)
    {
      mTree.mNodeAverageScores[lii] /= lNumUniqueChildren;
    }

    if (lAllImmediateChildrenComplete &&
        !lDecidingRoleWin &&
        mTree.mGameCharacteristics.isSimultaneousMove &&
        lRoleIndex != 0)
    {
      mAllChildrenComplete = true;

      // If the best we can do from this node is no better than the supported floor we don't require all nephews to be
      // complete to complete this node at the floor.
      if (!hasSiblings() ||
          (lFloorDeciderNode != null && lFloorDeciderNode.getAverageScore(lRoleIndex) +
          EPSILON >= lBestValueNode.getAverageScore(lRoleIndex)))
      {
        //	There was only one opponent choice so this is not after all
        //	incomplete information, so complete with the best choice for
        //	the decider
        lDecidingRoleWin = true;
      }
      else
      {
        //	To auto complete with simultaneous turn and no deciding role win
        //	we require that all nephews be complete or that all alternatives
        //	are anyway equivalent
        boolean lAllNephewsComplete = allNephewsComplete();

        for (int lii = 0; lii < mTree.mNumRoles; lii++)
        {
          if (Math.abs(mTree.mNodeAverageScores[lii] - lBestValueNode.getAverageScore(lii)) > EPSILON)
          {
            lAllImmediateChildrenComplete = lAllNephewsComplete;
            break;
          }
        }
      }

      // If all our immediate children are complete and we've been asked to check for sibling completion, flag that
      // we'll need to do that (at the end of this routine).
      if (lAllImmediateChildrenComplete && xiCheckConsequentialSiblingCompletion)
      {
        lSiblingCheckNeeded = true;
      }

      // It is possible for all children to be terminal, in which case no values will ever have been propagated back
      // from them (as we don't rollout from a terminal state).  In a simultaneous move game such a situation will
      // result in this node's average score not being set initially, since completion will not occur until all nephews
      // are complete.  We address this case by setting the scores provisionally as an average of the children (but
      // not marking complete yet).
      if ((!lAllImmediateChildrenComplete) && (mNumUpdates == 0))
      {
        for (int lii = 0; lii < mTree.mNumRoles; lii++)
        {
          setAverageScore(lii, mTree.mNodeAverageScores[lii]);
        }
      }
    }

    if (lAllImmediateChildrenComplete || (lDecidingRoleWin && !inhibitDecidingWinPropagation))
    {
      if ((lDeterminingChildRelativeCompletionDepth == Short.MAX_VALUE) ||
          (mTree.mGameCharacteristics.isSimultaneousMove))
      {
        // If there was no winning choice but everything is complete then the depth is the maximum of the non-winning
        // choice alternatives.  Note - this may be slightly misleading for non-fixed-sum games that are expected to end
        // at intermediate score values, but should operate correctly in other cases, and give reasonable indicative
        // results in all cases.
        //
        // Also, for simultaneous move games where the deciding role has a forced win (including in all cousins), we
        // need to select the longest completion depth as our completion depth (rather than the shortest completion
        // depth selected above) because we have to assume that the opponent(s) will play for the longest path.

        lDeterminingChildRelativeCompletionDepth = 0;

        for (short lIndex = 0; lIndex < mNumChildren; lIndex++)
        {
          if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[lIndex] == lIndex)
          {
            Object lChoice = mChildren[lIndex];

            TreeEdge lEdge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
            if (lEdge != null)
            {
              if (lEdge.hyperSuccessor != null)
              {
                break;
              }
              if (lEdge.getChildRef() != NULL_REF && get(lEdge.getChildRef()) != null)
              {
                TreeNode lNode = get(lEdge.getChildRef());
                if (lNode.mComplete && lDeterminingChildRelativeCompletionDepth < lNode.getCompletionDepth() - lNode.getDepth())
                {
                  lDeterminingChildRelativeCompletionDepth = (short)(lNode.getCompletionDepth() - lNode.getDepth());
                }
              }
            }
          }
        }
      }

      mTree.mNumCompletionsProcessed++;

      //	Opponent's choice which child to take, so take their
      //	best value and crystalize as our value.   However, if it's simultaneous
      //	move complete with the average score since
      //	opponents cannot make the pessimal (for us) choice reliably
      if (mTree.mGameCharacteristics.isSimultaneousMove && !lDecidingRoleWin)
      {
        //	If all option are complete but not decisive due to incomplete information
        //  arising form the opponent's simultaneous turn then take a weighted average
        //  of the child node scores, weighting them by the average cousin value of the
        //  deciding role
        if (lRoleIndex != 0)
        {
          mTree.mCousinMovesCachedFor = NULL_REF;

          //  Weight the values by their average cousin score
          double totalWeight = 0;
          for (int lii = 0; lii < mTree.mNumRoles; lii++)
          {
            mTree.mBlendedCompletionScoreBuffer[lii] = 0;
          }

          for (short lIndex = 0; lIndex < mNumChildren; lIndex++)
          {
            if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[lIndex] == lIndex)
            {
              Object lChoice = mChildren[lIndex];

              // Pseudo-noops in factored games can still be unexpanded at this point.
              if (lChoice instanceof TreeEdge)
              {
                TreeEdge lEdge = (TreeEdge)lChoice;

                assert(lEdge.getChildRef() != NULL_REF);

                TreeNode lNode = get(lEdge.getChildRef());
                assert(lNode != null);
                assert(lNode.mComplete);

                // Add epsilon in case all are 0.
                double lChooserScore = getAverageCousinMoveValue(lEdge, lRoleIndex) + EPSILON;

                for (int lii = 0; lii < mTree.mNumRoles; lii++)
                {
                  mTree.mBlendedCompletionScoreBuffer[lii] += lChooserScore * lNode.getAverageScore(lii);
                }
                totalWeight += lChooserScore;
              }
            }
          }

          for (int lii = 0; lii < mTree.mNumRoles; lii++)
          {
            mTree.mBlendedCompletionScoreBuffer[lii] /= totalWeight;
          }

          //  If a move provides a better-than-worst case in all uncles it provides a support
          //  floor the the worst that we can do with perfect play, so use that if its larger than
          //  what we would otherwise use
          if (lFloorDeciderNode != null &&
              lFloorDeciderNode.getAverageScore(lRoleIndex) > mTree.mBlendedCompletionScoreBuffer[lRoleIndex])
          {
            for (int lii = 0; lii < mTree.mNumRoles; lii++)
            {
              mTree.mBlendedCompletionScoreBuffer[lii] = lFloorDeciderNode.getAverageScore(lii);
            }
          }
        }
        else
        {
          //  For the final role we're transitioning to an actual fully decided new state so the
          //  appropriate choice is the best one for the chooser
          for (int lii = 0; lii < mTree.mNumRoles; lii++)
          {
            mTree.mBlendedCompletionScoreBuffer[lii] = lBestValueNode.getAverageScore(lii);
          }
        }
        markComplete(mTree.mBlendedCompletionScoreBuffer, (short)(lDeterminingChildRelativeCompletionDepth + mDepth + 1));
      }
      else if (lMultipleBestChoices)
      {
        //  Weight the values by 200-ourScore (i.e. - assume the chooser will prefer
        //  to make us lose, but not to the point where it totally discounts the other lines)
        //  This works better than assuming worst case, which amounts to assuming everyone else
        //  will cooperate against us, and makes giving up look as attractive as trying for
        //  a possible win that is not completely forced
        double totalWeight = 0;
        for (int lii = 0; lii < mTree.mNumRoles; lii++)
        {
          mTree.mBlendedCompletionScoreBuffer[lii] = 0;
        }

        for (short lIndex = 0; lIndex < mNumChildren; lIndex++)
        {
          if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[lIndex] == lIndex)
          {
            Object lChoice = mChildren[lIndex];

            //  Might be that two children completed concurrently (relative to opportunities
            //  to process the node completion queue), in which case we may have multiple
            //  decider-wins options even if some other children are unexpanded
            if (lChoice instanceof TreeEdge)
            {
              TreeEdge lEdge = (TreeEdge)lChoice;

              if (lEdge.getChildRef() != NULL_REF)
              {
                TreeNode lNode = get(lEdge.getChildRef());
                assert(lNode != null);

                if (lNode.getAverageScore(lRoleIndex) == lBestValue)
                {
                  double weight = (200 - lNode.getAverageScore(0));
                  for (int lii = 0; lii < mTree.mNumRoles; lii++)
                  {
                    mTree.mBlendedCompletionScoreBuffer[lii] += weight*lNode.getAverageScore(lii);
                  }
                  totalWeight += weight;
                }
              }
            }
          }
        }
        for (int lii = 0; lii < mTree.mNumRoles; lii++)
        {
          mTree.mBlendedCompletionScoreBuffer[lii] /= totalWeight;
        }

        markComplete(mTree.mBlendedCompletionScoreBuffer, (short)(lDeterminingChildRelativeCompletionDepth + mDepth + 1));
      }
      else
      {
        markComplete(lBestValueNode, (short)(lDeterminingChildRelativeCompletionDepth + mDepth + 1));
      }

      if (lSiblingCheckNeeded)
      {
        checkSiblingCompletion();
      }
    }

    mLastSelectionMade = -1;
  }

  private double getSumOfOpponentScores(int xiRoleIndex)
  {
    double lSumOfOpponentScores = 0;

    for (int lOpponentRoleIndex = 0; lOpponentRoleIndex < mTree.mNumRoles; lOpponentRoleIndex++)
    {
      if (lOpponentRoleIndex != xiRoleIndex)
      {
        lSumOfOpponentScores += getAverageScore(lOpponentRoleIndex);
      }
    }

    return lSumOfOpponentScores;
  }

  /**
   * Reset a node ready for re-use.
   *
   * @param xiTree - the tree in which the node is to be re-used (or null if not yet known).
   */
  public void reset(MCTSTree xiTree)
  {
    // Throughout this function, we do our best to reset existing objects wherever possible, rather than discarding the
    // old ones and allocating new ones.  This reduces the GC burden.

    // Increment the sequence number for this node so that any remaining TreeNodeRefs pointing to the previous
    // incarnation can spot that we've re-used this node under their feet.
    mRef += 0x100000000L;
    freeChildren();

    // Reset primitives.
    mNumVisits = 0;
    mNumUpdates = 0;
    mTerminal = false;
    mLocalSearchStatus = LocalSearchStatus.LOCAL_SEARCH_UNSEARCHED;
    mLastSelectionMade = -1;
    mComplete = false;
    mAllChildrenComplete = false;
    assert(mFreed || (xiTree == null));
    mFreed = (xiTree == null);
    mDepth = -1;
    mSweepSeq = 0;
    //sweepParent = null;
    mHeuristicValue = 0;
    mHeuristicWeight = 0;
    mHyperExpansionDepth = 0;
    mUpdatesToNormalization = NORMALIZATION_WARMUP_PERIOD;

    // Reset objects (without allocating new ones).
    mTree = xiTree;
    mParents.clear();
    mState.clear();

    // Reset score values
    if (xiTree != null)
    {
      for (int lii = 0; lii < xiTree.mNumRoles; lii++)
      {
        setAverageScore(lii, 0);
        setAverageSquaredScore(lii, 0);
      }
    }

    // Reset remaining objects.  These will need to be re-allocated later.  That's a shame, because it produces
    // unnecessary garbage, but sorting it won't be easy.
    mPrimaryChoiceMapping = null;
  }

  TreeNode getChild(short index)
  {
    assert(index < mNumChildren);

    Object lChoice = mChildren[index];
    TreeEdge edge;

    if (lChoice instanceof TreeEdge)
    {
      edge = (TreeEdge)lChoice;
    }
    else
    {
      return null;
    }

    if (edge.getChildRef() == NULL_REF)
    {
      return null;
    }

    return get(edge.getChildRef());
  }

  TreeNode createChildIfNeccessary(short index, ForwardDeadReckonLegalMoveInfo[] jointPartialMove, int choosingRoleIndex)
  {
    assert(index < mNumChildren);

    Object lChoice = mChildren[index];
    TreeEdge edge;

    if (lChoice instanceof TreeEdge)
    {
      edge = (TreeEdge)lChoice;
    }
    else
    {
      ForwardDeadReckonLegalMoveInfo move = (ForwardDeadReckonLegalMoveInfo)mChildren[index];
      if (move.mIsPseudoNoOp && this != mTree.mRoot)
      {
        return null;
      }

      edge = mTree.mEdgePool.allocate(mTree.mTreeEdgeAllocator);
      edge.setParent(this, move);
      mChildren[index] = edge;

      assert(edge != null);
    }

    if (edge.getChildRef() == NULL_REF)
    {
      //  Cannot re-create children of hyper-edges - once they are gone the hyper-edge is dead
      if (edge.isHyperEdge())
      {
        return null;
      }

      jointPartialMove[choosingRoleIndex] = edge.mPartialMove;

      createChildNodeForEdge(edge, jointPartialMove);

      assert(linkageValid());
    }

    return get(edge.getChildRef());
  }

  long getRef()
  {
    return mRef;
  }

  public double getAverageScore(int roleIndex)
  {
    return mTree.mScoreVectorPool.getAverageScore(mInstanceID, roleIndex);
  }

  public void setAverageScore(int roleIndex, double value)
  {
    assert(-EPSILON<=value);
    assert(100+EPSILON>=value);
    mTree.mScoreVectorPool.setAverageScore(mInstanceID, roleIndex, value);

    if ( mTree.mMixiMaxBias > 0 && roleIndex == (mDecidingRoleIndex+1)%mTree.mNumRoles && value > mBestDecidingScore )
    {
      mBestDecidingScore = value;
    }
  }

  public double getAverageSquaredScore(int roleIndex)
  {
    return mTree.mScoreVectorPool.getAverageSquaredScore(mInstanceID, roleIndex);
  }

  public void setAverageSquaredScore(int roleIndex, double value)
  {
    mTree.mScoreVectorPool.setAverageSquaredScore(mInstanceID, roleIndex, value);
  }

  void validate(boolean recursive)
  {
    for (short index = 0; index < mNumChildren; index++)
    {
      if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
      {
        Object lChoice = mChildren[index];

        TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
        if (edge != null)
        {
          TreeNode lNode = get(edge.getChildRef());
          if (lNode != null)
          {
            if (!lNode.mParents.contains(this))
            {
              LOGGER.error("Missing parent link");
            }
            if (lNode.mComplete &&
                lNode.getAverageScore(mDecidingRoleIndex) > 100-EPSILON &&
                !mComplete && !mTree.mCompletedNodeRefQueue.contains(lNode.getRef()))
            {
              LOGGER.error("Completeness constraint violation");
            }
            if ((lNode.mDecidingRoleIndex) == mDecidingRoleIndex && mTree.mGameCharacteristics.numRoles != 1)
            {
              LOGGER.error("Descendant type error");
            }

            if (recursive)
            {
              lNode.validate(true);
            }
          }
        }
      }
    }

    if (mParents.size() > 0)
    {
      int numInwardVisits = 0;

      for (TreeNode parent : mParents)
      {
        for (short index = 0; index < parent.mNumChildren; index++)
        {
          if (parent.mPrimaryChoiceMapping == null || parent.mPrimaryChoiceMapping[index] == index)
          {
            Object lChoice = parent.mChildren[index];

            TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
            if (edge != null && edge.getChildRef() != NULL_REF && get(edge.getChildRef()) == this)
            {
              numInwardVisits += edge.getNumChildVisits();
              break;
            }
          }
        }
      }

      if (numInwardVisits > mNumVisits)
      {
        LOGGER.error("Linkage counts do not add up");
      }
    }
  }

  /**
   * Mark all the nodes that will be in the live part of the graph with a sequence number.  When clearing out the graph,
   * if we meet a marked node, we know that it can't be deleted because it's still reachable.
   */
  private void markTreeForSweep(TreeNode parent)
  {
    assert(parent == null || mParents.contains(parent)) : "Marked node for sweep from unexpected parent";
    if (mSweepSeq != mTree.mSweepInstance)
    {
      //sweepParent = parent;
      mSweepSeq = mTree.mSweepInstance;
      for (short index = 0; index < mNumChildren; index++)
      {
        if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
        {
          Object lChoice = mChildren[index];

          TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
          if (edge != null)
          {
            //  No need to traverse hyper-edges in this sweep.  Also, since hyper-edges
            //  always follow the full set of direct edges in the array as soon as we see one
            //  we can stop looking
            if (edge.isHyperEdge())
            {
              break;
            }
            if (edge.getChildRef() != NULL_REF && get(edge.getChildRef()) != null)
            {
              get(edge.getChildRef()).markTreeForSweep(this);
            }
          }
        }
      }
    }
  }

  /**
   * Free this node.
   *
   * Before calling this method, the caller must have freed all children for whom this is the only parent.
   */
  private void freeNode()
  {
    assert (!mFreed) : "Attempt to free a node that has already been freed";

    mTree.nodeFreed(this);

    if (mComplete)
    {
      mTree.mNumCompletedBranches--;
    }

    if (MCTSTree.CREATING_DATABASE)
    {
      mTree.storeState(mState, mTerminal, mComplete, mNumVisits, getAverageScore(0));
    }

    // LOGGER.debug("    Freeing (" + ourIndex + "): " + state);
    mFreed = true;
    mTree.mNodePool.free(this, mInstanceID);
    mRef += 0x100000000L;
  }

  /**
   * Free all nodes apart from those reachable from the specified descendant
   * of this node
   * @param descendant
   * @return percentage of allocated nodes freed
   */
  public int freeAllBut(TreeNode descendant)
  {
    LOGGER.info("Freeing redundant state");
    LOGGER.debug("Free all but rooted in state: " + descendant.mState);

    int numNodesInUseBeforeTrim = mTree.mNodePool.getNumItemsInUse();

    // Mark the live portions of the tree.  This allows us to tidy up the state without repeatedly visiting live parts
    // of the tree.
    mTree.mSweepInstance++;
    descendant.markTreeForSweep(null);
    descendant.mParents.clear(); //	Do this here to allow generic orphan checking in node freeing
                                //	without tripping over this special case
    LOGGER.info("Sweep complete, beginning delete...");

    for (int index = 0; index < mNumChildren; index++)
    {
      if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
      {
        Object lChoice = mChildren[index];

        TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
        if (edge != null)
        {
          //  Note that hyper-edges represent duplicate paths that are not back-linked
          //  by parentage, so node-freeing via hyper-edges is not needed (or correct)
          TreeNode lNode = (edge.getChildRef() == NULL_REF || edge.isHyperEdge()) ? null : get(edge.getChildRef());

          // Delete our edge to the child anyway.  (We only set "descendant" when re-rooting the tree.  In that case,
          // we don't need the edge any more.)
          deleteEdge(index);

          // Free the child (at least from us)
          if (lNode != null)
          {
            lNode.freeFromAncestor(this, descendant);
          }
        }
      }
    }

    freeNode();
    mTree.mSweepInstance++;

    int numNodesInUseAfterTrim = mTree.mNodePool.getNumItemsInUse();
    int percentageFreed = (100*(numNodesInUseBeforeTrim-numNodesInUseAfterTrim))/numNodesInUseBeforeTrim;

    LOGGER.info("Freed " + percentageFreed + "% of allocated nodes (" + (numNodesInUseBeforeTrim-numNodesInUseAfterTrim) + " of " + numNodesInUseBeforeTrim + ")");

    return percentageFreed;
  }

  private void deleteEdge(int xiChildIndex)
  {
    assert(mChildren[xiChildIndex] instanceof TreeEdge) : "Asked to delete a non-edge";
    TreeEdge lEdge = (TreeEdge)mChildren[xiChildIndex];

    // Replace the edge with its move (so that it can be re-expanded later if required).
    if (!lEdge.isHyperEdge())
    {
      mChildren[xiChildIndex] = lEdge.mPartialMove;
    }
    else
    {
      mChildren[xiChildIndex] = null;
    }

    //  Make sure it is reset when freed not just whn re-allocated as it may still
    //  be referenced by a hyper-path, which will check validity via the refs
    lEdge.reset();
    // Return the edge to the pool.
    mTree.mEdgePool.free(lEdge, 0);
  }

  private void deleteHyperEdge(int xiChildIndex)
  {
    //  If the hyper-edge bing deleted is the last on through this mov then
    //  the principal (non-hyper) edge for that move must become selectable
    //  again
    Move move = ((TreeEdge)mChildren[xiChildIndex]).mPartialMove.mMove;

    deleteEdge(xiChildIndex);

    TreeEdge principalEdge = null;

    for (int lii = 0; lii < mNumChildren; lii++)
    {
      Object lChoice = mChildren[lii];

      if (lChoice instanceof TreeEdge)
      {
        TreeEdge edge = (TreeEdge)lChoice;

        if (edge.mPartialMove.mMove == move)
        {
          if (edge.hyperSuccessor == null)
          {
            principalEdge = edge;
          }
          else
          {
            //  Still extant hyper-edge
            return;
          }
        }
      }
    }

    if (principalEdge != null)
    {
      assert(!principalEdge.isSelectable());
      principalEdge.setIsSelectable(true);
    }
  }

  /**
   * Find an extant node in the tree (if any) for a specified state
   * within a given depth of the root
   * @param targetState
   * @param maxDepth
   * @return matching node or null
   */
  public TreeNode findNode(ForwardDeadReckonInternalMachineState targetState, int maxDepth)
  {
    if (mState.equals(targetState) && mDecidingRoleIndex == mTree.mNumRoles - 1)
    {
      return this;
    }
    else if (maxDepth == 0)
    {
      return null;
    }

    for (short index = 0; index < mNumChildren; index++)
    {
      if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
      {
        Object lChoice = mChildren[index];

        TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
        if (edge != null)
        {
          if (edge.getChildRef() != NULL_REF && get(edge.getChildRef()) != null)
          {
            TreeNode childResult = get(edge.getChildRef()).findNode(targetState, maxDepth - 1);
            if (childResult != null)
            {
              return childResult;
            }
          }
        }
      }
    }

    return null;
  }

  public boolean disposeLeastLikelyNode()
  {
    TreeEdge leastLikely = selectLeastLikelyExpandedNode(null);

    if (leastLikely != null)
    {
      //  Free the children of the chosen node from its parentage
      //  and de-expand it
      leastLikely.setHasBeenTrimmed();
      get(leastLikely.getChildRef()).unexpand();
      //validateAll();

      return true;
    }

    return false;
  }

  private void unexpand()
  {
    assert(mNumChildren != 0);
    assert(linkageValid());

    for (int index = 0; index < mNumChildren; index++)
    {
      Object lChoice = mChildren[index];
      if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
      {
        if (lChoice instanceof TreeEdge)
        {
          TreeEdge edge = (TreeEdge)lChoice;
          TreeNode child = (edge.getChildRef() == NULL_REF || edge.isHyperEdge()) ? null : get(edge.getChildRef());

          deleteEdge(index);

          if (child != null)
          {
            child.freeFromAncestor(this, null);
          }
        }
      }
      else
      {
        assert(lChoice instanceof ForwardDeadReckonLegalMoveInfo);
      }
    }

    freeChildren();

    mLastSelectionMade = -1;
  }

  public TreeEdge selectLeastLikelyExpandedNode(TreeEdge from)
  {
    int selectedIndex = -1;
    double bestValue = -Double.MAX_VALUE;

    //	Find the role this node is choosing for
    int roleIndex = (mDecidingRoleIndex + 1) % mTree.mNumRoles;

    mTree.mCousinMovesCachedFor = NULL_REF;

    //validateAll();
    if (mFreed)
    {
      LOGGER.warn("Encountered freed node in tree walk");
    }
    if (mNumChildren != 0)
    {
      if (mNumChildren == 1)
      {
        Object lChoice = mChildren[0];

        if (lChoice instanceof TreeEdge)
        {
          long cr = ((TreeEdge)lChoice).getChildRef();
          //  Don't descend into unexpanded nodes
          if (cr != NULL_REF && get(cr) != null && !get(cr).isUnexpanded())
          {
            selectedIndex = 0;
          }
        }
      }
      else
      {
        for (int lii = 0; lii < mNumChildren; lii++)
        {
          Object lChoice = mChildren[lii];

          if (lChoice instanceof TreeEdge)
          {
            TreeEdge edge = (TreeEdge)lChoice;

            //  We just traverse the regular tree looking for nodes to free, so
            //  stop when we reach the start of the hyper edges
            if(edge.isHyperEdge())
            {
              break;
            }

            long cr = edge.getChildRef();
            if (cr != NULL_REF)
            {
              TreeNode c = get(cr);
              //  Note - if the node has been trimmed we must not delete the edge
              //  because an unexpended edge asserts non-terminality of its child which
              //  we do not know.  Only selecting back through this edge can establish
              //  correct semantics
              if (c != null)
              {
                if (c.mFreed)
                {
                  LOGGER.warn("Encountered freed child node in tree walk");
                }
                //  Don't descend into unexpanded nodes
                if (!c.isUnexpanded())
                {
                  double uctValue;
                  if (edge.getNumChildVisits() == 0)
                  {
                    uctValue = -1000;
                  }
                  else
                  {
                    uctValue = -explorationUCT(mNumVisits,
                                               edge,
                                               roleIndex) -
                                               exploitationUCT(edge, roleIndex);
                  }
                  //  Add a small amount of noise to cause the subtrees we prune from
                  //  to spread around amongst reasonable candidates rather than pruning
                  //  entire subtrees which will quickly back up to low depths
                  //  in the tree which are more likely to require re-expansion
                  uctValue += mTree.mRandom.nextDouble()/20;

                  if (uctValue > bestValue)
                  {
                    selectedIndex = lii;
                    bestValue = uctValue;
                  }
                }
              }
            }
          }
        }
      }
    }

    //validateAll();
    if (selectedIndex != -1)
    {
      assert(mChildren[selectedIndex] instanceof TreeEdge);
      TreeEdge selectedEdge = (TreeEdge)mChildren[selectedIndex];

      return get(selectedEdge.getChildRef()).selectLeastLikelyExpandedNode(selectedEdge);
    }

    //  Children of the root should never be trimmed.  For us to have wanted to unexpand
    //  the root all its children must be unexpanded.  This is possible in factored games
    //  where one factor has a complete root, so all node allocation occurs in the other
    //  factor(s)'s tree(s), so it is only a warned condition at this level
    if (from == null)
    {
      if (mTree.mFactor == null)
      {
        LOGGER.warn("Attempt to trim child of root");
      }
    }

    return from;
  }

  private StateInfo calculateTerminalityAndAutoExpansion(ForwardDeadReckonInternalMachineState theState)
  {
    StateInfo result = mTree.mGameSearcher.mStateInfoBuffer;

    result.isTerminal = false;

    // Check if the goal value is latched.
    if (mTree.mUnderlyingStateMachine.scoresAreLatched(theState))
    {
      result.isTerminal = true;

      for (int lii = 0; lii < mTree.mNumRoles; lii++)
      {
        mTree.mUnderlyingStateMachine.getLatchedScoreRange(theState,
                                                           mTree.mRoleOrdering.roleIndexToRole(lii),
                                                           mTree.mLatchedScoreRangeBuffer);

        assert(mTree.mLatchedScoreRangeBuffer[0] == mTree.mLatchedScoreRangeBuffer[1]);
        result.terminalScore[lii] = mTree.mLatchedScoreRangeBuffer[0];
      }
    }
    else
    {
      if (mTree.mUnderlyingStateMachine.getBaseFilter() instanceof NullStateMachineFilter)
      {
        result.isTerminal = mTree.mUnderlyingStateMachine.isTerminalDedicated(theState);
        assert(result.isTerminal == mTree.mSearchFilter.isFilteredTerminal(theState, mTree.mUnderlyingStateMachine));
      }
      else
      {
        result.isTerminal = mTree.mSearchFilter.isFilteredTerminal(theState, mTree.mUnderlyingStateMachine);
      }

      if ( result.isTerminal )
      {
        for (int lii = 0; lii < mTree.mNumRoles; lii++)
        {
          result.terminalScore[lii] = mTree.mUnderlyingStateMachine.getGoal(theState, mTree.mRoleOrdering.roleIndexToRole(lii));
        }
      }
    }

    if (result.isTerminal)
    {
      // Add win bonus
      //  TODO - this needs adjustment to respect latched score ranges or else
      //  a best possible result that is a draw will not register as a decisively
      //  propagatable completion score.  This doesn't matter for fixed sum games
      //  that can only end with score of 0,50,100 or for puzzles, but would matter
      //  for more complex scoring structures in multi-player games (issue tracked in GITHub)
      for (int lii = 0; lii < mTree.mNumRoles; lii++)
      {
        double iScore = result.terminalScore[lii];
        mTree.mBonusBuffer[lii] = 0;

        for (int ljj = 0; ljj < mTree.mNumRoles; ljj++)
        {
          if (ljj != lii)
          {
            double jScore = result.terminalScore[ljj];

            if (iScore >= jScore)
            {
              double bonus = mTree.mGameCharacteristics.getCompetitivenessBonus();

              if (iScore > jScore)
              {
                bonus *= 2;
              }

              mTree.mBonusBuffer[lii] += bonus;
            }
          }
        }
      }

      for (int lii = 0; lii < mTree.mNumRoles; lii++)
      {
        result.terminalScore[lii] = ((result.terminalScore[lii] + mTree.mBonusBuffer[lii]) * 100) /
            (100 + 2 * (mTree.mNumRoles - 1) *
                mTree.mGameCharacteristics.getCompetitivenessBonus());
      }
    }

    return result;
  }

  void createChildNodeForEdge(TreeEdge edge, ForwardDeadReckonLegalMoveInfo[] jointPartialMove)
  {
    boolean isPseudoNullMove = (mTree.mFactor != null);
    int roleIndex = (mDecidingRoleIndex + 1) % mTree.mNumRoles;

    for (int lii = 0; lii <= ((mTree.mRemoveNonDecisionNodes && mNumChildren > 1) ? mTree.mNumRoles-1 : roleIndex); lii++)
    {
      if (jointPartialMove[lii].mInputProposition != null)
      {
        isPseudoNullMove = false;
      }
    }

    assert(mState != null);
    assert(edge.getChildRef() == NULL_REF);

    ForwardDeadReckonInternalMachineState newState = null;
    if (roleIndex == mTree.mNumRoles - 1 || (mTree.mRemoveNonDecisionNodes && mNumChildren > 1))
    {
      newState = mTree.mNextStateBuffer;
      mTree.mUnderlyingStateMachine.getNextState(mState, mTree.mFactor, jointPartialMove, newState);

      //  In a factorized game we need to normalize the generated state
      //  so as to not fall foul of potential corruption of the non-factor
      //  element engendered by not making a move in other factors
      if (mTree.mFactor != null)
      {
        mTree.makeFactorState(newState);
      }
    }

    createChildNodeForEdgeWithAssertedState(edge, newState, 0, isPseudoNullMove);
  }

  /**
   * @param edge
   * @param newState
   * @param extraDepthIncrement
   * @param isPseudoNullMove
   */
  void createChildNodeForEdgeWithAssertedState(TreeEdge edge, ForwardDeadReckonInternalMachineState newState, int extraDepthIncrement, boolean isPseudoNullMove)
  {
    TreeNode newChild = mTree.allocateNode(newState, this, isPseudoNullMove);
    int roleIndex = (mDecidingRoleIndex + 1) % mTree.mNumRoles;

    assert(!newChild.mFreed);
    edge.setChild(newChild);

    //  Don't overwrite the deciding role index if the child we got was actually a transposition into an already
    //  expanded node, as it could be some way down a forced response sequence
    if (newChild.mDepth == -1)
    {
      newChild.mDecidingRoleIndex = ((mTree.mRemoveNonDecisionNodes && mNumChildren > 1) ? mTree.mNumRoles-1 : roleIndex);

      if (roleIndex != mTree.mNumRoles - 1 && (!mTree.mRemoveNonDecisionNodes || mNumChildren == 1))
      {
        // assert(newState == null);
        newChild.setState(mState);
      }

      if (mTree.mGameSearcher.mUseGoalGreedy)
      {
        newChild.mHeuristicValue = mTree.mUnderlyingStateMachine.getGoal(newState, mTree.mOurRole);
      }
    }

    //  If this was a transposition to an existing node it can be linked at multiple depths.
    //  Give it the lowest depth at which it has been seen, as this is guaranteed to preserve
    //  correct implicit semantics of unexpanded children (assertion of non-terminality if below
    //  min game length depth)
    int expectedDepth;
    if (mTree.mRemoveNonDecisionNodes && mNumChildren > 1)
    {
      expectedDepth = ((mDepth / mTree.mNumRoles + 1) * mTree.mNumRoles +
                       (newChild.mDecidingRoleIndex + 1) % mTree.mNumRoles) +
                       extraDepthIncrement;
    }
    else
    {
      expectedDepth = mDepth + 1 + extraDepthIncrement;
    }
    if (newChild.mDepth < 0 || newChild.mDepth > expectedDepth)
    {
      newChild.mDepth = (short)expectedDepth;
    }

    assert(newChild.mDepth % mTree.mNumRoles == (newChild.mDecidingRoleIndex + 1) % mTree.mNumRoles);

    // If we transition into a complete node we need to have it re-process that completion again in the light of the
    // new parentage.
    if (newChild.mComplete)
    {
      mTree.mCompletedNodeRefQueue.add(newChild.getRef());
    }
  }

  private void considerPathToAsPlan()
  {
    assert(mTerminal);

    GamePlan plan = mTree.mGameSearcher.getPlan();
    if (plan != null)
    {
      List<ForwardDeadReckonLegalMoveInfo> fullPlayoutList = new LinkedList<>();

      //  Pick arbitrary path back to the root
      TreeNode current = this;

      while(current.getDepth() != mTree.mRoot.getDepth())
      {
        assert(!current.mParents.isEmpty());
        TreeNode parent = current.mParents.get(0);

        if (current.mDecidingRoleIndex == 0)
        {
          assert(parent.mNumChildren > 0);
          for (Object lChoice : parent.mChildren)
          {
            if (lChoice instanceof TreeEdge)
            {
              TreeEdge edge = (TreeEdge)lChoice;

              if (edge.getChildRef() != NULL_REF && get(edge.getChildRef()) == current)
              {
                fullPlayoutList.add(0, edge.mPartialMove);
              }
            }
          }
        }

        current = parent;
      }

      //  It is possible to encounter a terminal node during an expansion while processing
      //  setting a new root (and reconnecting it to the existing tree).  This happens before
      //  the tree is trimmed to remove no-longer referenced nodes, and in this processing it is
      //  possible that parent paths can lead outside the scope of the new root (since we just
      //  follow one path arbitrarily).  Such paths are not valid as plans, so if we find ourselves
      //  at/above the root level but not at the root we discard it.  Typically this only happens
      //  when enacting plans previously discovered (since we'll normally discover the win much
      //  deeper than immediately in  forced-move sequence from the root), when we anyway won't
      //  accept a new plan since we are already replaying one.
      if (current == mTree.mRoot)
      {
        plan.considerPlan(fullPlayoutList);
      }
    }
  }

  public TreeNode expand(TreePath fullPathTo, ForwardDeadReckonLegalMoveInfo[] jointPartialMove, int parentDepth)
  {
    assert(this == mTree.mRoot || fullPathTo == null || mParents.contains(fullPathTo.getTailElement().getParentNode()));

    assert(linkageValid());

    TreeNode result = expandInternal(fullPathTo, jointPartialMove, parentDepth, false, false);

    assert(!mTree.mRemoveNonDecisionNodes || result.mNumChildren > 1 || result == mTree.mRoot || result.mComplete);
    assert(result.linkageValid());
    return result;
  }

  /**
   * Expand the number of children.
   *
   * Normally, the number of children is known at node creation time.  But, in the presence of hyper-edges, we need to
   * expand the child arrays to account for the hyper-children.
   */
  private void expandChildCapacity()
  {
    Object[] newChildren = new Object[mNumChildren * 2];

    System.arraycopy(mChildren, 0, newChildren, 0, mNumChildren);

    mChildren = newChildren;

    if (mPrimaryChoiceMapping != null)
    {
      short[] newPrimaryChoiceMapping = new short[mNumChildren * 2];

      System.arraycopy(mPrimaryChoiceMapping, 0, newPrimaryChoiceMapping, 0, mNumChildren);

      mPrimaryChoiceMapping = newPrimaryChoiceMapping;
    }

    if (mTree.mGameSearcher.mUseRAVE)
    {
      RAVEStats lNewRAVEStats = new RAVEStats(mNumChildren * 2);

      System.arraycopy(mRAVEStats.mCounts, 0, lNewRAVEStats.mCounts, 0, mNumChildren);
      System.arraycopy(mRAVEStats.mScores, 0, lNewRAVEStats.mScores, 0, mNumChildren);

      // Detach the old RAVE stats and use the freshly allocated version.
      detachRAVEStats();
      mRAVEStats = lNewRAVEStats;
    }
  }

  public void hyperExpand(TreePath fullPathTo, ForwardDeadReckonLegalMoveInfo[] jointPartialMove, short hyperDepth)
  {
    assert(ThreadControl.checkTreeOwnership());

    int roleIndex = (mDecidingRoleIndex + 1) % mTree.mNumRoles;

    //  Uncomment this if paranoid, but at some execution cost when asserts are enabled
    //assert(tree.validateForcedMoveProps(state, jointPartialMove));

    //  Perform hyper-expansion and add in hyper-edges
    //  This involves checking which children still have the same player with control
    //  (so only applies to non-simultaneous move games) and recursively expanding
    //  them, and then inserting 'hyper-edges' to the descendants where this player
    //  in NOT in control (i.e. - to all states reachable without any opponent
    //  choice).  Because this is recursive some car is needed with the singleton
    //  buffers in the MCTSTree instance used for interim results (to avoid memory allocation)
    //  We piggyback on the processing done by no-choice node removal, which guarantees
    //  that each child has moved to a different state (except in a special-case of root handling),
    //  and so only enable hyper-edge analysis if no-choice removal is active
    if (mTree.mRemoveNonDecisionNodes && mTree.mRoleControlProps != null && fullPathTo != null && hyperDepth > mHyperExpansionDepth)
    {
      boolean hyperExpansionNeeded = false;

      assert(mState.contains(mTree.mRoleControlProps[roleIndex]));

      //  First create child nodes for all with the same player in control.  This node creation
      //  is anyway implied by the need to recursively expand such children.
      //  TODO - we can optimize this by flagging the children that are same-control in expansion
      //  so that we don't have to create all children here
      if (mHyperExpansionDepth == 0)
      {
        for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
        {
          if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[lMoveIndex] == lMoveIndex)
          {
            //  Create if necessary
            TreeNode child = createChildIfNeccessary(lMoveIndex, jointPartialMove, roleIndex);

            if (child != null && child.mState.contains(mTree.mRoleControlProps[roleIndex]))
            {
              hyperExpansionNeeded = true;
              break;
            }
          }
        }
      }
      else
      {
        Object lastChild = mChildren[mNumChildren-1];

        hyperExpansionNeeded = (lastChild == null || (lastChild instanceof TreeEdge && ((TreeEdge)lastChild).isHyperEdge()));
      }

      //  Now perform the recursive expansion if any is needed
      if (hyperExpansionNeeded)
      {
        for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
        {
          if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[lMoveIndex] == lMoveIndex)
          {
            Object lChoice = mChildren[lMoveIndex];
            if (lChoice instanceof TreeEdge &&  ((TreeEdge)lChoice).isHyperEdge())
            {
              //  We're only interested in recursively expanding the regular tree linkage
              //  so as soon as we hit a hyper-edge we can stop
              break;
            }

            TreeNode child = getChild(lMoveIndex);

            if (child != null && !child.mComplete && child.mState.contains(mTree.mRoleControlProps[roleIndex]))
            {
              assert(lChoice instanceof TreeEdge);
              TreeEdge edge = (TreeEdge)lChoice;

              TreeNode expandedChild;

              assert(validateHyperChain(edge));

              if (child.isUnexpanded())
              {
                //  expand recursively
                fullPathTo.push(this, edge);

                expandedChild = child.expandInternal(fullPathTo, jointPartialMove, mDepth, false, false);

                fullPathTo.pop();

                //  Expanding can result in the discovery that forced moves transpose to the same state as another
                //  edge, in which case expansion can retire this edge - check before continuing
                if (edge != mChildren[lMoveIndex])
                {
                  assert(mPrimaryChoiceMapping != null && mPrimaryChoiceMapping[lMoveIndex] != lMoveIndex);
                  continue;
                }

                //  It can also lead to a state where control has changed hands, so we don't actually have
                //  a valid hyper-path
                if (!expandedChild.mState.contains(mTree.mRoleControlProps[roleIndex]))
                {
                  //  Must reset the joint moved forced props, as the expansion will have
                  //  disturbed them
                  mTree.setForcedMoveProps(mState, jointPartialMove);
                  continue;
                }
              }
              else
              {
                expandedChild = child;
              }
              assert(validateHyperChain(edge));

              expandedChild.hyperExpand(fullPathTo, jointPartialMove, (short)(hyperDepth-1));

              if (!expandedChild.mComplete)
              {
                boolean expandedChildHasHyperEdges = false;
                Object lastChoice = expandedChild.mChildren[expandedChild.mNumChildren-1];

                if ((lastChoice instanceof TreeEdge) && ((TreeEdge)lastChoice).hyperSuccessor != null)
                {
                  expandedChildHasHyperEdges = true;
                }

                //  Mark this edge as unselectable
                edge.setIsSelectable(false);

                //  Add in the implied hyper-edges
                if (expandedChildHasHyperEdges)
                {
                  for (short index = 0; index < expandedChild.mNumChildren; index++)
                  {
                    Object childChoice = expandedChild.mChildren[index];
                    if (childChoice instanceof TreeEdge && !((TreeEdge)childChoice).isSelectable())
                    {
                      continue;
                    }

                    if (childChoice != null && (expandedChild.mPrimaryChoiceMapping == null || expandedChild.mPrimaryChoiceMapping[index] == index))
                    {
                      //  Children will have already been expanded by the recursive call above usually, but in
                      //  the case of a transposition it is possible they will not have been
                      TreeNode descendant = expandedChild.createChildIfNeccessary(index, jointPartialMove, roleIndex);

                      //  In the case of a hyper-edge whose terminus no longer exists (because an intermediary step has been completed usually)
                      //  we may have no descendant, which means it's a dead hyper-edge and we should ignore it
                      if (descendant == null)
                      {
                        continue;
                      }

                      //  In principal hyper-edges should provide direct access to all possible successor states which the
                      //  currently choosing player can force arrival at (those where the opponent gets the next choice)
                      //  In practice this leads to too much of a combinatoric explosion in the branching factor, so instead
                      //  we link to the terminal nodes of the sequences wherein the choosing player retains control.  This provides
                      //  most of the selection power of the theoretical approach with much less of an increase in branching factor
                      if (!descendant.mState.contains(mTree.mRoleControlProps[roleIndex]))
                      {
                        assert(!((TreeEdge)expandedChild.mChildren[index]).isHyperEdge());
                        continue;
                      }
                      assert(((TreeEdge)expandedChild.mChildren[index]).isHyperEdge());

                      //  Only need to add if we don't already have a (hyper or otherwise) child
                      //  which is this node
                      boolean alreadyPresent = false;

                      for (short ourIndex = 0; ourIndex < mNumChildren; ourIndex++)
                      {
                        TreeNode ourChild = getChild(ourIndex);
                        if (ourChild != null && ourChild.mState.equals(descendant.mState))
                        {
                          alreadyPresent = true;
                          break;
                        }
                      }

                      if (!alreadyPresent)
                      {
                        //  Add the new hyper-edge
                        TreeEdge descendantEdge = (TreeEdge)expandedChild.mChildren[index];

                        assert(descendantEdge.mParentRef == expandedChild.getRef());

                        //  Do we need to expand the children array?
                        if (mChildren.length == mNumChildren)
                        {
                          expandChildCapacity();
                        }

                        TreeEdge hyperEdge = mTree.mEdgePool.allocate(mTree.mTreeEdgeAllocator);
                        hyperEdge.setParent(this, edge.mPartialMove);
                        hyperEdge.hyperSuccessor = descendantEdge;
                        hyperEdge.setIsHyperEdge(true);
                        //  Because edges are pooled and can be reallocated after being freed by an un-expansion
                        //  it is possible that the hyperSuccessor chain can contain stale edges that have been
                        //  reused.  So that we can validate chain integrity we therefore store the ref of the next
                        //  node in the chain, so that at each link the expected next node ref can be validated against
                        //  the succesor's parent ref
                        hyperEdge.nextHyperChild = expandedChild.getRef();
                        hyperEdge.setChildRef(descendantEdge.getChildRef());

                        if (mPrimaryChoiceMapping != null)
                        {
                          mPrimaryChoiceMapping[mNumChildren] = mNumChildren;
                        }
                        mChildren[mNumChildren++] = hyperEdge;

                        assert(validateHyperChain(hyperEdge));
                      }
                    }
                  }
                }
                else
                {
                  //  Do we need to expand the children array?
                  if (mChildren.length == mNumChildren)
                  {
                    expandChildCapacity();
                  }

                  //  Hyper link directly to the expanded child
                  TreeEdge hyperEdge = mTree.mEdgePool.allocate(mTree.mTreeEdgeAllocator);
                  hyperEdge.setParent(this, edge.mPartialMove);
                  hyperEdge.hyperSuccessor = null;
                  hyperEdge.setIsHyperEdge(true);
                  //  Because edges are pooled and can be reallocated aftr being freed by an un-expansion
                  //  it is possible that the hyperSuccessor chain can contain stale edges that have been
                  //  reused.  So that we can validate chain integrity we therefore store the ref of the next
                  //  node in the chain, so that at each link the expected next node ref can be validated against
                  //  the succesor's parent ref
                  hyperEdge.nextHyperChild = expandedChild.getRef();
                  hyperEdge.setChildRef(edge.getChildRef());
                  //  Can safely take on the visit count of the replaced normal edge
                  hyperEdge.setNumVisits(edge.getNumChildVisits());

                  if (mPrimaryChoiceMapping != null)
                  {
                    mPrimaryChoiceMapping[mNumChildren] = mNumChildren;
                  }
                  mChildren[mNumChildren++] = hyperEdge;

                  assert(validateHyperChain(hyperEdge));
                }

                assert(linkageValid());
              }
            }
          }
        }

        if (mNumChildren > mTree.maxChildrenSeen)
        {
          mTree.maxChildrenSeen = mNumChildren;

          LOGGER.info("Max children for one node seen: " + mTree.maxChildrenSeen);
        }
      }

      mHyperExpansionDepth = hyperDepth;
    }
  }

  private TreeNode expandInternal(TreePath fullPathTo, ForwardDeadReckonLegalMoveInfo[] jointPartialMove, int parentDepth, boolean isRecursiveExpansion, boolean stateChangedInForcedExpansion)
  {
    assert(ThreadControl.checkTreeOwnership());

    TreePathElement pathTo = (fullPathTo == null ? null : fullPathTo.getTailElement());

    assert(this == mTree.mRoot || mParents.size() > 0);
    assert((mDepth / mTree.mNumRoles == mTree.mRoot.mDepth / mTree.mNumRoles) ||
           (!mTree.mRemoveNonDecisionNodes && mDecidingRoleIndex != mTree.mNumRoles-1) ||
           (pathTo != null && pathTo.getEdgeUnsafe().mPartialMove.mIsPseudoNoOp) ||
           (mTree.findTransposition(mState) == this));
    //assert(state.size()==10);
    //boolean assertTerminal = !state.toString().contains("b");
    //  Find the role this node is choosing for
    int roleIndex = (mDecidingRoleIndex + 1) % mTree.mNumRoles;

    //  Don't bother evaluating terminality of children above the earliest completion depth
    boolean evaluateTerminalOnNodeCreation = (mTree.mEvaluateTerminalOnNodeCreation && (mDepth >= mTree.mShallowestCompletionDepth-mTree.mNumRoles || mTree.mHeuristic.isEnabled() || mTree.mGameSearcher.mUseGoalGreedy));

    //  Don't evaluate terminality on the root since it cannot be (and latched score states
    //  might indicate it should be treated as such, but this is never correct for the root)
    if (this != mTree.mRoot)
    {
      if (roleIndex == 0)
      {
        //  We cannot be sure that the parent evaluated terminality unless it is deeper than the observed
        //  minmimal game depth from meta-gaming.  The current best (dynamic) estimate may be lowert than
        //  this and **may** mean that the parent expansion DID calculate terminality, but in such cases
        //  we repeat the test here (it's cheap since we are running the state anyway to obtain legal moves)
        boolean parentEvaluatedTerminalOnNodeCreation = (mTree.mEvaluateTerminalOnNodeCreation &&
                                                         !isRecursiveExpansion &&
                                                         parentDepth >= mTree.mGameCharacteristics.getEarliestCompletionDepth() - mTree.mNumRoles);
        if (!parentEvaluatedTerminalOnNodeCreation && mNumChildren == 0)
        {
          StateInfo info = calculateTerminalityAndAutoExpansion(mState);

          mTerminal = info.isTerminal;

          if (mTerminal)
          {
            if (mTree.mGameCharacteristics.isPseudoPuzzle)
            {
              mTree.mUnderlyingStateMachine.getLatchedScoreRange(mTree.mRoot.mState,
                                                                 mTree.mRoleOrdering.roleIndexToRole(0),
                                                                 mTree.mLatchedScoreRangeBuffer);

              if (info.terminalScore[0] == mTree.mLatchedScoreRangeBuffer[1])
              {
                considerPathToAsPlan();
              }
            }
            markComplete(info.terminalScore, mDepth);
            return this;
          }
        }
      }
    }

    assert(!mTree.mSearchFilter.isFilteredTerminal(mState, mTree.mUnderlyingStateMachine));
    assert(linkageValid());

    assert (mNumChildren == 0);
    {
      Role choosingRole = mTree.mRoleOrdering.roleIndexToRole(roleIndex);
      int topMoveWeight = 0;

      if (!isRecursiveExpansion && pathTo != null && pathTo.getEdgeUnsafe().getHasBeenTrimmed())
      {
        //  If the node is unexpanded, yet has already been visited, this must
        //  be a re-expansion following trimming.
        //  Note - the first can visit occur without expansion as a random child
        //  of the last expanded node will be chosen to rollout from
        mTree.mNumReExpansions++;
      }
      //validateAll();

      //LOGGER.debug("Expand our moves from state: " + state);
      ForwardDeadReckonLegalMoveSet moves = mTree.mUnderlyingStateMachine.getLegalMoveSet(mState);
      mNumChildren = (short)mTree.mSearchFilter.getFilteredMovesSize(mState, moves, choosingRole, true);
      assert(mNumChildren > 0) : "Filtered move list for node was empty";
      Iterator<ForwardDeadReckonLegalMoveInfo> itr;

      if (mNumChildren == 1 && this != mTree.mRoot && mTree.mRemoveNonDecisionNodes)
      {
        assert(pathTo != null);

        TreeNode parent = pathTo.getParentNode();
        TreeEdge edge = pathTo.getEdgeUnsafe();

        assert(parent != null);
        assert(parent.linkageValid());
        assert(edge.getChildRef() == getRef());

        itr = moves.getContents(choosingRole).iterator();

        //  Forced responses do not get their own nodes - we just re-purpose this one
        ForwardDeadReckonLegalMoveInfo forcedChoice = mTree.mSearchFilter.nextFilteredMove(itr);
        ForwardDeadReckonInternalMachineState newState = mTree.mChildStatesBuffer[0];
        TreeNode result = this;

        jointPartialMove[roleIndex] = forcedChoice;

        if (roleIndex == mTree.mNumRoles - 1)
        {
           stateChangedInForcedExpansion = true;

          mTree.setForcedMoveProps(mState, jointPartialMove);
          newState = mTree.mChildStatesBuffer[0];
          mTree.mUnderlyingStateMachine.getNextState(mState,
                                                   mTree.mFactor,
                                                   jointPartialMove,
                                                   newState);

          assert(!newState.equals(parent.mState));
          //  In a factorized game we need to normalize the generated state
          //  so as to not fall foul of potential corruption of the non-factor
          //  element engendered by not making a move in other factors
          if (mTree.mFactor != null)
          {
            mTree.makeFactorState(newState);
          }

          //  Have we transposed?
          TreeNode existing = mTree.findTransposition(newState);
          if (existing != null)
          {
            assert(existing != this);
            assert(existing != parent);
            assert(existing.mState.equals(newState));
            assert(edge.getChildRef() != existing.getRef());
            assert(existing.linkageValid());

            //  Detach the edge from the old node we just transitioned out of
            edge.setChildRef(NULL_REF);

            //  Need to check that we don't already have a different edge leading from the same parent to this newly transposed-to
            //  node (multiple forced move paths can have a common destination)
            if (existing.mParents.contains(parent))
            {
              short thisIndex = -1;
              short otherPathIndex = -1;

              for (short lii = 0; lii < parent.mNumChildren; lii++)
              {
                if (parent.mChildren[lii] instanceof TreeEdge)
                {
                  TreeEdge linkingEdge = (TreeEdge)parent.mChildren[lii];

                  if (linkingEdge == edge)
                  {
                    thisIndex = lii;
                  }
                  else if (linkingEdge.getChildRef() == existing.getRef())
                  {
                    otherPathIndex = lii;
                  }
                }
              }

              assert(thisIndex != -1);
              assert(otherPathIndex != -1);

              //  This edge is being newly traversed (for the first time), but the
              //  other may already have been traversed multiple times, so we must retire
              //  this one in favour of the other
              parent.mChildren[thisIndex] = edge.mPartialMove;
              if (parent.mPrimaryChoiceMapping == null)
              {
                parent.mPrimaryChoiceMapping = new short[parent.mChildren.length];

                for (short lii = 0; lii < parent.mNumChildren; lii++)
                {
                  parent.mPrimaryChoiceMapping[lii] = lii;
                }
              }

              parent.mPrimaryChoiceMapping[thisIndex] = otherPathIndex;

              edge.reset();
              mTree.mEdgePool.free(edge, 0);
              edge = (TreeEdge)parent.mChildren[otherPathIndex];

              //  The old edge will have been selected through (else we wouldn't be expanding it)
              //  which will have incremented its visit count - need to do that on the replacement
              //  path to keep things correctly counted
              edge.incrementNumVisits();

              //  In any other path it's only valid to create a path element where the number
              //  of edge visits is no greater than the number of child node visits, but in the
              //  selectAction path where this recursion can replace a node due to transpositions
              //  we'll be in an intermediary state where the processing selection has incremented
              //  the edge count, but not yet the child node count (which will be incremented on the
              //  next step of the selection process).  This means the constraint can be temporarily
              //  'out by one', and rather than lose the power of these assertions we can make for the
              //  'normal' case we transiently increment the child node count here if and only if
              //  assertions are enabled.
              assert(existing.mNumVisits++ >= 0);
              pathTo.set(parent, edge);
              assert(existing.mNumVisits-- > 0);
            }

            //  This situation can only occur on the first expansion of the child of the edge
            //  which happens the first or second time it is visited (first time it may result in
            //  a playout from a leaf node that doesn't get expanded).  If it turn out to be a
            //  transposition, not detected until the second selection, then the first selection
            //  will not register on the visit counting of the new child, and the edge count needs
            //  reducing to one for things to remain consistent
            //edge.setNumVisits(1);

            //  In any other path it's only valid to create a path element where the number
            //  of edge visits is no greater than the number of child node visits, but in the
            //  selectAction path where this recursion can replace a node due to transpositions
            //  we'll be in an intermediary state where the processing selection has incremented
            //  the edge count, but not yet the child node count (which will be incremented on the
            //  next step of the selection process).  This means the constraint can be temporarily
            //  'out by one', and rather than lose the power of these assertions we can make for the
            //  'normal' case we transiently increment the child node count here if and only if
            //  assertions are enabled.

            //  Also there is the case where the edge has a heuristic weight bu the transposed to node did not
            //  which can lead to a lower visit count on the node.  To keep things in order we just increase
            //  it as necessary
            if (existing.mNumVisits < edge.getNumChildVisits())
            {
              existing.mNumVisits = edge.getNumChildVisits() - 1;
            }

            assert(existing.mNumVisits++ >= 0);
            edge.setChild(existing);
            pathTo.set(parent, edge);
            assert(existing.mNumVisits-- > 0);

            //  Strictly this new path from parent to child by a forced-move path might
            //  not be unique (it could turn out that multiple forced move sequences which
            //  have different starting moves lead to the same result)
            //  If it's NOT unique we must make it so
            if (!existing.mParents.contains(parent))
            {
              existing.addParent(parent);
            }

            assert(existing.linkageValid());
            assert(parent.linkageValid());

            //  If the node transposed to was complete it must have been a non-decisive completion
            //  but it could be that it completes all children of the new parent, so that must be
            //  checked
            if (existing.mComplete)
            {
              parent.checkChildCompletion(false);
            }

            //  The original node is no longer needed for this path since we wound up transposing
            //  However, it may have several parents, and we can only trim it from the current
            //  selection path (selection through the other paths will later transpose to the same result)
            //  In rare cases this node may ALREADY have been freed by a decisive completion during
            //  a recursive expansion of a node transposed into during the recursion (that shared a common
            //  parent)
            if (!mFreed)
            {
              assert(this != mTree.mRoot);
              assert(mNumChildren == 1) : "Expansion of non-decision node occuring on apparent decision node!";
              mNumChildren = 0; //  Must reset this so it appears unexpanded for other paths if it doesn't get freed
              assert(mParents.size() > 0);
              if (mParents.contains(parent))
              {
                freeFromAncestor(parent, null);
              }

              assert(mFreed || linkageValid());
              assert(parent.linkageValid());
            }

            //  If the transposed-to node is already expanded we're done
            if (!existing.mComplete && existing.isUnexpanded())
            {
              result = existing.expandInternal(fullPathTo, jointPartialMove, parentDepth, true, stateChangedInForcedExpansion);
              assert(result.mNumChildren > 1 || result == mTree.mRoot || result.mComplete);
              assert(result.linkageValid());
            }
            else
            {
              //  Need to check if this is a heuristic sequence exit in this path, but wasn't in the path
              //  the node was originally created via
              if (!existing.mComplete && stateChangedInForcedExpansion &&
                   (existing.mHeuristicWeight == 0 || Math.abs(existing.mHeuristicValue-50) < EPSILON))
              {
                TreeEdge incomingEdge = pathTo.getEdgeUnsafe();

                if (!incomingEdge.hasHeuristicDeviation())
                {
                  mTree.mHeuristic.getHeuristicValue(existing.mState,
                                                    parent.mState,
                                                    parent.mState,
                                                    mTree.mNodeHeuristicInfo);

                  if (mTree.mNodeHeuristicInfo.treatAsSequenceStep)
                  {
                    incomingEdge.setHasHeuristicDeviation(true);
                    existing.mHeuristicWeight = mTree.mNodeHeuristicInfo.heuristicWeight;
                    existing.mHeuristicValue = mTree.mNodeHeuristicInfo.heuristicValue[0];
                  }
                }
              }

              result = existing;
              assert(result.mNumChildren > 1 || result == mTree.mRoot || result.mComplete);
            }
            assert(result != this);
            assert(result != parent);
            assert(parent.mComplete || pathTo.getEdgeUnsafe().getChildRef() == result.getRef());
          }
          else
          {
            //  Update the node transposition indexes
            //  Note - ideally we would NOT remove any indexing of the previous state
            //  since if it pointed to this node, then it is pointing at an interim
            //  state in a forced move sequence, which anyway should evaluate to
            //  the terminus of that sequence, which will be this node at the end of
            //  the recursion.  HOWEVER, the state is an object reference to a fixed collection
            //  associated with this node, and we're about to change its value, which will
            //  change its hash key, so we must either create a new internal state instance
            //  of remove the old index entry.  Due to the difficulty of cleaning up index
            //  entries that do not correspond to an extant node state we DO remove the old
            //  entry and accept that other forced moves paths transitioning through it will have to
            //  redo the sequence recursion.
            assert(mTree.findTransposition(newState) == null);
            mTree.removeFromTranspositionIndexes(this);
            mState.copy(newState);
            mTree.addToTranspositionIndexes(this);
          }
        }

        if (result == this)
        {
          assert(this != mTree.mRoot);
          assert(mNumChildren == 1) : "Expansion of non-decision node occuring on apparent decision node!";
          mNumChildren = 0;
          mDepth++;
          mDecidingRoleIndex = (mDecidingRoleIndex + 1) % mTree.mNumRoles;
          assert(mDepth % mTree.mNumRoles == (mDecidingRoleIndex + 1) % mTree.mNumRoles);

          //  Recurse
          result = expandInternal(fullPathTo, jointPartialMove, parentDepth, true, stateChangedInForcedExpansion);
          assert(result.linkageValid());
        }

        assert(result.mNumChildren > 1 || result == mTree.mRoot || result.mComplete);
        assert(parent.linkageValid());
        return result;
      }

      // If the child array isn't large enough, expand it.
      assert(mNumChildren <= MCTSTree.MAX_SUPPORTED_BRANCHING_FACTOR);
      if (mNumChildren > mChildren.length)
      {
        int lMaxDirectChildren = mTree.mGameCharacteristics.getChoicesHighWaterMark(mNumChildren);
        mChildren = new Object[lMaxDirectChildren];
        assert(mRAVEStats == null);
        assert(mPrimaryChoiceMapping == null);
      }

      if (MCTSTree.USE_STATE_SIMILARITY_IN_EXPANSION)
      {
        if (mNumChildren > 1)
        {
          topMoveWeight = mTree.mStateSimilarityMap.getTopMoves(mState, jointPartialMove, mTree.mNodeTopMoveCandidates);
        }
      }

      if (mTree.mFactor != null && mTree.mHeuristic.isEnabled() && (mTree.mRemoveNonDecisionNodes || roleIndex == mTree.mNumRoles - 1))
      {
        //  If we're expanding the pseudo-noop in a factored game we need to correct for the case where
        //  it's ending a heuristic sequence
        if (pathTo != null)
        {
          TreeEdge edge = pathTo.getEdgeUnsafe();

          if (edge.mPartialMove.mIsPseudoNoOp)
          {
            assert(pathTo.getParentNode() == mTree.mRoot);

            //  Look to see if a sibling move has no heuristic variance - we can clone
            //  it's heuristic score and weight
            for (short index = 0; index < mTree.mRoot.mNumChildren; index++)
            {
              Object lChoice = mTree.mRoot.mChildren[index];

              if (lChoice != edge && lChoice instanceof TreeEdge)
              {
                TreeEdge siblingEdge = (TreeEdge)lChoice;

                if (!siblingEdge.hasHeuristicDeviation())
                {
                  TreeNode siblingNode = get(siblingEdge.getChildRef());

                  if (siblingNode != null)
                  {
                    mHeuristicValue = siblingNode.mHeuristicValue;
                    mHeuristicWeight = siblingNode.mHeuristicWeight;
                    break;
                  }
                }
              }
            }
          }
        }
      }

      //  If this is the first choice node discovered as we descend from the root not it and how many children it has
      mTree.setForcedMoveProps(mState, jointPartialMove);

      //  Must retrieve the iterator AFTER setting any forced move props, since it will also
      //  iterate over moves internally, and the legal move set iterator is a singleton
      itr = moves.getContents(choosingRole).iterator();

      boolean foundVirtualNoOp = false;
      for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
      {
        ForwardDeadReckonLegalMoveInfo newChoice = mTree.mSearchFilter.nextFilteredMove(itr);

        boolean isPseudoNullMove = (mTree.mFactor != null && mNumChildren > 1);

        jointPartialMove[roleIndex] = newChoice;

        if (isPseudoNullMove)
        {
          for (int lii = 0; lii <= roleIndex; lii++)
          {
            if (jointPartialMove[lii].mInputProposition != null)
            {
              isPseudoNullMove = false;
            }
          }
        }

        ForwardDeadReckonInternalMachineState newState = mTree.mChildStatesBuffer[lMoveIndex];
        if ((roleIndex == mTree.mNumRoles - 1 || (mNumChildren != 1 && mTree.mRemoveNonDecisionNodes)) && (!foundVirtualNoOp || !newChoice.mIsVirtualNoOp))
        {
          newState = mTree.mChildStatesBuffer[lMoveIndex];
          mTree.mUnderlyingStateMachine.getNextState(mState,
                                                   mTree.mFactor,
                                                   jointPartialMove,
                                                   newState);

          //  In a factorized game we need to normalize the generated state
          //  so as to not fall foul of potential corruption of the non-factor
          //  element engendered by not making a move in other factors
          if (mTree.mFactor != null)
          {
            mTree.makeFactorState(newState);
          }
        }
        else
        {
          newState.copy(mState);
        }

        if (mPrimaryChoiceMapping != null)
        {
          mPrimaryChoiceMapping[lMoveIndex] = lMoveIndex;
        }

        //	Check for multiple moves that all transition to the same state
        //  Note - we use an approximation of the true game for search purposes
        //  wherein all virtual noops are considered to be the same and thus
        //  transition to the same state.  This is not strictly accurate, but is
        //  likely to be a very good approximation for any reasonable game.  The
        //  approximation would break down in games where:
        //  1) There is a pool of virtual no-ops (aka moves that impact only parts of the
        //     base state space that are disconnected for our goals)
        //  2) An elective no-op can be beneficial
        //  3) The size of the pool of noops significantly depends on the particular
        //     choice made between them on ancestor nodes
        if (!isPseudoNullMove)
        {
          for (short lii = 0; lii < lMoveIndex; lii++)
          {
            if (mChildren[lii] != null &&
                ((foundVirtualNoOp && newChoice.mIsVirtualNoOp) ||
                 ((mTree.mRemoveNonDecisionNodes || roleIndex == mTree.mNumRoles - 1) &&
                  mTree.mChildStatesBuffer[lii].equals(newState))))
            {
              if (mPrimaryChoiceMapping == null)
              {
                mPrimaryChoiceMapping = new short[mChildren.length];
                for (short ljj = 0; ljj < lMoveIndex; ljj++)
                {
                  mPrimaryChoiceMapping[ljj] = ljj;
                }
              }
              mPrimaryChoiceMapping[lMoveIndex] = lii;
              break;
            }
          }
        }

        assert(newChoice != null);
        mChildren[lMoveIndex] = newChoice;

        foundVirtualNoOp |= newChoice.mIsVirtualNoOp;
      }

      assert(linkageValid());

      if (evaluateTerminalOnNodeCreation && ((mNumChildren != 1 && mTree.mRemoveNonDecisionNodes) || roleIndex == mTree.mNumRoles - 1))
      {
        int bestChildGoalValue = 0;

        for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
        {
          if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[lMoveIndex] == lMoveIndex)
          {
            StateInfo info = calculateTerminalityAndAutoExpansion(mTree.mChildStatesBuffer[lMoveIndex]);
            boolean stateFastForwarded = false;
            int fastForwardDepthIncrement = 0;

            if (mNumChildren != 1 && mTree.mRemoveNonDecisionNodes)
            {
              //  Piggy-back on the fact that we have to run the state anyway to fast-forward
              //  forced move expansions to their terminus (a choice node or a terminal node)
              //  In particular note that we always evaluate terminal on creation for games with heuristics
              //  so this fast-forwarding will always occur in such games
              while (!info.isTerminal && mTree.setForcedMoveProps(mTree.mChildStatesBuffer[lMoveIndex], mTree.mFastForwardPartialMoveBuffer))
              {
                fastForwardDepthIncrement += mTree.mNumRoles;
                stateFastForwarded = true;
                mTree.mUnderlyingStateMachine.getNextState(mTree.mChildStatesBuffer[lMoveIndex],
                                                         mTree.mFactor,
                                                         mTree.mFastForwardPartialMoveBuffer,
                                                         mTree.mStateScratchBuffer);

                //  In a factorized game we need to normalize the generated state
                //  so as to not fall foul of potential corruption of the non-factor
                //  element engendered by not making a move in other factors
                if (mTree.mFactor != null)
                {
                  mTree.makeFactorState(mTree.mStateScratchBuffer);
                }

                mTree.mChildStatesBuffer[lMoveIndex].copy(mTree.mStateScratchBuffer);
                info = calculateTerminalityAndAutoExpansion(mTree.mChildStatesBuffer[lMoveIndex]);
              }

              if (stateFastForwarded)
              {
                short remappedTo = -1;

                //  Since we have changed the state fixup the primary choice mappings
                for (short lii = 0; lii < lMoveIndex; lii++)
                {
                  if (mChildren[lii] != null && mTree.mChildStatesBuffer[lii].equals(mTree.mChildStatesBuffer[lMoveIndex]))
                  {
                    if (mPrimaryChoiceMapping == null)
                    {
                      mPrimaryChoiceMapping = new short[mChildren.length];
                      for (short ljj = 0; ljj < mNumChildren; ljj++)
                      {
                        mPrimaryChoiceMapping[ljj] = ljj;
                      }
                    }
                    mPrimaryChoiceMapping[lMoveIndex] = lii;
                    remappedTo = lii;
                    break;
                  }
                }
                if (remappedTo != -1)
                {
                  for (short ljj = (short)(lMoveIndex + 1); ljj < mNumChildren; ljj++)
                  {
                    if (mPrimaryChoiceMapping[ljj] == lMoveIndex)
                    {
                      mPrimaryChoiceMapping[ljj] = remappedTo;
                    }
                  }
                }
              }
            }

            if (mTree.mGameSearcher.mUseGoalGreedy)
            {
              int goalValue = mTree.mUnderlyingStateMachine.getGoal(mTree.mChildStatesBuffer[lMoveIndex], mTree.mOurRole);

              if (goalValue > bestChildGoalValue)
              {
                bestChildGoalValue = goalValue;
              }
            }

            //  We need to create the node at once if a fast-forward has taken place since
            //  the state will not be that reached directly by the move choice and we will
            //  not have access to the correct state information in other contexts
            ForwardDeadReckonLegalMoveInfo childMove = (ForwardDeadReckonLegalMoveInfo)mChildren[lMoveIndex];
            if ((mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[lMoveIndex] == lMoveIndex) &&
                 (info.isTerminal || stateFastForwarded) && !childMove.mIsPseudoNoOp)
            {
              TreeEdge newEdge = mTree.mEdgePool.allocate(mTree.mTreeEdgeAllocator);
              newEdge.setParent(this, childMove);
              mChildren[lMoveIndex] = newEdge;
              jointPartialMove[roleIndex] = newEdge.mPartialMove;
              createChildNodeForEdgeWithAssertedState(newEdge, mTree.mChildStatesBuffer[lMoveIndex], fastForwardDepthIncrement, false);

              TreeNode newChild = get(newEdge.getChildRef());
              newChild.mTerminal = info.isTerminal;
              if (info.isTerminal)
              {
                if (mTree.mGameCharacteristics.isPseudoPuzzle)
                {
                  mTree.mUnderlyingStateMachine.getLatchedScoreRange(mTree.mRoot.mState,
                                                                     mTree.mRoleOrdering.roleIndexToRole(0),
                                                                     mTree.mLatchedScoreRangeBuffer);

                  if (info.terminalScore[0] == mTree.mLatchedScoreRangeBuffer[1])
                  {
                    newChild.considerPathToAsPlan();
                  }
                }
                newChild.markComplete(info.terminalScore, newChild.mDepth);
              }

              assert(newChild.linkageValid());
            }
          }
        }

        if (mTree.mGameSearcher.mUseGoalGreedy)
        {
          if (bestChildGoalValue <= mHeuristicValue && !mParents.isEmpty())
          {
            mHeuristicValue = mParents.get(0).mHeuristicValue;
          }
        }
      }

      assert(linkageValid());

      if (MCTSTree.USE_STATE_SIMILARITY_IN_EXPANSION && topMoveWeight > 0)
      {
        for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
        {
          if ((mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[lMoveIndex] == lMoveIndex))
          {
            Object lChoice = mChildren[lMoveIndex];
            TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
            if (edge == null || !get(edge.getChildRef()).mTerminal)
            {
              //  Skip this for pseudo-noops
              if ((edge != null ? edge.mPartialMove : (ForwardDeadReckonLegalMoveInfo)lChoice).mIsPseudoNoOp)
              {
                continue;
              }

              for (int lii = 0; lii < mTree.mNodeTopMoveCandidates.length; lii++)
              {
                ForwardDeadReckonLegalMoveInfo moveCandidate = mTree.mNodeTopMoveCandidates[lii];
                if (lChoice == moveCandidate || (edge != null && edge.mPartialMove == moveCandidate))
                {
                  if (edge == null)
                  {
                    edge = mTree.mEdgePool.allocate(mTree.mTreeEdgeAllocator);
                    edge.setParent(this, moveCandidate);
                    mChildren[lMoveIndex] = edge;
                  }
                  edge.explorationAmplifier = (topMoveWeight * (mTree.mNodeTopMoveCandidates.length + 1 - lii)*2) /
                                              (mTree.mNodeTopMoveCandidates.length + 1);
                  break;
                }
              }
            }
          }
        }
      }

      assert(linkageValid());

      //calculatePathMoveWeights(fullPathTo);

      if (mTree.mHeuristic.isEnabled() && ((mNumChildren != 1 && mTree.mRemoveNonDecisionNodes) || roleIndex == mTree.mNumRoles - 1))
      {
        if (mTree.mHeuristic.applyAsSimpleHeuristic())
        {
          for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
          {
            if ((mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[lMoveIndex] == lMoveIndex))
            {
              Object lChoice = mChildren[lMoveIndex];

              //  Skip this for pseudo-noops since we don't want to expand them except when they are immediate
              //  children of the root (and in that case their heuristic value is the same as the root's)
              if (((lChoice instanceof TreeEdge) ? ((TreeEdge)lChoice).mPartialMove : (ForwardDeadReckonLegalMoveInfo)lChoice).mIsPseudoNoOp)
              {
                continue;
              }

              // Determine the heuristic value for this child.
              mTree.mHeuristic.getHeuristicValue(mTree.mChildStatesBuffer[lMoveIndex],
                                                mState,
                                                mState,
                                                mTree.mNodeHeuristicInfo);

              if (mTree.mNodeHeuristicInfo.heuristicWeight > 0)
              {
                double heuristicSquaredDeviation = 0;

                //validateScoreVector(heuristicScores);

                // Set the heuristic values, although note that this doesn't actually apply them.  There need to be some
                // recorded samples before the averageScores have any meaning.
                for (int i = 0; i < mTree.mNumRoles; i++)
                {
                  //newChild.averageScores[i] = heuristicScores[i];
                  double lDeviation = mTree.mRoot.getAverageScore(i) - mTree.mNodeHeuristicInfo.heuristicValue[i];
                  heuristicSquaredDeviation += (lDeviation * lDeviation);
                }

                // Only apply the heuristic values if the current root has sufficient visits and there is some deviation
                // between the root's scores and the heuristic scores in the new child.
                if (heuristicSquaredDeviation > 0.01 && mTree.mRoot.mNumVisits > 50)
                {
                  //  Create the edge if necessary
                  TreeEdge edge;

                  if (mChildren[lMoveIndex] instanceof TreeEdge)
                  {
                    edge = (TreeEdge)mChildren[lMoveIndex];
                  }
                  else
                  {
                    edge = mTree.mEdgePool.allocate(mTree.mTreeEdgeAllocator);
                    edge.setParent(this, (ForwardDeadReckonLegalMoveInfo)mChildren[lMoveIndex]);
                    mChildren[lMoveIndex] = edge;
                  }

                  assert(edge != null);

                  jointPartialMove[roleIndex] = edge.mPartialMove;

                  assert(linkageValid());

                  if (edge.getChildRef() == NULL_REF)
                  {
                    createChildNodeForEdge(edge, jointPartialMove);

                    assert(linkageValid());

                    assert(!evaluateTerminalOnNodeCreation || !calculateTerminalityAndAutoExpansion(get(edge.getChildRef()).mState).isTerminal);

                    assert(linkageValid());
                  }

                  TreeNode newChild = get(edge.getChildRef());

                  if (!newChild.mTerminal && (newChild.mNumVisits == 0 || newChild.mHeuristicWeight == 0 || Math.abs(newChild.mHeuristicValue-50) < EPSILON))
                  {
                    newChild.mHeuristicValue = mTree.mNodeHeuristicInfo.heuristicValue[0];
                    newChild.mHeuristicWeight = mTree.mNodeHeuristicInfo.heuristicWeight;

                    //  If this turns out to be a transition into an already visited child
                    //  then do not apply the heuristic seeding to the average scores
                    if (newChild.mNumVisits == 0)
                    {
                      for (int lii = 0; lii < mTree.mNumRoles; lii++)
                      {
                        double adjustedRoleScore = mTree.mNodeHeuristicInfo.heuristicValue[lii];

                        double newChildRoleScore = (newChild.getAverageScore(lii) * newChild.mNumUpdates +
                                                    adjustedRoleScore * mTree.mNodeHeuristicInfo.heuristicWeight) /
                                                   (newChild.mNumUpdates + mTree.mNodeHeuristicInfo.heuristicWeight);
                        newChild.setAverageScore(lii, newChildRoleScore);
                      }

                      // Use the heuristic confidence to guide how many virtual rollouts to pretend there have been through
                      // the new child.
                      //newChild.mNumUpdates += mTree.mNodeHeuristicInfo.heuristicWeight;
                      assert(!Double.isNaN(newChild.getAverageScore(0)));

                      //newChild.mNumVisits += mTree.mNodeHeuristicInfo.heuristicWeight;
                      //edge.setNumVisits(newChild.mNumVisits);
                    }
                  }
                }
              }
            }
          }
        }
        else
        {
          // Determine the appropriate reference node to evaluate children with respect to
          // Evaluate wrt the first ancestor state with a reasonable number of visits which
          // is not itself immediately preceded by a heuristic exchange
          boolean previousEdgeHadHeuristicDeviation = false;
          TreeNode referenceNode = mTree.mRoot;

          //  If the state represented by this node has changed due to forced move collapsing
          //  there may have been a heuristic change, which needs to be noted on the incoming edge
          if (pathTo != null)
          {
            TreeNode parent = pathTo.getParentNode();

            if (stateChangedInForcedExpansion || parent.mDepth / mTree.mNumRoles < mDepth / mTree.mNumRoles - 1)
            {
              TreeEdge incomingEdge = pathTo.getEdgeUnsafe();

              if (!incomingEdge.hasHeuristicDeviation())
              {
                mTree.mHeuristic.getHeuristicValue(mState,
                                                  parent.mState,
                                                  parent.mState,
                                                  mTree.mNodeHeuristicInfo);

                if (mTree.mNodeHeuristicInfo.treatAsSequenceStep)
                {
                  incomingEdge.setHasHeuristicDeviation(true);
                }
              }
            }
          }

          if (fullPathTo != null)
          {
            boolean previousEdgeWalked = false;
            TreePathElement pathElement = null;
            boolean traversedHeuristicSequence = false;
            boolean inHeuristicSequence = false;

            while(fullPathTo.hasMore())
            {
              fullPathTo.getNextNode();
              pathElement = fullPathTo.getCurrentElement();

              assert(pathElement != null);

              boolean pathElementHasHeuristicDeviation = pathElement.getEdgeUnsafe().hasHeuristicDeviation();

              if (!previousEdgeWalked)
              {
                previousEdgeHadHeuristicDeviation = pathElementHasHeuristicDeviation;
                previousEdgeWalked = true;
              }

              if (pathElementHasHeuristicDeviation && !inHeuristicSequence && traversedHeuristicSequence)
              {
                break;
              }

              if (pathElement.getParentNode().mNumUpdates > 200 && !pathElementHasHeuristicDeviation)
              {
                break;
              }

              inHeuristicSequence = pathElementHasHeuristicDeviation;
              traversedHeuristicSequence |= inHeuristicSequence;
              referenceNode = pathElement.getParentNode();
            }

            //  Restore path cursor so that next attempt to enumerate is clean
            fullPathTo.resetCursor();
          }

          short firstIndexWithHeuristic = -1;

          for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
          {
            if ((mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[lMoveIndex] == lMoveIndex))
            {
              Object lChoice = mChildren[lMoveIndex];

              //  Skip this for pseudo-noops since we don't want to expand them except when they are immediate
              //  children of the root (and in that case their heuristic value is the same as the root's)
              if (((lChoice instanceof TreeEdge) ? ((TreeEdge)lChoice).mPartialMove : (ForwardDeadReckonLegalMoveInfo)lChoice).mIsPseudoNoOp)
              {
                continue;
              }

              // Determine the heuristic value for this child.
              mTree.mHeuristic.getHeuristicValue(mTree.mChildStatesBuffer[lMoveIndex],
                                                mState,
                                                referenceNode.mState,
                                                mTree.mNodeHeuristicInfo);

              assert(checkFixedSum(mTree.mNodeHeuristicInfo.heuristicValue));

              TreeEdge edge = null;
              if (mTree.mNodeHeuristicInfo.treatAsSequenceStep)
              {
                if (mChildren[lMoveIndex] instanceof TreeEdge)
                {
                  edge = (TreeEdge)mChildren[lMoveIndex];
                }
                else
                {
                  edge = mTree.mEdgePool.allocate(mTree.mTreeEdgeAllocator);
                  edge.setParent(this, (ForwardDeadReckonLegalMoveInfo)mChildren[lMoveIndex]);
                  mChildren[lMoveIndex] = edge;
                }

                assert(edge!=null);

                edge.setHasHeuristicDeviation(true);
              }

              if (mTree.mNodeHeuristicInfo.heuristicWeight > 0)
              {
                boolean applyHeuristicHere = (firstIndexWithHeuristic != -1);
                double heuristicWeightToApply = 0;

                if (mTree.mNodeHeuristicInfo.treatAsSequenceStep || (pathTo != null && !previousEdgeHadHeuristicDeviation))
                {
                  applyHeuristicHere |= mTree.mNodeHeuristicInfo.treatAsSequenceStep;
                }
                else if (pathTo != null)
                {
                  heuristicWeightToApply = mTree.mNodeHeuristicInfo.heuristicWeight;

                  applyHeuristicHere = true;
                }

                if (applyHeuristicHere)
                {
                  if (firstIndexWithHeuristic == -1)
                  {
                    firstIndexWithHeuristic = lMoveIndex;
                    if (lMoveIndex > 0)
                    {
                      lMoveIndex = -1;
                      continue;
                    }
                  }
                  //  Create the edge if necessary
                  if (edge == null)
                  {
                    if (mChildren[lMoveIndex] instanceof TreeEdge)
                    {
                      edge = (TreeEdge)mChildren[lMoveIndex];
                    }
                    else
                    {
                      edge = mTree.mEdgePool.allocate(mTree.mTreeEdgeAllocator);
                      edge.setParent(this, (ForwardDeadReckonLegalMoveInfo)mChildren[lMoveIndex]);
                      mChildren[lMoveIndex] = edge;
                    }
                  }

                  assert(edge != null);

                  jointPartialMove[roleIndex] = edge.mPartialMove;

                  assert(linkageValid());

                  if (edge.getChildRef() == NULL_REF)
                  {
                    createChildNodeForEdge(edge, jointPartialMove);

                    assert(linkageValid());

                    assert(!evaluateTerminalOnNodeCreation || !calculateTerminalityAndAutoExpansion(get(edge.getChildRef()).mState).isTerminal);

                    assert(linkageValid());
                  }

                  TreeNode newChild = get(edge.getChildRef());

                  if (!newChild.mTerminal && (newChild.mNumVisits == 0 || newChild.mHeuristicWeight == 0 || Math.abs(newChild.mHeuristicValue-50) < EPSILON))
                  {
                    newChild.mHeuristicValue = mTree.mNodeHeuristicInfo.heuristicValue[0];
                    newChild.mHeuristicWeight = heuristicWeightToApply;

                    //  If this turns out to be a transition into an already visited child
                    //  then do not apply the heuristic seeding to the average scores
                    if (newChild.mNumVisits == 0)
                    {
                      for (int lii = 0; lii < mTree.mNumRoles; lii++)
                      {
                        double adjustedRoleScore;
                        double referenceRoleScore = referenceNode.getAverageScore(lii);

                        //  Weight by a measure of confidence in the reference score
                        //  TODO - experiment - should this be proportional to sqrt(num root visits)?
                        double referenceScoreWeight = referenceNode.mNumUpdates/50;
                        referenceRoleScore = (referenceRoleScore*referenceScoreWeight + 50)/(referenceScoreWeight + 1);

                        if (mTree.mNodeHeuristicInfo.heuristicValue[lii] > 50)
                        {
                          adjustedRoleScore = referenceRoleScore +
                                                (100 - referenceRoleScore) *
                                                (mTree.mNodeHeuristicInfo.heuristicValue[lii] - 50) /
                                                50;
                        }
                        else
                        {
                          adjustedRoleScore = referenceRoleScore -
                                                (referenceRoleScore) *
                                                (50 - mTree.mNodeHeuristicInfo.heuristicValue[lii]) /
                                                50;
                        }

                        double newChildRoleScore = (newChild.getAverageScore(lii) * newChild.mNumUpdates +
                                                    adjustedRoleScore * mTree.mNodeHeuristicInfo.heuristicWeight) /
                                                   (newChild.mNumUpdates + mTree.mNodeHeuristicInfo.heuristicWeight);
                        newChild.setAverageScore(lii, newChildRoleScore);
                      }
                    }

                    // Use the heuristic confidence to guide how many virtual rollouts to pretend there have been through
                    // the new child.
                    newChild.mNumUpdates += mTree.mNodeHeuristicInfo.heuristicWeight;
                    assert(!Double.isNaN(newChild.getAverageScore(0)));

                    newChild.mNumVisits += mTree.mNodeHeuristicInfo.heuristicWeight;
                    edge.setNumVisits(newChild.mNumVisits);
                  }
                }
              }
            }
          }
        }
      }

      assert(linkageValid());

      //validateAll();

      if (evaluateTerminalOnNodeCreation && (mTree.mRemoveNonDecisionNodes || roleIndex == mTree.mNumRoles - 1))
      {
        boolean completeChildFound = false;
        TreeNode decisiveCompletionNode = null;

        for (int lii = 0; lii < mNumChildren; lii++)
        {
          Object lChoice = mChildren[lii];
          if (lChoice instanceof TreeEdge)
          {
            long cr = ((TreeEdge)lChoice).getChildRef();
            if (cr != NULL_REF && get(cr) != null)
            {
              TreeNode lNode = get(cr);
              if (lNode.mTerminal)
              {
                //lNode.markComplete(lNode, lNode.depth);
                lNode.mComplete = true;
                lNode.mCompletionDepth = lNode.mDepth;

                if (!completeChildFound)
                {
                  completeChildFound = true;
                  mTree.mUnderlyingStateMachine.getLatchedScoreRange(mState,
                                                                     mTree.mRoleOrdering.roleIndexToRole(roleIndex),
                                                                     mTree.mLatchedScoreRangeBuffer);
                }

                if (mTree.mLatchedScoreRangeBuffer[0] != mTree.mLatchedScoreRangeBuffer[1] && lNode.getAverageScore(roleIndex) > mTree.mLatchedScoreRangeBuffer[1] - EPSILON)
                {
                  decisiveCompletionNode = lNode;
                }
              }
              if (lNode.mComplete)
              {
                completeChildFound = true;
              }
            }
          }
        }

        if (completeChildFound && !mComplete)
        {
          if (decisiveCompletionNode != null)
          {
            decisiveCompletionNode.processCompletion();
          }
          else
          {
            checkChildCompletion(true);
          }
        }
      }
    }

    assert(linkageValid());

    return this;
  }

  /**
   * Attach RAVE stats to this node.
   */
  private void attachRAVEStats()
  {
    assert(mRAVEStats == null) : "RAVE stats already attached";
    assert(mTree != null) : "Can't attach RAVE stats without a tree";
    assert(mTree.mGameSearcher.mUseRAVE) : "Shouldn't attach RAVE stats if not using RAVE";

    // Request RAVE counts from the pool.
    mRAVEStats = mTree.mRAVEStatsPool.allocate(mTree.mRAVEStatsAllocator);

    int lMaxDirectChildren = mTree.mGameCharacteristics.getChoicesHighWaterMark(mNumChildren);
    if (mRAVEStats.mCounts.length < lMaxDirectChildren)
    {
      // The pooled object wasn't big enough.  It won't be big enough for anybody else, so discard it and allocate a new
      // array.  We don't want to take the hit of trying to drain the whole pool all at once, so just allocate directly.
      // (We're still allowed to free back to the pool.)
      mRAVEStats = new RAVEStats(lMaxDirectChildren);
    }
  }

  /**
   * Detach RAVE stats from this node, returning them to the pool if suitable.
   */
  private void detachRAVEStats()
  {
    assert(mRAVEStats != null) : "No RAVE stats attached";
    assert(mTree != null) : "Can't detatch RAVE stats without a tree";
    assert(mTree.mGameSearcher.mUseRAVE) : "Shouldn't have RAVE stats if not using RAVE";

    int lMaxDirectChildren = mTree.mGameCharacteristics.getChoicesHighWaterMark(mNumChildren);
    if ((mRAVEStats.mCounts.length >= lMaxDirectChildren) && (mRAVEStats.mCounts.length < lMaxDirectChildren * 2))
    {
      mTree.mRAVEStatsPool.free(mRAVEStats, 0);
    }

    mRAVEStats = null;
  }

  private void validateScoreVector(double[] scores)
  {
    double total = 0;

    for (int lii = 0; lii < mTree.mNumRoles; lii++)
    {
      total += scores[lii];
    }

    if (total > 0 && Math.abs(total - 100) > EPSILON)
    {
      LOGGER.warn("Bad score vector");
    }

    if (total > 0 && mNumChildren != 0)
    {
      total = 0;
      int visitTotal = 0;

      for (short index = 0; index < mNumChildren; index++)
      {
        if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
        {
          Object lChoice = mChildren[index];

          TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
          if (edge != null && edge.getChildRef() != NULL_REF)
          {
            total += get(edge.getChildRef()).getAverageScore(0) * edge.getNumChildVisits();
            visitTotal += edge.getNumChildVisits();
          }
        }
      }

      if (visitTotal > 200 &&
          Math.abs(getAverageScore(0) - total / visitTotal) > 10)
      {
        LOGGER.warn("Parent stats do not match children");
      }
    }
  }

  private double explorationUCT(int effectiveTotalVisits,
                                TreeEdge edge,
                                int roleIndex)
  {
    // Extract the common parts of the calculation to avoid making expensive calls twice.
    double lVarianceExploration;
    double lUcbExploration;
    TreeNode childNode = get(edge.getChildRef());
    int effectiveNumChildVisits = edge.getNumChildVisits() + 1;

    //  It is possible for an un-visited node to still have children that have been visited due
    //  to transpositions, which means that we will need to apply UCT based on the child visit
    //  counts even though the parent count is 0.  In such cases the parent count is the same for all
    //  children so it just amounts to a common normalization factor that will not impact ordering,
    //  so set it to 1 so we get meaningful values
    if (effectiveTotalVisits == 0)
    {
      effectiveTotalVisits = 1;
    }

    //  If we're using weight decay we need to normalize the apparent sample sizes
    //  used to calculate the upper bound on variance for UCB-tuned or else the
    //  calculated upper bound on variance will be too low (we gain less information
    //  from a diluted weight playout than from a less diluted one).  Empirically this
    //  treatment produces far better results and allows UCB-tuned to continue to be used
    //  in conjunction with weight decay.
    //  Note - an analogous treatment of the sample sizes used to compute the simple UCB
    //  element is not helpful and actually does considerable harm in at last some games
    if (mTree.mWeightDecayKneeDepth > 0)
    {
      double normalizedNumVisits;
      double normalizedNumChildVisits;

      if (childNode.mNumUpdates > 0)
      {
        normalizedNumVisits = effectiveTotalVisits*(mNumUpdates + 1) / mNumVisits;
        normalizedNumChildVisits = effectiveNumChildVisits*(childNode.mNumUpdates + 1)/childNode.mNumVisits;
      }
      else
      {
        normalizedNumVisits = effectiveTotalVisits;
        normalizedNumChildVisits = effectiveNumChildVisits;
      }

      lVarianceExploration = 2 * Math.log(Math.max(normalizedNumVisits, normalizedNumChildVisits)) / normalizedNumChildVisits;
      lUcbExploration = 2 * Math.log(Math.max(effectiveTotalVisits, effectiveNumChildVisits)) / effectiveNumChildVisits;
    }
    else
    {
      lUcbExploration = 2 * Math.log(Math.max(effectiveTotalVisits, effectiveNumChildVisits)) / effectiveNumChildVisits;
      lVarianceExploration = lUcbExploration;
    }

    double result;

    if (mTree.USE_UCB_TUNED)
    {
      // When we propagate adjustments due to completion we do not also adjust the variance contribution so this can
      // result in 'impossibly' low (aka negative) variance - take a lower bound of 0
      double roleAverageScore = getAverageScore(roleIndex);
      double roleAverageSquaredScore = getAverageSquaredScore(roleIndex);

      double varianceBound = Math.max(0, roleAverageSquaredScore -
                                    roleAverageScore *
                                    roleAverageScore) /
                                    10000 +
                                    Math.sqrt(lVarianceExploration);
      result = getExplorationBias(edge) *
          Math.sqrt(Math.min(0.25, varianceBound) * lUcbExploration) / mTree.mRoleRationality[roleIndex];
    }
    else
    {
      result = getExplorationBias(edge) *
          Math.sqrt(lUcbExploration) / mTree.mRoleRationality[roleIndex];
    }

    result *= (1 + edge.explorationAmplifier)*(edge.isHyperEdge() ? 5 : 1);
    if (mTree.mGameSearcher.mUseGoalGreedy && this != mTree.mRoot)
    {
      if (childNode.mHeuristicValue <= mHeuristicValue)
      {
        result *= 0.25;  //  Supress searching of non-goal-increasing branches
      }
    }
    return result;
  }

  private double getAverageCousinMoveValue(TreeEdge relativeTo, int roleIndex)
  {
    TreeNode lNode = get(relativeTo.getChildRef());
    if (lNode.mDecidingRoleIndex == 0)
    {
      return lNode.getAverageScore(roleIndex);
    }
    else if (mTree.mCousinMovesCachedFor == NULL_REF || get(mTree.mCousinMovesCachedFor) != this)
    {
      mTree.mCousinMovesCachedFor = getRef();
      mTree.mCousinMoveCache.clear();
      mTree.mCachedMoveScorePool.clear(mTree.mMoveScoreInfoAllocator, false);

      for (TreeNode parent : mParents)
      {
        for (short index = 0; index < parent.mNumChildren; index++)
        {
          if (parent.mPrimaryChoiceMapping == null || parent.mPrimaryChoiceMapping[index] == index)
          {
            Object lChoice = parent.mChildren[index];

            TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
            if (edge == null || edge.getChildRef() == NULL_REF)
            {
              continue;
            }

            TreeNode child = get(edge.getChildRef());
            if (child != null)
            {
              for (short nephewIndex = 0; nephewIndex < child.mNumChildren; nephewIndex++)
              {
                Object rawChoice = child.mChildren[nephewIndex];
                Object nephewChoice = child.mChildren[child.mPrimaryChoiceMapping == null ? nephewIndex : child.mPrimaryChoiceMapping[nephewIndex]];

                TreeEdge nephewEdge = (nephewChoice instanceof TreeEdge ? (TreeEdge)nephewChoice : null);
                if (nephewEdge == null || nephewEdge.getChildRef() == NULL_REF)
                {
                  continue;
                }

                TreeNode nephew = get(nephewEdge.getChildRef());
                if (nephew != null && (nephew.mNumUpdates > 0 || nephew.mComplete))
                {
                  Move move = (rawChoice instanceof TreeEdge ? nephewEdge.mPartialMove : (ForwardDeadReckonLegalMoveInfo)rawChoice).mMove;
                  MoveScoreInfo accumulatedMoveInfo = mTree.mCousinMoveCache.get(move);
                  if (accumulatedMoveInfo == null)
                  {
                    accumulatedMoveInfo = mTree.mCachedMoveScorePool.allocate(mTree.mMoveScoreInfoAllocator);
                    mTree.mCousinMoveCache.put(move, accumulatedMoveInfo);
                  }

                  for (int lii = 0; lii < mTree.mNumRoles; lii++)
                  {
                    accumulatedMoveInfo.averageScores[lii] = (accumulatedMoveInfo.averageScores[lii] *
                        accumulatedMoveInfo.numSamples + nephew.getAverageScore(lii)) /
                        (accumulatedMoveInfo.numSamples + 1);
                  }
                  accumulatedMoveInfo.numSamples++;

                  assert(checkFixedSum(accumulatedMoveInfo.averageScores));
                }
              }
            }
          }
        }
      }
    }

    MoveScoreInfo accumulatedMoveInfo = mTree.mCousinMoveCache.get(relativeTo.mPartialMove.mMove);
    if (accumulatedMoveInfo == null)
    {
      if (lNode.mNumUpdates > 0)
      {
        mParents.get(0).dumpTree("subTree.txt");
        LOGGER.warn("No newphews found for search move including own child!");
        mTree.mCousinMovesCachedFor = NULL_REF;
      }
      //getAverageCousinMoveValue(relativeTo);
      return lNode.getAverageScore(roleIndex);
    }
    return accumulatedMoveInfo.averageScores[roleIndex];
  }

  private double getExplorationBias(TreeEdge moveEdge)
  {
    double result = mTree.mGameCharacteristics.getExplorationBias();

//    if ( mDepth < mTree.mRoot.mDepth + mTree.mNumRoles )
//    {
//      result /= 5;
//    }
//    if (moveEdge.moveWeight != 0)
//    {
//      result *= (1 + moveEdge.moveWeight/25);
//    }

    //result *= (tree.DEPENDENCY_HEURISTIC_STRENGTH * moveEdge.moveWeight + 0.5);
    return result;
  }

  private double sigma(double x)
  {
    return 1 / (1 + Math.exp(-x));
  }

  private double heuristicUCT(TreeEdge moveEdge)
  {
    return 0;
  }

  private double exploitationUCT(TreeEdge inboundEdge, int roleIndex)
  {
    //  Force selection of a pseudo-noop as an immediate child of the
    //  root as much as the best scoring node as there is a 50-50 chance we'll need to pass
    //  on this factor (well strictly (#factors-1)/#factors but 1:1 is good
    //  enough), so we need good estimates on the score for the pseudo-noop
    if (inboundEdge.mPartialMove.mIsPseudoNoOp && this == mTree.mRoot)
    {
      double bestChildScore = 0;

      for (short index = 0; index < mNumChildren; index++)
      {
        if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
        {
          Object lChoice = mChildren[index];

          TreeEdge edge2 = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
          if (edge2 != null && !edge2.mPartialMove.mIsPseudoNoOp && edge2.getChildRef() != NULL_REF)
          {
            double childUtility = effectiveExploitationScore(index, roleIndex);

            if (childUtility > bestChildScore)
            {
              bestChildScore = childUtility;
            }
          }
        }
      }

      return bestChildScore;
    }

    TreeNode lInboundChild = get(inboundEdge.getChildRef());
    if (mTree.mGameCharacteristics.isSimultaneousMove)
    {
      if (roleIndex == 0)
      {
        return lInboundChild.getAverageScore(roleIndex) / 100;
      }
      return getAverageCousinMoveValue(inboundEdge, roleIndex)/100;
    }

    if ( mTree.mMixiMaxBias > 0 )
    {
      return (lInboundChild.getAverageScore(roleIndex)*inboundEdge.getNumChildVisits() + mTree.mMixiMaxBias*lInboundChild.mBestDecidingScore)/(100*(inboundEdge.getNumChildVisits()+mTree.mMixiMaxBias));
    }
    double result = lInboundChild.getAverageScore(roleIndex) / 100;// + heuristicValue() / Math.log(numVisits+2);// + averageSquaredScore/20000;

    return result;
  }

  //  UCT value to use for an unexpanded child during select on its parent
  //  Basic MCTS would use a large value, guaranteeing that unexplored nodes
  //  always receive one visit before anything receives two.  However this can lead
  //  to very slow convergence in large branching factor games where there turns out to be
  //  a stand-out choice.  Instead we use the value that would be generated by a single
  //  visit to a child whose value was the same as that of the parent - i.e. - we start from
  //  the assumption that the best estimate of a child node's value without any play-throughs
  //  is the same as that of its parent
  private double unexpandedChildUCTValue(int roleIndex, TreeEdge edge)
  {
    if (mTree.mUseEstimatedValueForUnplayedNodes)
    {
      // Extract the common parts of the calculation to avoid making expensive calls twice.
      double lCommon = 2 * Math.log(mNumVisits + 1);

      double varianceBound = Math.sqrt(lCommon);
      double explorationTerm = mTree.mGameCharacteristics.getExplorationBias() *
             Math.sqrt(Math.min(0.5, varianceBound) * lCommon);

      if (edge != null)
      {
        explorationTerm *= (1 + edge.explorationAmplifier);
        explorationTerm += heuristicUCT(edge);
      }

      return explorationTerm + getAverageScore(roleIndex)/100 + mTree.mRandom.nextDouble() * EPSILON;
    }

    //  Else use standard MCTS very high values for unplayed
    return 10 + mTree.mRandom.nextDouble() * EPSILON + (edge == null ? 0 : edge.explorationAmplifier);
  }

  private void normalizeScores(boolean bTrace)
  {
    final int MIN_NUM_VISITS = 20;  //  Threshold child visits below which child weights are not renormalized
    double weightTotal = 0;
    int choosingRoleIndex = (mDecidingRoleIndex + 1) % mTree.mNumRoles;

    //  It is possible that transpositions can result in a rarely visited node
    //  being flagged for normalization due to value changes in a (transposed to and
    //  heavily visited by other routes) child.  In such cases it's not worth
    //  attempting to normalize (and may be impossible if no edges have actually
    //  been selected through yet!)
    if ( mNumVisits < NORMALIZATION_WARMUP_PERIOD )
    {
      return;
    }

    mUpdatesToNormalization = NORMALIZATION_PERIOD;

    for (int role = 0; role < mTree.mNumRoles; role++)
    {
      mTree.mNodeAverageScores[role] = 0;
      mTree.mNodeAverageSquaredScores[role] = 0;
    }

    double pivotScore = -Double.MAX_VALUE;
    double pivotScoreWeight = -1;
    boolean pivotIsHyper = false;
    int highestScoreIndex = -1;

    for (int lii = 0; lii < mNumChildren; lii++)
    {
      Object lChoice = mChildren[lii];

      if (lChoice instanceof TreeEdge)
      {
        TreeEdge edge = (TreeEdge)lChoice;
        int visits = edge.getNumChildVisits();

        if (edge.getChildRef() != NULL_REF && edge.isSelectable() && visits >= MIN_NUM_VISITS)
        {
          TreeNode child = get(edge.getChildRef());

          if (child != null)
          {
            double score = effectiveExploitationScore(lii, choosingRoleIndex);
            // We must pick the highest score of the children we plan to include in renormalization
            // or else the UCT renormalization formula breaks down for scores above a certain amount
            // greater than the score of the node we do choose as a pivot
            if (score > pivotScore)
            {
              pivotScore = score;
              pivotScoreWeight = visits;
              highestScoreIndex = lii;
              pivotIsHyper = edge.isHyperEdge();
            }
          }
        }
      }
    }

    double highestVisitFactor = Math.log(mNumVisits)/pivotScoreWeight;
    //  Note - the following line should remove biases from hyper-edge selection, but empirically
    //  (with or without this adjustment) normalization and hyper-edges just do not seem to mix well
    //  I do not know why, but for now normalization is just disabled in games with hyper-expansion
    double expBias = pivotIsHyper ? 2.5 : 0.5;  //  Account for boosted selection used on hyper edges
    double c = pivotScore + expBias*Math.sqrt(highestVisitFactor);


    for (int lii = 0; lii < mNumChildren; lii++)
    {
      Object lChoice = mChildren[lii];

      if (lChoice instanceof TreeEdge)
      {
        TreeEdge edge = (TreeEdge)lChoice;

        if (edge.getChildRef() != NULL_REF && edge.isSelectable())
        {
          TreeNode child = get(edge.getChildRef());

          if (child != null )
          {
            //  Normalize by assuming that the highest scoring child has a 'correct' visit count and
            //  producing normalized visit counts for the other children based on their scores and the
            //  standard UCT distribution given the parent visit count.  Note that if the score
            //  distribution of the children is static (does not change with increasing samples) then
            //  this will precisely reproduce the visit count of continued UCT sampling.  If the distributions
            //  are changing (as is typically the case) then this will re-calculate the parent score estimates
            //  as if the current child scores WERE from a static distribution.  For example, suppose one
            //  child starts out looking good, but eventually converges to a lower score.  In such a case
            //  un-normalized MCTS will weight that child's score more highly than the renormalized version,
            //  and result in slower parent convergence.  Normalization should increase convergence rates in
            //  such cases, especially if child convergence is non-monotonic
            double chooserScore = effectiveExploitationScore(lii, choosingRoleIndex);
            int numChildVisits = edge.getNumChildVisits();
            double weight = (numChildVisits >= MIN_NUM_VISITS ? expBias*expBias*Math.log(mNumVisits)/((c-chooserScore)*(c-chooserScore)) : numChildVisits);
            assert(lii != highestScoreIndex || Math.abs(weight-edge.getNumChildVisits()) < EPSILON);

            if (bTrace)
            {
              LOGGER.info("Move " + edge.descriptiveName() + "choosing score " + child.getAverageScore(choosingRoleIndex) + " [" + edge.getNumChildVisits() + "], weight: " + weight);
            }
            for (int role = 0; role < mTree.mNumRoles; role++)
            {
              double score = child.getAverageScore(role);
              double squaredScore = child.getAverageSquaredScore(role);

              //  Normalize for any heuristic bias that would normally have been
              //  applied for propagations from this child to this parent
              if (mHeuristicWeight > 0)
              {
                double applicableValue = (mHeuristicValue > 50 ? mHeuristicValue : 100 - mHeuristicValue);

                if (applicableValue > EPSILON)
                {
                  double rootSquaredScore = Math.sqrt(squaredScore);

                  if ((mHeuristicValue > 50) == (role == 0))
                  {
                    score = score + (100 - score) * (applicableValue - 50) / 50;
                    rootSquaredScore = rootSquaredScore + (100 - rootSquaredScore) * (applicableValue - 50) / 50;
                  }
                  else
                  {
                    score = score - (score) * (applicableValue - 50) / 50;
                    rootSquaredScore = rootSquaredScore - (rootSquaredScore) * (applicableValue - 50) / 50;
                  }

                  squaredScore = rootSquaredScore*rootSquaredScore;
                }
              }
              mTree.mNodeAverageScores[role] += weight*score;
              mTree.mNodeAverageSquaredScores[role] += weight*squaredScore;
            }
            weightTotal += weight;
          }
        }
      }
    }

    if (weightTotal > 0)
    {
      for (int role = 0; role < mTree.mNumRoles; role++)
      {
        double newValue = mTree.mNodeAverageScores[role]/weightTotal;

        //  If normalization caused a significant change in value (for now taking that
        //  as 3%) cause the parents to be normalized next time they are selected through
        if ( Math.abs(getAverageScore(role) - newValue) > 3 )
        {
          //  Flag the parents as in need of normalization
          for(TreeNode parent : mParents)
          {
            parent.mUpdatesToNormalization = 0;
          }
        }
        setAverageScore(role, newValue);
        setAverageSquaredScore(role, mTree.mNodeAverageSquaredScores[role]/weightTotal);
      }
    }
  }

  private double getUCTSelectionValue(TreeEdge edge, TreeNode c, int roleIndex)
  {
    double uctValue;

    if (c.mComplete)
    {
      edge.explorationAmplifier = 0;
    }

    if (c.mNumVisits == 0)
    {
      uctValue = unexpandedChildUCTValue(roleIndex, edge);
    }
    else
    {
      assert(edge.getNumChildVisits() <= c.mNumVisits || (edge.hyperSuccessor != null && c.mComplete));

      //  Various experiments have been done to try to find the best selection
      //  weighting, and it seems that using the number of times we've visited the
      //  child FROM THIS PARENT coupled with the num visits here in standard UCT
      //  manner works best.  In particular using the visit count on the child node
      //  (rather than through the child edge to it, which can be radically different
      //  in highly transpositional games) does not seem to work as well (even with a
      //  'corrected' parent visit count obtained by summing the number of visits to all
      //  the child's parents)
      //  Empirically the half value for the exploration term applied to complete
      //  children seems to give decent results.  Both applying it in full and not
      //  applying it (both of which can be rationalized!) seem to fare worse in at
      //  least some games
      uctValue = (c.mComplete ? explorationUCT(mNumVisits,
                                              edge,
                                              roleIndex)/2
                             : explorationUCT(mNumVisits,
                                              edge,
                                              roleIndex)) +
                 exploitationUCT(edge, roleIndex) +
                 heuristicUCT(edge);
    }

    return uctValue;
  }

  private static double RAVE_EXPLORATION_REDUCTION_FACTOR = 7.0;

  private double getRAVEExplorationValue(TreeEdge edge)
  {
    if (edge == null || edge.getNumChildVisits() == 0)
    {
      return 0.5;
    }

    //  It is possible for an un-visited node to still have children that have been visited due
    //  to transpositions, which means that we will need to apply UCT based on the child visit
    //  counts even though the parent count is 0.  In such cases the parent count is the same for all
    //  children so it just amounts to a common normalization factor that will not impact ordering,
    //  so set it to 1 so we get meaningful values
    int effectiveTotalVisits = mNumVisits;
    if (effectiveTotalVisits == 0)
    {
      effectiveTotalVisits = 1;
    }
    return getExplorationBias(edge) * Math.sqrt(2 * Math.log(effectiveTotalVisits) / edge.getNumChildVisits()) /
           RAVE_EXPLORATION_REDUCTION_FACTOR;
  }

  private double getRAVESelectionValue(int edgeIndex)
  {
    return getRAVEExplorationValue(mChildren[edgeIndex] instanceof TreeEdge ? (TreeEdge)mChildren[edgeIndex] : null) + (mRAVEStats == null ? 0 : mRAVEStats.mScores[edgeIndex] / 100);
  }

  private double getSelectionValue(int edgeIndex, TreeNode c, int roleIndex)
  {
    TreeEdge edge = (mChildren[edgeIndex] instanceof TreeEdge ? (TreeEdge)mChildren[edgeIndex] : null);
    double UCTValue = (c == null ? unexpandedChildUCTValue(roleIndex, edge) : getUCTSelectionValue(edge, c, roleIndex));
    double result;

    if (mTree.mGameSearcher.mUseRAVE && (c == null || !c.mComplete))
    {
      int numChildVisits = (edge == null ? 0 : edge.getNumChildVisits());
      double lRAVEValue = getRAVESelectionValue(edgeIndex);
      int lRAVECount = (mRAVEStats == null) ? 0 : mRAVEStats.mCounts[edgeIndex];
      double lRAVEWeight = (lRAVECount) /
                          (lRAVECount + numChildVisits + RAVE_BIAS * numChildVisits * lRAVECount + 1);

      result = (1 - lRAVEWeight) * UCTValue + lRAVEWeight * lRAVEValue;
    }
    else
    {
      result = UCTValue;
    }

    if ( c != null )
    {
      //  A local known search status strongly impacts the choice.  Basically this establishes a hierarchy
      //  to all intents and purposes that means local search wins will essentially always be selected
      //  over anything but complete wins, and local search losses will almost never be selected
      //  However, crucially if ALL choices are local search losses normal selection ordering will result
      switch(c.mLocalSearchStatus)
      {
        case LOCAL_SEARCH_LOSS:
          result /= 10;
          break;
        case LOCAL_SEARCH_WIN:
          result *= 10;
          break;
          //$CASES-OMITTED$
        default:
          break;
      }
    }

    return result;
  }

  private double effectiveExploitationScore(int edgeIndex, int roleIndex)
  {
    double lResult;
    TreeEdge edge = (TreeEdge)mChildren[edgeIndex];
    TreeNode c = get(edge.getChildRef());

    if (mRAVEStats != null && !c.mComplete)
    {
      double lRAVEValue = mRAVEStats.mScores[edgeIndex] / 100;
      int lRAVECount = mRAVEStats.mCounts[edgeIndex];
      double lRAVEWeight = (lRAVECount) /
                           (lRAVECount + edge.getNumChildVisits() + RAVE_BIAS * edge.getNumChildVisits() * lRAVECount + 1);

      lResult = (1 - lRAVEWeight) * c.getAverageScore(roleIndex) / 100 + lRAVEWeight * lRAVEValue;
    }
    else
    {
      lResult = c.getAverageScore(roleIndex) / 100;
    }

    return lResult;
  }

  /**
   * Select the child node to descend through during MCTS node selection.
   *
   * @param path - path taken from the root to this node
   * @param jointPartialMove - partial current move at this depth
   * @param xiForceMove - force selection of the specified move, provided that it's our turn (or null not to force).
   *
   * @return PathElement to add to the path representing the selected child
   */
  TreePathElement select(TreePath path, ForwardDeadReckonLegalMoveInfo[] jointPartialMove, Move xiForceMove)
  {
    assert(ThreadControl.checkTreeOwnership());

    TreeEdge selected = null;
    int selectedIndex = -1;
    int bestSelectedIndex = -1;
    double bestCompleteValue = Double.MIN_VALUE;
    TreeNode bestCompleteNode = null;
    double bestValue = -Double.MAX_VALUE;

    //	Find the role this node is choosing for
    int roleIndex = (mDecidingRoleIndex + 1) % mTree.mNumRoles;

    mTree.mCousinMovesCachedFor = NULL_REF;
    //LOGGER.debug("Select in " + state);
    assert (mNumChildren != 0);
    {
      mTree.mGameSearcher.mAverageBranchingFactor.addSample(mNumChildren);

      //  If there is only one choice we have to select it
      if (mNumChildren == 1)
      {
        //  Non-simultaneous move games always collapse one-choice nodes
        assert(!mTree.mRemoveNonDecisionNodes || this == mTree.mRoot);
        selectedIndex = 0;
      }
      else if ((xiForceMove != null) && (roleIndex == 0))
      {
        // Find the move that we already know we're going to play.
        for (short lii = 0; lii < mNumChildren; lii++)
        {
          if (mChildren[lii] instanceof TreeEdge)
          {
            TreeEdge lEdge = (TreeEdge)mChildren[lii];
            if (!lEdge.mPartialMove.mIsPseudoNoOp && lEdge.mPartialMove.mMove.equals(xiForceMove))
            {
              selectedIndex = lii;
              break;
            }
          }
        }

        assert(selectedIndex != -1 || mTree.mFactor != null) : "Failed to find forced move: " + xiForceMove;
      }

      if (selectedIndex == -1)
      {
        //calculatePathMoveWeights(path);

        //  Perform a normalization if one is due
        if (mTree.USE_NODE_SCORE_NORMALIZATION && mUpdatesToNormalization-- <= 0 &&
             (!mTree.mGameCharacteristics.isSimultaneousMove || roleIndex == 0))
        {
          normalizeScores(false);
        }

        //  We just re-use the last selected child if it's still a valid choice until the cache of its index
        //  is cleared, at which point we use UCT to make the best new choice.  This flag is cleared on:
        //  A child completing
        //  Unexpansion of the node
        //  Back-propagation of a playout result through this node
        //  The reason we do this is historical and not fully understood.  The asynchronous nature of back-propagation
        //  processing means that there is a delayed clearing of this cached selection choice dependent on the
        //  pipeline latency, with the result that a node which has not recently been selected through will make a choice of
        //  child and then stick with it for a while.  It is unclear why this is beneficial (but empirically it certainly is)
        //  It would be highly desirable to find a more direct means to obtain the benefit, since the 'accident' of pipeline
        //  latency is both necessarily coincidental and cannot possible be optimally tuned (if nothing else it will depend
        //  on things like thread counts and relative performance of playouts to MCTS expansions, which will vary by game!)
        //  Several days sepnt trying to find such a mechanism have so far failed, so for now we're stuck with this 'by-product'
        //  mechanism
        if (mLastSelectionMade != -1 && (mTree.mFactor == null || this != mTree.mRoot) && !mTree.mHeuristic.isEnabled())
        {
          Object lChoice = mChildren[mLastSelectionMade];
          if (lChoice instanceof TreeEdge)
          {
            TreeEdge edge;

            edge = (TreeEdge)lChoice;

            //  Hyper-edges may become stale due to down-stream links being freed - ignore
            //  hyper paths with stale linkage
            if(edge.hyperSuccessor != null && edge.hyperLinkageStale())
            {
              deleteHyperEdge(mLastSelectionMade);
            }
            else
            {
              long cr = edge.getChildRef();

              if(cr != NULL_REF)
              {
                TreeNode c = get(cr);
                if (c != null && (!c.mComplete) && !c.mAllChildrenComplete)
                {
                  selectedIndex = mLastSelectionMade;
                }
              }
            }
          }
        }

        if (selectedIndex == -1)
        {
          boolean hyperLinksRemoved;

          do
          {
            hyperLinksRemoved = false;

            for (short lii = 0; lii < mNumChildren; lii++)
            {
              //  Only select one move that is state-equivalent, and don't allow selection of a pseudo-noop
              if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[lii] == lii)
              {
                Object lChoice = mChildren[lii];

                if (lChoice == null)
                {
                  //  Previously removed stale hyper-edge (slot)
                  continue;
                }

                TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
                double uctValue;
                long cr;
                TreeNode c;

                if (edge != null && (cr = edge.getChildRef()) != NULL_REF && (c = get(cr)) != null)
                {
                  //  In the presence of hyper edges some normal edges will not be selectable because
                  //  they are just sub-elements of selectable hyper-edges
                  if (!edge.isSelectable())
                  {
                    continue;
                  }

                  //  Hyper-edges may become stale due to down-stream links being freed - ignore
                  //  hyper paths with stale linkage
                  if(edge.hyperSuccessor != null && edge.hyperLinkageStale())
                  {
                    deleteHyperEdge(lii);
                    hyperLinksRemoved = true;
                    continue;
                  }

                  //  Don't allow selection of a pseudo-noop
                  //  except from the root since we just want to know the difference in cost or omitting one
                  //  move (at root level) if we play in another factor
                  if (mTree.mRoot == this || !edge.mPartialMove.mIsPseudoNoOp)
                  {
                    if ( mTree.mMixiMaxBias > 0 && c.getAverageScore(roleIndex) > mBestDecidingScore )
                    {
                      mBestDecidingScore = c.getAverageScore(roleIndex);
                    }

                    //  Don't preferentially explore paths once they are known to have complete results
                    uctValue = getSelectionValue(lii, c, roleIndex);

                    //  If the node we most want to select through is complete (so there is nothing further to
                    //  learn) we select the best non-complete choice but record the fact
                    //  so that on propagation of the result we can propagate upwards from this
                    //  node the score of the complete node that in some sense 'should' have
                    //  been selected
                    //  Note - in simultaneous move games all the node's children can be complete
                    //  without the node being complete, but we cannot take the same approach there because
                    //  we need this node to be on the selection path so that it continues to get updates
                    if (!c.mComplete)
                    {
                      if (uctValue > bestValue)
                      {
                        selectedIndex = lii;
                        bestValue = uctValue;
                      }
                    }
                    else
                    {
                      if (uctValue > bestCompleteValue)
                      {
                        bestCompleteValue = uctValue;
                        bestCompleteNode = c;
                        bestSelectedIndex = lii;
                      }
                    }
                  }
                }
                else if (mTree.mRoot == this || !(edge == null ? (ForwardDeadReckonLegalMoveInfo)lChoice : edge.mPartialMove).mIsPseudoNoOp)
                {
                  if (edge != null && edge.hyperSuccessor != null)
                  {
                    //  Stale hyper-link - can be ignored now its target has gone
                    continue;
                  }

                  //  A null child ref in an extant edge is a not-yet selected through
                  //  path which is asserted to be non-terminal and unvisited
                  uctValue = getSelectionValue(lii, null, roleIndex);
                  //uctValue = unexpandedChildUCTValue(roleIndex, edge);

                  if (uctValue > bestValue)
                  {
                    selectedIndex = lii;
                    bestValue = uctValue;
                  }
                }
              }
            }
          } while(hyperLinksRemoved);
        }
      }
    }

    if (selectedIndex == -1)
    {
      //  This can happen only if all children are complete.  If we are explicitly allowing
      //  selection through complete nodes then the best one will have been determined and
      //  we should use it.  If this is not the case then what has happened is that a child
      //  has been completed by an expansion, and when completions are processed this node will
      //  also be completed, but that has not happened yet.  In that case a random selection
      //  will be fine as this node is already actually fully determined
      if(bestSelectedIndex != -1)
      {
        selectedIndex = bestSelectedIndex;
        bestValue = bestCompleteValue;
      }
      else
      {
        TreeEdge chosenEdge;

        do
        {
          selectedIndex = mTree.mRandom.nextInt(mNumChildren);
          if (mPrimaryChoiceMapping != null)
          {
            selectedIndex = mPrimaryChoiceMapping[selectedIndex];
          }
          if(mChildren[selectedIndex] instanceof TreeEdge)
          {
            chosenEdge = (TreeEdge)mChildren[selectedIndex];
            assert(get(chosenEdge.getChildRef()) != null);
          }
          else
          {
            //  This can happen in the case where the randomly selected node happened to be the pseudo-noop
            //  which is itself unexpanded (as it will be if the other choice is a regular noop)
            chosenEdge = null;
          }
        } while(chosenEdge == null || (mTree.mRoot != this && chosenEdge.mPartialMove.mIsPseudoNoOp));

        assert(chosenEdge.mPartialMove.mIsPseudoNoOp || get(chosenEdge.getChildRef()).mComplete);
      }
    }
    assert(selectedIndex != -1);

    mLastSelectionMade = (short)selectedIndex;

    //  Expand the edge if necessary
    Object lChoice = mChildren[selectedIndex];

    if (lChoice instanceof TreeEdge)
    {
      selected = (TreeEdge)lChoice;
    }
    else
    {
      selected = mTree.mEdgePool.allocate(mTree.mTreeEdgeAllocator);
      selected.setParent(this, (ForwardDeadReckonLegalMoveInfo)lChoice);
      mChildren[selectedIndex] = selected;
    }

    jointPartialMove[roleIndex] = selected.mPartialMove;

    if (selected.getChildRef() == NULL_REF)
    {
      //  If this node does not follow its parent in joint-move order (because interim forced-choices have
      //  been trimmed out) then the joint move array will not necessarily contain the correct
      //  information and must be calculated a priori.  For now we have no means to distinguish this
      //  case so we always do so.  This is not expensive because the following creation of the child
      //  node anyway has top call getNextStat() and thus run the state through the state machine,
      //  which is the bulk of the cost
      mTree.setForcedMoveProps(mState, jointPartialMove);

      createChildNodeForEdge(selected, jointPartialMove);

      assert(!mTree.mEvaluateTerminalOnNodeCreation ||
             (mDepth < mTree.mGameCharacteristics.getEarliestCompletionDepth() && !mTree.mHeuristic.isEnabled()) ||
             this == mTree.mRoot ||
             !calculateTerminalityAndAutoExpansion(get(selected.getChildRef()).mState).isTerminal);
      assert(linkageValid());
    }

    assert(get(selected.getChildRef()) != null);

    if (!mComplete && (mTree.mRemoveNonDecisionNodes || roleIndex == 0) && MCTSTree.USE_STATE_SIMILARITY_IN_EXPANSION)
    {
      mTree.mStateSimilarityMap.add(this);
    }

    final double explorationAmplifierDecayRate = 0.6;
    selected.explorationAmplifier *= explorationAmplifierDecayRate;
    TreePathElement result = null;
    //  If we selected a hyper-edge then we actually need to push all of its constituent elements
    //  so that the stats update applies to the correct intermediate states also
    if (selected.hyperSuccessor == null)
    {
      assert(get(selected.getChildRef()).mParents.contains(this));
      result = path.push(this, selected);
    }
    else
    {
      TreeNode intermediaryParent = this;

      while(selected.hyperSuccessor != null)
      {
        //  Find the principal edge for the next part of the hyper-edge's sub-path
        TreeEdge principalEdge = null;

        for (int lMoveIndex = 0; lMoveIndex < intermediaryParent.mNumChildren; lMoveIndex++)
        {
          Object intermediaryChoice = intermediaryParent.mChildren[lMoveIndex];

          if (intermediaryChoice instanceof TreeEdge)
          {
            TreeEdge candidateEdge = (TreeEdge)intermediaryChoice;

            if (candidateEdge.mPartialMove.mMove == selected.mPartialMove.mMove)
            {
              assert(!candidateEdge.isSelectable());
              principalEdge = candidateEdge;
              break;
            }
          }
        }

        assert(principalEdge != null);

        result = path.push(intermediaryParent, principalEdge);
        principalEdge.incrementNumVisits();

        assert(principalEdge.getChildRef() == selected.nextHyperChild);
        TreeNode nextNode = get(principalEdge.getChildRef());

        assert(nextNode.mParents.contains(intermediaryParent));
        intermediaryParent = nextNode;

        assert(intermediaryParent != null);

        //  It is possible that node completion has propagated 'part way' through
        //  a hyper-edge.  If so stop at the first complete node encountered when
        //  resolving the sub-path
        if (intermediaryParent.mComplete)
        {
          break;
        }

        intermediaryParent.mNumVisits++;
        selected.incrementNumVisits();

        selected = selected.hyperSuccessor;
      }

      if (!intermediaryParent.mComplete)
      {
        assert(get(selected.getChildRef()).mParents.contains(intermediaryParent));
        result = path.push(intermediaryParent, selected);
      }
    }

    assert(result != null);

    //  If the node that should have been selected through was complete
    //  note that in the path, so that on application of the update
    //  the propagation upward from this node can be corrected
    if (bestCompleteNode != null && bestCompleteValue > bestValue )
    {
      assert(mChildren[bestSelectedIndex] instanceof TreeEdge);
      TreeEdge bestSelectedEdge = (TreeEdge)mChildren[bestSelectedIndex];
      assert(bestCompleteNode == get(bestSelectedEdge.getChildRef()));

      result.setScoreOverrides(bestCompleteNode);
      bestCompleteNode.mNumVisits++;
      bestSelectedEdge.incrementNumVisits();
      mLastSelectionMade = -1;
    }

    //  Update the visit counts on the selection pass.  The update counts
    //  will be updated on the back-propagation pass
    mNumVisits++;
    selected.incrementNumVisits();

    return result;
  }

  public boolean isUnexpanded()
  {
    return mNumChildren == 0 || mComplete;
  }

  private double scoreForMostLikelyResponseRecursive(TreeNode from,
                                                     int forRoleIndex)
  {
    //	Stop recursion at the next choice
    if (mNumChildren == 0 || mComplete)
    {
      return getAverageScore(forRoleIndex);
    }
    else if ((mDecidingRoleIndex + 1) % mTree.mNumRoles == forRoleIndex &&
        from != null && mNumChildren > 1)
    {
      return from.getAverageScore(forRoleIndex); //	TEMP TEMP TEMP
    }

    double result = 0;
    double childResult = -Double.MAX_VALUE;

    for (short index = 0; index < mNumChildren; index++)
    {
      if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
      {
        Object lChoice = mChildren[index];

        TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
        if (edge != null && get(edge.getChildRef()) != null)
        {
          TreeNode lNode = get(edge.getChildRef());
          double childVal = lNode.getAverageScore(lNode.mDecidingRoleIndex);

          if (childVal > childResult)//&& edge.child.node.numVisits > 500)
          {
            childResult = childVal;
            result = lNode.scoreForMostLikelyResponseRecursive(this, forRoleIndex);
          }
        }
      }
    }

    return (childResult == -Double.MAX_VALUE ? getAverageScore(forRoleIndex)
        : Math
        .min(result,
             getAverageScore(forRoleIndex)));
  }

  private double scoreForMostLikelyResponse()
  {
    return scoreForMostLikelyResponseRecursive(null, mDecidingRoleIndex);
  }

  private String stringizeScoreVector()
  {
    StringBuilder sb = new StringBuilder();

    sb.append("[");
    for (int lii = 0; lii < mTree.mNumRoles; lii++)
    {
      if (lii > 0)
      {
        sb.append(", ");
      }
      sb.append(FORMAT_2DP.format(getAverageScore(lii)));
    }
    sb.append("]");
    if (mTerminal)
    {
      sb.append('T');
    }
    if (mComplete)
    {
      sb.append(" (complete");
      sb.append('@');
      sb.append(mCompletionDepth);
      sb.append(')');
    }

    return sb.toString();
  }

  private int traceFirstChoiceNode(int xiResponsesTraced, boolean forceTrace)
  {
    if (mNumChildren == 0)
    {
      LOGGER.info("    No choice response scores " + stringizeScoreVector());
    }
    else if (mNumChildren > 1)
    {
      for (short index = 0; index < mNumChildren; index++)
      {
        if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
        {
          Object lChoice = mChildren[index];

          if (lChoice == null)
          {
            continue;
          }

          TreeEdge edge2 = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
          if (edge2 != null)
          {
            if (!edge2.isSelectable())
            {
              continue;
            }
            if (edge2.getChildRef() != NULL_REF)
            {
              if (get(edge2.getChildRef()) != null)
              {
                TreeNode lNode2 = get(edge2.getChildRef());
                String lLog = "    Response " +
                              edge2.mPartialMove.mMove + (edge2.isHyperEdge() ? " (hyper)" : "") +
                              " scores " + lNode2.stringizeScoreVector() +
                              ", visits " + lNode2.mNumVisits + " [edge " + edge2.getNumChildVisits() + ", updates " + lNode2.mNumUpdates + "]" +
                              ", ref : " + lNode2.mRef +
                              (mRAVEStats != null ? (", RAVE[" + mRAVEStats.mCounts[index] + ", " + FORMAT_2DP.format(mRAVEStats.mScores[index]) + "]") : "") +
                              (lNode2.mComplete ? " (complete)" : "") +
                              (lNode2.mLocalSearchStatus.HasResult() ? ("(" + lNode2.mLocalSearchStatus + ")") : "");

                if (xiResponsesTraced < 400 || forceTrace)
                {
                  LOGGER.debug(lLog);
                }
                else
                {
                  if (xiResponsesTraced == 400)
                  {
                    LOGGER.debug("(Further responses output at trace level)");
                  }
                  LOGGER.trace(lLog);
                }
                xiResponsesTraced++;
              }
              else
              {
                String lLog = "    Response " + edge2.mPartialMove.mMove + " trimmed";

                if (xiResponsesTraced < 400)
                {
                  LOGGER.debug(lLog);
                }
              }
            }
            else
            {
              String lLog = "    Response " + edge2.mPartialMove.mMove + " unexpanded";

              if (xiResponsesTraced < 400)
              {
                LOGGER.debug(lLog);
              }
            }
          }
          else
          {
            String lLog = "    Response " +
                ((ForwardDeadReckonLegalMoveInfo)lChoice).mMove +
                " unexpanded edge";

            if (xiResponsesTraced < 400)
            {
              LOGGER.debug(lLog);
            }
          }
        }
      }
    }
    else if (mChildren[0] instanceof TreeEdge)
    {
      TreeEdge edge2 = (TreeEdge)mChildren[0];

      if (edge2.getChildRef() != NULL_REF && get(edge2.getChildRef()) != null)
      {
        xiResponsesTraced = get(edge2.getChildRef()).traceFirstChoiceNode(xiResponsesTraced, forceTrace);
      }
      else
      {
        String lLog = "    Response " + edge2.mPartialMove.mMove + " unexpanded";

        if (xiResponsesTraced < 400 || forceTrace)
        {
          LOGGER.debug(lLog);
        }
      }
    }
    else
    {
      String lLog = "    Response " +
          ((ForwardDeadReckonLegalMoveInfo)mChildren[0]).mMove +
          " unexpanded edge";

      if (xiResponsesTraced < 400 || forceTrace)
      {
        LOGGER.debug(lLog);
      }
    }

    return xiResponsesTraced;
  }

  private void indentedPrint(PrintWriter writer, int depth, String line)
  {
    StringBuilder indent = new StringBuilder();

    for (int lii = 0; lii < depth; lii++)
    {
      indent.append(" ");
    }
    writer.println(indent + line);
  }

  private void dumpTree(PrintWriter writer, int dumpDepth, TreeEdge arrivalPath, int childIndex)
  {
    if (arrivalPath == null)
    {
      indentedPrint(writer, dumpDepth * 2, "Root scores " +
          stringizeScoreVector());
    }
    else
    {
      indentedPrint(writer,
                    dumpDepth * 2,
                    "@" +
                        dumpDepth +
                        ": Move " +
                        arrivalPath.descriptiveName() +
                        " [" + childIndex + "] D" + mDepth +
                        " (choosing role " + (mDecidingRoleIndex + 1) % mTree.mNumRoles + ")" +
                        " scores " + stringizeScoreVector() + "[" + mHeuristicValue + "@" + mHeuristicWeight + "] (ref " + mRef +
                        ") - visits: " + mNumVisits + " (" +
                        arrivalPath.getNumChildVisits() + ", " + arrivalPath.hasHeuristicDeviation() + "), updates: " + mNumUpdates);
    }

    if (mSweepSeq == mTree.mSweepInstance)
    {
      indentedPrint(writer, (dumpDepth + 1) * 2, "...transition...");
    }
    else
    {
      mSweepSeq = mTree.mSweepInstance;

      for (short index = 0; index < mNumChildren; index++)
      {
        if (mPrimaryChoiceMapping == null || mPrimaryChoiceMapping[index] == index)
        {
          Object lChoice = mChildren[index];
          if (lChoice == null)
          {
            continue;
          }

          TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
          if (edge != null && edge.getChildRef() != NULL_REF && get(edge.getChildRef()) != null && edge.isSelectable())
          {
            get(edge.getChildRef()).dumpTree(writer, dumpDepth + 1, edge, index);
          }
          else if (edge != null && !edge.isSelectable())
          {
            indentedPrint(writer,
                          (dumpDepth + 1) * 2,
                          "@" +
                              (dumpDepth + 1) +
                              ": Move " +
                              edge.mPartialMove.mMove +
                              " unselectable (" + edge.getNumChildVisits() + ")");
          }
          else
          {
            indentedPrint(writer,
                          (dumpDepth + 1) * 2,
                          "@" +
                              (dumpDepth + 1) +
                              ": Move " +
                              (edge == null ? (ForwardDeadReckonLegalMoveInfo)lChoice : edge.mPartialMove).mMove +
                              " unexpanded");
          }
        }
      }
    }
  }

  /**
   * Dump the tree to the specified file.
   *
   * @param xiFilename - filename (in temp. directory) or path from working directory.
   */
  void dumpTree(String xiFilename)
  {
    mTree.mSweepInstance++;

    try
    {
      // If a directory has been specified, assume it's relative to the working directory.  Otherwise, store in the
      // temporary directory.
      File f;
      if (xiFilename.contains("/"))
      {
        // A path has been specified.  It's relative to our current directory.
        f = new File(xiFilename);
      }
      else
      {
        // No path specified - put the output in the temporary directory.
        f = new File(TEMP_DIR, xiFilename);
      }
      PrintWriter writer = new PrintWriter(f);
      dumpTree(writer, 0, null, 0);
      writer.close();
    }
    catch (Exception e)
    {
      GamerLogger.logStackTrace("StateMachine", e);
    }
  }

  public FactorMoveChoiceInfo getBestMove(boolean xiTraceResponses, StringBuffer xbPathTrace, boolean xiFirstDecision)
  {
    double bestScore = -Double.MAX_VALUE;
    double bestMoveScore = -Double.MAX_VALUE;
    double bestRawScore = -Double.MAX_VALUE;
    TreeEdge rawBestEdgeResult = null;
    TreeEdge bestEdge = null;
    boolean anyComplete = false;
    TreeNode bestNode = null;
    FactorMoveChoiceInfo lResult = new FactorMoveChoiceInfo();
    int lResponsesTraced = 0;
    GdlConstant primaryMoveName = null;
    GdlConstant secondaryMoveName = null;
    TreeEdge firstPrimaryEdge = null;
    TreeEdge firstSecondaryEdge = null;
    int numPrimaryMoves = 0;
    int numSecondaryMoves = 0;

    //  If were asked for the first actual decision drill down through any leading no-choice path
    if (xiFirstDecision && mNumChildren == 1)
    {
      return get(((TreeEdge)mChildren[0]).getChildRef()).getBestMove(xiTraceResponses, xbPathTrace, xiFirstDecision);
    }

    //  If there is no pseudo-noop then there cannot be any penalty for not taking
    //  this factor's results - we simply return a pseudo-noop penalty value of 0
    lResult.mPseudoNoopValue = 100;

    // This routine is called recursively for path tracing purposes.  When
    // calling this routine for path tracing purposes, don't make any other
    // debugging output (because it would be confusing).
    boolean lRecursiveCall = (xbPathTrace != null);

    // Find the role which has a choice at this node.  If this function is
    // being called for real (rather than for debug trace) it MUST be our role
    // (always 0), otherwise why are we trying to get the best move?
    int roleIndex = (mDecidingRoleIndex + 1) % mTree.mNumRoles;
    assert(lRecursiveCall || roleIndex == 0 || xiFirstDecision);
    assert(mNumChildren != 0) : "Asked to get best move when there are NO CHILDREN!";

    int maxChildVisitCount = 1;
    if (!lRecursiveCall)
    {
      for (int lii = 0; lii < mNumChildren; lii++)
      {
        Object lChoice = mChildren[lii];
        TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
        if (edge != null && edge.getChildRef() != NULL_REF)
        {
          TreeNode lNode = get(edge.getChildRef());

          if (lNode != null)
          {
            if (lNode.mNumVisits > maxChildVisitCount)
            {
              maxChildVisitCount = lNode.mNumVisits;
            }
            if (lNode.mComplete)
            {
              anyComplete = true;
            }
          }
        }
      }
    }

    for (int lii = 0; lii < mNumChildren; lii++)
    {
      Object lChoice = mChildren[lii];

      if (lChoice == null)
      {
        continue;
      }

      TreeEdge edge = (lChoice instanceof TreeEdge ? (TreeEdge)lChoice : null);
      if (edge == null || edge.getChildRef() == NULL_REF)
      {
        if (!lRecursiveCall && !xiFirstDecision)
        {
          ForwardDeadReckonLegalMoveInfo partialMove;

          if (edge == null)
          {
            partialMove = (ForwardDeadReckonLegalMoveInfo)lChoice;
          }
          else
          {
            partialMove = edge.mPartialMove;
          }

          if (mComplete)
          {
            // We expect unexpanded children if the root is complete.
            LOGGER.info("Unexpanded child of complete root for move: " + partialMove.mMove);
          }
          else
          {
            // Otherwise, it's a bit surprising that we haven't managed to play out everything at least once.
            LOGGER.warn("Unexpanded child of root for move: " + partialMove.mMove);
          }
        }
        continue;
      }

      if (!edge.isSelectable())
      {
        //  Superseded by hyper-edges
        continue;
      }

      //  If this factor is irrelevant it doesn't really matter what we pick, but noop
      //  seems to often be good for hindering the opponent!
      if (mTree.mIsIrrelevantFactor)
      {
        //  Whatever we choose will be worth 0 to us
        bestScore = 0;
        bestRawScore = 0;

        LOGGER.info("Move " + edge.descriptiveName() +
                    " in irrelevant factor");
        if (edge.mPartialMove.mInputProposition == null)
        {
          //  Any move not connected to the propnet at all is certainly a noop!
          bestEdge = edge;
          break;
        }

        //  If the noop is used in propnet calculations (e.g. - base props that remain unchanged when a noop happens)
        //  then we cannot spot them as easily, so we work on the assumption that the common pattern will be lots of
        //  some move name (e.g. 'Move' x y w z) and a lone noop of a different name.  Heuristically therefore play
        //  the loner.  Note that this isn't 100% reliable, but at the end of the day this is an irrelevant factor and
        //  if we fail to noop in it in some games that's hardly the end of the world.  A counter example would be
        //  a checkers position where you have both 'move' and 'jump' available
        GdlConstant moveName = edge.mPartialMove.mMove.getContents().toSentence().getName();

        if (primaryMoveName == null)
        {
          primaryMoveName = moveName;
          firstPrimaryEdge = edge;
          bestEdge = edge;  //  bank a choice in case we never meet another choice condition
        }

        if (primaryMoveName.equals(moveName))
        {
          numPrimaryMoves++;
        }
        else
        {
          if (secondaryMoveName == null)
          {
            secondaryMoveName = moveName;
            firstSecondaryEdge = edge;
          }
          if (secondaryMoveName.equals(moveName))
          {
            numSecondaryMoves++;
          }
        }

        if (numPrimaryMoves > 1 && numSecondaryMoves == 1)
        {
          bestEdge = firstSecondaryEdge;
          break;
        }
        if (numPrimaryMoves == 1 && numSecondaryMoves > 1)
        {
          bestEdge = firstPrimaryEdge;
          break;
        }
      }
      else
      {
        TreeNode child = get(edge.getChildRef());

        //  Trimmed nodes may be encountered anywhere below the root's own child links
        //  and these should not accidentally be followed when tracing a move path
        if (child == null)
        {
          continue;
        }

        double selectionScore;
        double moveScore = (mTree.mGameCharacteristics.isSimultaneousMove ||
                            mTree.mGameCharacteristics.numRoles > 2 ||
                            anyComplete ||
                            MCTSTree.DISABLE_ONE_LEVEL_MINIMAX) ? child.getAverageScore(roleIndex) :
                                                                  child.scoreForMostLikelyResponse();

        assert(-EPSILON <= moveScore && 100 + EPSILON >= moveScore);
//        if (firstDecision && (edge.mPartialMove.toString().contains("5 6 6 5")))
//        {
//          LOGGER.info("Force-selecting " + edge.mPartialMove);
//          bestNode = child;
//          bestScore = 99;
//          bestMoveScore = bestNode.getAverageScore(0);
//          bestEdge = edge;
//          break;
//        }
//        if (firstDecision && (edge.mPartialMove.toString().contains("8 1 7 2") || edge.mPartialMove.toString().contains("8 1 7 2")))
//        {
//          LOGGER.info("Force-UNselecting " + edge.mPartialMove);
//          moveScore = 1;
//        }
        //	If we have complete nodes with equal scores choose the one with the highest variance
        if (child.mComplete)
        {
          if (moveScore < 0.1)
          {
            //  Prefer more distant losses to closer ones
            moveScore = (child.mCompletionDepth - mTree.mGameSearcher.getRootDepth()) - 500;
            assert(moveScore <= 0);
            //assert(moveScore >= -500);
          }

          //  If the root has no visits (can happen if a node was completed in expansion by a shallow greedy rollout)
          //  then its selection value is its move value if complete
          if (mNumVisits == 0)
          {
            selectionScore = moveScore;
          }
          else
          {
            //  A complete score is certain, but we're comparing within a set that has only
            //  has numVisits TOTAL visits so still down-weight by the same visit count the most
            //  selected child has.  This avoids a tendency to throw a marginal win away for a
            //  definite draw.  Especially in games with low signal to noise ratio (everything looks
            //  close to 50%) this can be important
            //  We add EPSILON to break ties with the most-selected (but incomplete) node in favour of
            //  the complete one.  If we don't do this rounding errors can lead to an indeterminate
            //  choice (between this and the most selected node)
            selectionScore = moveScore *
                (1 - 20 * Math.log(mNumVisits) /
                    (20 * Math.log(mNumVisits) + maxChildVisitCount)) + EPSILON;
          }
        }
        else
        {
          int numChildVisits = child.mNumVisits;

          //  Cope with the case where root expansion immediately found a complete node and never
          //  even explored the others (which should not be selected)
          if (numChildVisits == 0 || mNumVisits == 0)
          {
            selectionScore = -1000;
          }
          else
          {
            //  Subtly down-weight noops in 1-player games to discourage them.  Note that
            //  this has to be fairly subtle, and not impact asymptotic choices since it is possible
            //  for a puzzle to require noops for a solution!
            if (mTree.mGameCharacteristics.numRoles == 1)
            {
              if (edge.mPartialMove.mInputProposition == null)
              {
                numChildVisits /= 2;
              }
            }
            selectionScore = moveScore *
                (1 - 20 * Math.log(mNumVisits) /
                    (20 * Math.log(mNumVisits) + numChildVisits));

            //  Whether we're looking for a choice of node to concentrate local search on (firstDecision==true)
            //  of looking for our final choice to play (firstDecision==false) impacts how we weight
            //  children relative to one another
            if (xiFirstDecision)
            {
              //  If it's already been local-searched down-weight it to avoid flipping
              //  back and forth between the same few moves
              if (child.mLocalSearchStatus == LocalSearchStatus.LOCAL_SEARCH_NO_RESULT)
              {
                selectionScore *= 0.95;
              }
            }
            else
            {
              //  Also down-weight nodes that have not been subject to local-search
              //  This helps cope with cases where the MCTS convergence tips over
              //  to a new node near the end of turn processing where the previous candidate
              //  had been confirmed to have no local loss, but the new choice turns out
              //  to actually be a loss.  The slight loss of potentially going for the second best
              //  MCTS choice when things are very close is more than compensated for by the
              //  added safety
              if (child.mLocalSearchStatus == LocalSearchStatus.LOCAL_SEARCH_UNSEARCHED)
              {
                selectionScore *= 0.95;
              }
            }

            //  A local known search status strongly impacts the choice.  Basically this establishes a hierarchy
            //  to all intents and purposes that means local serach wins will easentially always be selected
            //  over anything but complete wins, and local serach losses will almost never be selected
            switch(child.mLocalSearchStatus)
            {
              case LOCAL_SEARCH_LOSS:
                selectionScore /= 10;
                break;
              case LOCAL_SEARCH_WIN:
                selectionScore = 100 - (100-selectionScore)/10;
                break;
                //$CASES-OMITTED$
              default:
                break;
            }
          }
        }
        if (!lRecursiveCall && !xiFirstDecision)
        {
          LOGGER.info("Move " + edge.descriptiveName() +
                      " scores " + FORMAT_2DP.format(moveScore) + " (selectionScore " +
                      FORMAT_2DP.format(selectionScore) + ", selection count " + child.mNumVisits +
                      " [edge " + edge.getNumChildVisits() + ", updates " + child.mNumUpdates + "]" +  ", ref " + child.mRef +
                      (mRAVEStats != null ? (", RAVE[" + mRAVEStats.mCounts[lii] + ", " + FORMAT_2DP.format(mRAVEStats.mScores[lii]) + "]") : "") +
                      (child.mComplete ? (", complete [" + ((child.mCompletionDepth - mTree.mRoot.mDepth) / mTree.mNumRoles) + "]") : "") +
                      (child.mLocalSearchStatus.HasResult() ? (", " + child.mLocalSearchStatus + " [" + ((child.mCompletionDepth - mTree.mRoot.mDepth) / mTree.mNumRoles) + "]") : "") +
                      ")");
        }

        if (child.mNumChildren != 0 && !child.mComplete && xiTraceResponses && !xiFirstDecision)
        {
          lResponsesTraced = child.traceFirstChoiceNode(lResponsesTraced, (selectionScore > bestScore));
        }

        if (edge.mPartialMove.mIsPseudoNoOp)
        {
          lResult.mPseudoNoopValue = moveScore;
          lResult.mPseudoMoveIsComplete = child.mComplete;
          continue;
        }
        //	Don't accept a complete score which no rollout has seen worse than, if there is
        //	any alternative
        if (bestNode != null && !bestNode.mComplete && child.mComplete &&
            moveScore < mTree.mLowestRolloutScoreSeen &&
            mTree.mLowestRolloutScoreSeen < 100)
        {
          continue;
        }
        if (bestNode == null ||
            selectionScore > bestScore ||
            (selectionScore == bestScore && child.mComplete && (child.mCompletionDepth < bestNode.mCompletionDepth || !bestNode.mComplete)) ||
            (bestNode.mComplete && !child.mComplete &&
            bestNode.getAverageScore(roleIndex) < mTree.mLowestRolloutScoreSeen && mTree.mLowestRolloutScoreSeen < 100))
        {
          bestNode = child;
          bestScore = selectionScore;
          bestMoveScore = bestNode.getAverageScore(0);
          bestEdge = edge;
        }
        if (child.getAverageScore(roleIndex) > bestRawScore ||
            (child.getAverageScore(roleIndex) == bestRawScore && child.mComplete && child.getAverageScore(roleIndex) > 0))
        {
          bestRawScore = child.getAverageScore(roleIndex);
          rawBestEdgeResult = edge;
        }
      }
    }

    if (!lRecursiveCall && !xiFirstDecision)
    {
      if (bestEdge == null && mTree.mFactor == null)
      {
        LOGGER.warn("No move found!");
      }
      if (rawBestEdgeResult != bestEdge)
      {
        LOGGER.info("1 level minimax result differed from best raw move: " + rawBestEdgeResult);
      }
    }

    // Trace the most likely path through the tree if searching from the root
    if (!xiFirstDecision)
    {
      if (!lRecursiveCall)
      {
        xbPathTrace = new StringBuffer("Most likely path: ");
      }
      assert(xbPathTrace != null);
      if (bestEdge != null)
      {
        xbPathTrace.append(bestEdge.descriptiveName());
        xbPathTrace.append(roleIndex == 0 ? ", " : " | ");
      }

      if ((bestNode != null) && (bestNode.mNumChildren != 0))
      {
        FactorMoveChoiceInfo lChildInfo = bestNode.getBestMove(false, xbPathTrace, false);
        lResult.mPathTrace = lChildInfo.mPathTrace;
      }
      else
      {
        xbPathTrace.setLength(xbPathTrace.length() - 2);
        String lPathTrace = xbPathTrace.toString();
        LOGGER.info(lPathTrace);
        lResult.mPathTrace = lPathTrace;
      }
    }

    if (bestEdge == null)
    {
      //  This can happen if the node has no expanded children
      assert(this != mTree.mRoot || mComplete || (mNumChildren == 1 && (mChildren[0] instanceof TreeEdge) && ((TreeEdge)mChildren[0]).mPartialMove.mIsPseudoNoOp)) : "Root incomplete but has no expanded children";

      //  If we're being asked for the first decision node we need to give a response even if it's
      //  essentially arbitrary from equal choices
      if (xiFirstDecision)
      {
        //  If nothing is expanded pick the first (arbitrarily)
        Object firstChoice = mChildren[0];
        lResult.mBestEdge = null;
        lResult.mBestMove = (firstChoice instanceof ForwardDeadReckonLegalMoveInfo) ? (ForwardDeadReckonLegalMoveInfo)firstChoice : ((TreeEdge)firstChoice).mPartialMove;
        //  Complete with no expanded children implies arbitrary child must match parent score
        lResult.mBestMoveValue = getAverageScore(0);
        lResult.mResultingState = null;
      }
      else
      {
        //  For a non firstDecision call (i.e. - actually retrieving best move to play)
        //  we need to ensure that we return null here so that in a factorized game this
        //  factor will never be picked
        lResult.mBestMove = null;
      }
    }
    else
    {
      ForwardDeadReckonLegalMoveInfo moveInfo = bestEdge.mPartialMove;

      lResult.mBestEdge = bestEdge;
      lResult.mBestMove = (moveInfo.mIsPseudoNoOp ? null : moveInfo);
      lResult.mResultingState = get(bestEdge.getChildRef()).mState;
      if (!moveInfo.mIsPseudoNoOp)
      {
        lResult.mBestMoveValue = bestMoveScore;
        lResult.mBestMoveIsComplete = get(bestEdge.getChildRef()).mComplete;
      }
    }

    return lResult;
  }

  /**
   * Perform a rollout.
   *
   * @param path             - the path to the selected node.
   * @param xiPipeline       - the pipeline from which RolloutRequests can be allocated.
   * @param forceSynchronous - whether this rollout *must* be performed synchronously.
   * @param xiSelectTime     - the elapsed time to select the node.
   * @param xiExpandTime     - the elapsed time to expand the node.
   *
   * @throws MoveDefinitionException
   * @throws TransitionDefinitionException
   * @throws GoalDefinitionException
   */
  public void rollOut(TreePath path,
                      Pipeline xiPipeline,
                      boolean forceSynchronous,
                      long xiSelectTime,
                      long xiExpandTime)
    throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    //  Rolling out from this node constitutes a visit, and the leaf node that we roll out
    //  from will not have had its visit count updated by select as it has not been selected through
    mNumVisits++;

    assert(!mFreed) : "Rollout node is a freed node";
    assert(path.isValid()) : "Rollout path isn't valid";

    if (mComplete)
    {
      // This node is already complete, so there's no need to perform another rollout.  Just back-propagate the known
      // score for this node.
      mTree.mNumTerminalRollouts++;

      // Take a copy of the scores because updateStats may modify these values during back-propagation.
      for (int lii = 0; lii < mTree.mNumRoles; lii++)
      {
        mTree.mNodeAverageScores[lii] = getAverageScore(lii);
        mTree.mNodeAverageSquaredScores[lii] = getAverageSquaredScore(lii);
      }

      long lBackPropTime = updateStats(mTree.mNodeAverageScores,
                                       mTree.mNodeAverageSquaredScores,
                                       path,
                                       1,
                                       null);
      mTree.mGameSearcher.recordIterationTimings(xiSelectTime, xiExpandTime, 0, 0, lBackPropTime);
      mTree.mPathPool.free(path, 0);

      return;
    }

    //assert(decidingRoleIndex == tree.numRoles - 1) : "Attempt to rollout from an incomplete-information node";

    assert(!mFreed) : "Rollout node is a freed node";
    assert(path.isValid()) : "Rollout path isn't valid";

    // Get a rollout request object.
    RolloutRequest lRequest;
    long lGetSlotTime = 0;
    if (ThreadControl.ROLLOUT_THREADS > 0 && !forceSynchronous)
    {
      // Get a request slot from the pipeline.
      if (!xiPipeline.canExpand())
      {
        // The pipeline is full.  We can't expand it until we've done some back-propagation.  Even though none was
        // available at the start of the expansion, we'll just have to wait.
        lGetSlotTime = mTree.mGameSearcher.processCompletedRollouts(true);

        //  Processing completions above could have resulted in a node on the rollout
        //  path from being freed (because it has been determined to be complete or an
        //  ancestor has).  In such cases abort the rollout.
        if (path.isFreed())
        {
          mTree.mPathPool.free(path, 0);
          return;
        }
      }
      lRequest = xiPipeline.getNextExpandSlot();
    }
    else
    {
      // Synchronous rollouts - use the single request object.
      lRequest = mTree.mNodeSynchronousRequest;
    }

    assert(!mFreed) : "Rollout node is a freed node";
    assert(path.isValid()) : "Rollout path isn't valid";

    lRequest.mSelectElapsedTime  = xiSelectTime;
    lRequest.mExpandElapsedTime  = xiExpandTime;
    lRequest.mGetSlotElapsedTime = lGetSlotTime;
    lRequest.mState.copy(mState);
    lRequest.mNodeRef = getRef();
    lRequest.mSampleSize = mTree.mGameCharacteristics.getRolloutSampleSize();
    lRequest.mPath = path;
    lRequest.mFactor = mTree.mFactor;
    lRequest.mRecordPlayoutTrace = mTree.mGameCharacteristics.isPseudoPuzzle || mTree.mGameSearcher.mUseRAVE;
    lRequest.mIsWin = false;
    lRequest.mTree = mTree;

    mTree.mNumNonTerminalRollouts += lRequest.mSampleSize;

    if (lRequest != mTree.mNodeSynchronousRequest)
    {
      // Queue the request for processing.
      lRequest.mEnqueueTime = System.nanoTime();
      xiPipeline.completedExpansion();
    }
    else
    {
      // Do the rollout and back-propagation synchronously (on this thread).
      assert(ThreadControl.ROLLOUT_THREADS == 0 || forceSynchronous);
      lRequest.process(mTree.mUnderlyingStateMachine, mTree.mOurRole, mTree.mRoleOrdering);
      long lRolloutTime = System.nanoTime() - lRequest.mRolloutStartTime;
      assert(!Double.isNaN(lRequest.mAverageScores[0]));

      long lBackPropTime = mTree.mGameSearcher.processCompletedRollout(lRequest);

      mTree.mGameSearcher.recordIterationTimings(xiSelectTime, xiExpandTime, 0, lRolloutTime, lBackPropTime);
      mTree.mPathPool.free(lRequest.mPath, 0);
      lRequest.mPath = null;
    }
  }

  /**
   * Update the tree with details from a rollout, starting with this node, then working up the path.
   *
   * WARNING: The caller is responsible for ensuring that the entire path is valid before calling this function.
   *
   * @param xiValues                  - The per-role rollout values.
   * @param xiSquaredValues           - The per-role squared values (for computing variance).
   * @param xiPath                    - The path taken through the tree for the rollout.
   * @param xiWeight                  - Weight to apply this update with
   * @param playedMoves               - Moves played below the current node, or null is RAVE not being used
   *
   * @return the time taken to do the update, in nanoseconds
   */
  public long updateStats(double[] xiValues,
                          double[] xiSquaredValues,
                          TreePath xiPath,
                          double  xiWeight,
                          OpenBitSet playedMoves)
  {
    long lStartTime = System.nanoTime();
    assert(checkFixedSum(xiValues));

    boolean lastNodeWasComplete = false;
    TreeNode lNextNode;
    for (TreeNode lNode = this; lNode != null; lNode = lNextNode)
    {
      TreePathElement lElement = xiPath.getCurrentElement();
      TreeEdge lChildEdge = (lElement == null ? null : lElement.getEdgeUnsafe());
      lNextNode = null;

      if (!mTree.mHeuristic.applyAsSimpleHeuristic() && lNode.mHeuristicWeight > 0 && !lastNodeWasComplete)
      {
        double applicableValue = (lNode.mHeuristicValue > 50 ? lNode.mHeuristicValue : 100 - lNode.mHeuristicValue);

        if (applicableValue > EPSILON)
        {
          for (int roleIndex = 0; roleIndex < mTree.mNumRoles; roleIndex++)
          {
            double heuristicAdjustedValue;

            if ((lNode.mHeuristicValue > 50) == (roleIndex == 0))
            {
              heuristicAdjustedValue = xiValues[roleIndex] + (100 - xiValues[roleIndex]) * (applicableValue - 50) / 50;
            }
            else
            {
              heuristicAdjustedValue = xiValues[roleIndex] - (xiValues[roleIndex]) * (applicableValue - 50) / 50;
            }

            xiValues[roleIndex] = heuristicAdjustedValue;
            xiSquaredValues[roleIndex] = heuristicAdjustedValue*heuristicAdjustedValue;
          }
        }
      }

      //  For a non-decisive losing complete node we know this path will not actually be chosen
      //  so reduce its weight significantly.  This helps a lot in games like breakthrough where
      //  all responses but one (to an enemy pawn advancing the 7th/2nd rank) lose, by preventing
      //  the necessary expansion of every child resulting in a very misleading value for the immediate
      //  parent after O(branching factor) visits, which can otherwise cause it to sometimes not be
      //  explored further
      boolean isAntiDecisiveCompletePropagation = false;
      if (!mTree.mHeuristic.applyAsSimpleHeuristic() &&
          lastNodeWasComplete &&
          xiValues[(lNode.mDecidingRoleIndex + 1) % mTree.mNumRoles] == 0)
      {
        isAntiDecisiveCompletePropagation = true;
      }

      // Across a turn end it is possible for queued paths to run into freed nodes due to trimming of the tree at the
      // root to advance the turn.  Rather than add locking and force clearing the rollout pipeline synchronously on
      // turn start it is more efficient to simply abort the update when the path leads to a no-longer extant region of
      // the tree.
      if (xiPath.hasMore())
      {
        lNextNode = xiPath.getNextNode();
        if (lNextNode == null)
        {
          return System.nanoTime() - lStartTime;
        }
      }

      // Do the stats update for the selected node.
      //assert(lNode.numUpdates <= lNode.numVisits);

      double[] lOverrides = (lElement == null ? null : lElement.getScoreOverrides());
      if (lOverrides != null)
      {
        xiValues = lOverrides;
        assert(checkFixedSum(xiValues));
        for (int lRoleIndex = 0; lRoleIndex < mTree.mNumRoles; lRoleIndex++)
        {
          xiSquaredValues[lRoleIndex] = xiValues[lRoleIndex]*xiValues[lRoleIndex];
        }
      }

      //  If we're propagating up through a complete node then the only possible valid score to
      //  propagate is that node's score
      if (lNode.mComplete)
      {
        for (int lRoleIndex = 0; lRoleIndex < mTree.mNumRoles; lRoleIndex++)
        {
          xiValues[lRoleIndex] = lNode.getAverageScore(lRoleIndex);
          xiSquaredValues[lRoleIndex] = lNode.getAverageSquaredScore(lRoleIndex);
        }
      }

      //  Choke off propagation that originated through an anti-decisive (losing) complete
      //  choice except for the first one through that parent
      if (isAntiDecisiveCompletePropagation && lNode.mNumUpdates > 0)
      {
        return System.nanoTime() - lStartTime;
      }

      if (!lNode.mComplete)
      {
        double applicationWeight = (isAntiDecisiveCompletePropagation ? xiWeight/10 : xiWeight);

        for (int lRoleIndex = 0; lRoleIndex < mTree.mNumRoles; lRoleIndex++)
        {
          assert(xiValues[lRoleIndex] < 100+EPSILON);
          if (lChildEdge != null)
          {
            TreeNode lChild = lNode.get(lChildEdge.getChildRef());
            int     adjustedChildVisits = lChild.mNumVisits;
            if (mTree.mHeuristic.applyAsSimpleHeuristic())
            {
              adjustedChildVisits += (int)lChild.mHeuristicWeight;
            }

            //  Take the min of the apparent edge selection and the total num visits in the child
            //  This is necessary because when we re-expand a node that was previously trimmed we
            //  leave the edge with its old selection count even though the child node will be
            //  reset.
            int lNumChildVisits = Math.min(lChildEdge.getNumChildVisits(), adjustedChildVisits);

            assert(lNumChildVisits > 0);
            //  Propagate a value that is a blend of this rollout value and the current score for the child node
            //  being propagated from, according to how much of that child's value was accrued through this path
            if (xiValues != lOverrides && lNumChildVisits > 0)
            {
              xiValues[lRoleIndex] = (xiValues[lRoleIndex] * lNumChildVisits + lChild.getAverageScore(lRoleIndex) *
                                      (adjustedChildVisits - lNumChildVisits)) /
                                      adjustedChildVisits;
              assert(xiValues[lRoleIndex] < 100+EPSILON);
            }

            assert (!lNode.mAllChildrenComplete || mTree.mGameCharacteristics.isSimultaneousMove || Math.abs(xiValues[lRoleIndex] - lChild.getAverageScore(lRoleIndex)) < EPSILON);
          }

          double numUpdatesIncludedHeuristicBias = lNode.mNumUpdates;
          if (mTree.mHeuristic.applyAsSimpleHeuristic())
          {
            numUpdatesIncludedHeuristicBias += lNode.mHeuristicWeight;
          }

          lNode.setAverageScore(lRoleIndex,
                                (lNode.getAverageScore(lRoleIndex) * numUpdatesIncludedHeuristicBias * SCORE_TEMPORAL_DECAY_RATE + xiValues[lRoleIndex]*applicationWeight) /
                                (numUpdatesIncludedHeuristicBias*SCORE_TEMPORAL_DECAY_RATE + applicationWeight));


          lNode.setAverageSquaredScore(lRoleIndex,
                                       (lNode.getAverageSquaredScore(lRoleIndex) *
                                           numUpdatesIncludedHeuristicBias + xiSquaredValues[lRoleIndex]*applicationWeight) /
                                       (numUpdatesIncludedHeuristicBias + applicationWeight));
        }

        lNode.mLastSelectionMade = -1;

        //validateScoreVector(averageScores);
        lNode.mNumUpdates += applicationWeight;

        //  RAVE stats update
        if (mTree.mGameSearcher.mUseRAVE && lNode.mNumChildren > 1 && playedMoves != null)
        {
          if (lNode.mRAVEStats == null)
          {
            // First update through this node, so allocate RAVE stats.
            lNode.attachRAVEStats();

            //  Pre-warm the stats from the nearest same-role-choice ancestor
            TreeNode ancestor = lNode.findSameRoleAncestor();

            if ( ancestor != null && ancestor.mRAVEStats != null )
            {
              int ancestorIndex = 0;

              for (int lii = 0; lii < lNode.mNumChildren; lii++)
              {
                if (lNode.mPrimaryChoiceMapping == null || lNode.mPrimaryChoiceMapping[lii] == lii)
                {
                  Object lChoice = lNode.mChildren[lii];
                  ForwardDeadReckonLegalMoveInfo moveInfo = (lChoice instanceof TreeEdge) ? ((TreeEdge)lChoice).mPartialMove : (ForwardDeadReckonLegalMoveInfo)lChoice;

                  //  Use of the partebt index rather than the loop variable directly optimizes the common case
                  //  where moves have the same ordering in the ancestor as in the child
                  for(int lij = 0; lij < ancestor.mNumChildren; lij++, ancestorIndex = (ancestorIndex + 1)%ancestor.mNumChildren)
                  {
                    Object lParentChoice = ancestor.mChildren[ancestorIndex];
                    ForwardDeadReckonLegalMoveInfo parentMoveInfo = (lParentChoice instanceof TreeEdge) ? ((TreeEdge)lParentChoice).mPartialMove : (ForwardDeadReckonLegalMoveInfo)lParentChoice;

                    if ( parentMoveInfo.mMasterIndex == moveInfo.mMasterIndex )
                    {
                      int canonicalIndex = (ancestor.mPrimaryChoiceMapping == null ? ancestorIndex : ancestor.mPrimaryChoiceMapping[ancestorIndex]);

                      //  Put a ceiling on the weight of the pre-warm value from the parent so that we converge using
                      //  local data quickly without the parewnt hint having too much momentum
                      lNode.mRAVEStats.mCounts[lii] = Math.min(ancestor.mRAVEStats.mCounts[canonicalIndex], 5);
                      lNode.mRAVEStats.mScores[lii] = ancestor.mRAVEStats.mScores[canonicalIndex];
                    }
                  }
                }
              }
            }
          }

          for (int lii = 0; lii < lNode.mNumChildren; lii++)
          {
            if (lNode.mPrimaryChoiceMapping == null || lNode.mPrimaryChoiceMapping[lii] == lii)
            {
              Object lChoice = lNode.mChildren[lii];
              ForwardDeadReckonLegalMoveInfo moveInfo = (lChoice instanceof TreeEdge) ? ((TreeEdge)lChoice).mPartialMove : (ForwardDeadReckonLegalMoveInfo)lChoice;
              if (playedMoves.get(moveInfo.mMasterIndex))
              {
                // This move was played so update the RAVE stats for it.
                lNode.mRAVEStats.mScores[lii] = (lNode.mRAVEStats.mScores[lii] * lNode.mRAVEStats.mCounts[lii] +
                                                (float)xiValues[(lNode.mDecidingRoleIndex + 1) % mTree.mNumRoles]) /
                                                                                    (lNode.mRAVEStats.mCounts[lii] + 1);
                lNode.mRAVEStats.mCounts[lii]++;
              }
            }
          }
        }
      }

      //assert(lNode.numUpdates <= lNode.numVisits);
      lastNodeWasComplete = lNode.mComplete;

      if (playedMoves != null && lChildEdge != null)
      {
        //  Add this edge's move into the played move record so that the ancestors will
        //  process it as well as what was processed here
        playedMoves.set(lChildEdge.mPartialMove.mMasterIndex);
      }

      assert(checkFixedSum(xiValues));
      assert(checkFixedSum());
    }

    return System.nanoTime() - lStartTime;
  }

  private TreeNode findSameRoleAncestor()
  {
    TreeNode ancestor = this;

    do
    {
      if ( ancestor.mParents.isEmpty() )
      {
        return null;
      }

      ancestor = ancestor.mParents.get(0);  //  Any parentage will do
    } while(ancestor.mDecidingRoleIndex != mDecidingRoleIndex);

    return ancestor;
  }

  /**
   * Get a node by reference, from the specified pool.
   *
   * @param xiPool    - the pool.
   * @param xiNodeRef - the node reference.
   *
   * @return the node, or null if it has been recycled.
   */
  public static TreeNode get(Pool<TreeNode> xiPool, long xiNodeRef)
  {
    assert(xiNodeRef != NULL_REF);

    TreeNode lNode = xiPool.get((int)xiNodeRef);
    if (lNode.mRef == xiNodeRef)
    {
      return lNode;
    }
    return null;
  }

  /**
   * Convenience method to get a TreeNode by reference from the same pool as this node.
   *
   * @param xiNodeRef - the node reference.
   *
   * @return the referenced node, or null if it has been recycled.
   */
  TreeNode get(long xiNodeRef)
  {
    return get(mTree.mNodePool, xiNodeRef);
  }
}