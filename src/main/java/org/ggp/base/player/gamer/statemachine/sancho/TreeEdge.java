package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool.ObjectAllocator;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

/**
 * An edge in an MCTS Tree.
 */
public class TreeEdge
{
  /**
   * Utility class for allocating tree edges from a pool.
   */
  public static class TreeEdgeAllocator implements ObjectAllocator<TreeEdge>
  {
    @Override
    public TreeEdge newObject(int xiPoolIndex)
    {
      return new TreeEdge();
    }

    @Override
    public void resetObject(TreeEdge xiEdge, boolean xiFree)
    {
      xiEdge.reset();
    }

    @Override
    public boolean shouldReset(TreeEdge xiEdge)
    {
      assert(false) : "Shouldn't call shouldReset(TreeEdge)";
      return false;
    }
  }

  /**
   * The parent node from which this edge leads.
   *
   * This is final for the logical lifetime of the edge (i.e. until it is recycled).  It is never TreeNode.NULL_REF.
   */
  long                           mParentRef                 = TreeNode.NULL_REF;

  /**
   * The move, performed in the parent, which this edge represents.
   *
   * This is final for the logical lifetime of the edge (i.e. until it is recycled).
   */
  ForwardDeadReckonLegalMoveInfo mPartialMove;

  /**
   * The child reached by performing the move in the parent.
   *
   * This may be TreeNode.NULL_REF if the child node is yet to be created.
   */
  private long                   mChildRef                  = TreeNode.NULL_REF;

  /**
   * Top bit of the numChildVisits is used to encode an edge re-expansion event
   */
  private static final int HAS_BEEN_TRIMMED_MASK = 0x80000000;
  private static final int NUM_VISITS_MASK = ~HAS_BEEN_TRIMMED_MASK;
  private int                   numChildVisits       = 0;

  double                        explorationAmplifier = 0;
  /**
   * Edge flags hold some binary properties for the edge, which are accessed through
   * public get/setters
   */
  private static final short  EDGE_FLAG_HAS_HEURISTIC_DEVIATION = 1;
  private static final short  EDGE_FLAG_IS_UNSELECTABLE         = 2;
  private static final short  EDGE_FLAG_IS_HYPEREDGE            = 4;
  private short                 mFlags               = 0;
  TreeEdge                      hyperSuccessor       = null;
  long                          nextHyperChild       = TreeNode.NULL_REF;

  /**
   * Create a tree edge.
   *
   * Immediately after creation, the caller is expected to call setParent.  (The parameters aren't passed here purely to
   * make pooled creation simpler.)
   */
  TreeEdge()
  {
  }

  /**
   * @return child ref of this edge
   */
  public long getChildRef()
  {
//    if ( hyperSuccessor != null )
//    {
//      return hyperSuccessor.getChildRef();
//    }

    return mChildRef;
  }

  /**
   * Set the child ref of this edge
   * @param ref
   */
  public void setChildRef(long ref)
  {
    assert(ThreadControl.checkTreeOwnership());

    mChildRef = ref;
  }

  /**
   * @return whether this edge traverses a heuristic  deviation
   */
  public boolean hasHeuristicDeviation()
  {
    return (mFlags & EDGE_FLAG_HAS_HEURISTIC_DEVIATION) != 0;
  }

  /**
   * Set whether this edge traverses a heuristic deviation
   * @param hasHeuristicDeviation
   */
  public void setHasHeuristicDeviation(boolean hasHeuristicDeviation)
  {
    assert(ThreadControl.checkTreeOwnership());

    if ( hasHeuristicDeviation )
    {
      mFlags |= EDGE_FLAG_HAS_HEURISTIC_DEVIATION;
    }
    else
    {
      mFlags &= ~EDGE_FLAG_HAS_HEURISTIC_DEVIATION;
    }
  }

  /**
   * @return whether this edge is a hyper-edge
   */
  public boolean isHyperEdge()
  {
    return (mFlags & EDGE_FLAG_IS_HYPEREDGE) != 0;
  }

  /**
   * Set whether this edge is a hyper-edge
   * @param isHyperEdge
   */
  public void setIsHyperEdge(boolean isHyperEdge)
  {
    assert(ThreadControl.checkTreeOwnership());

    if ( isHyperEdge )
    {
      mFlags |= EDGE_FLAG_IS_HYPEREDGE;
    }
    else
    {
      mFlags &= ~EDGE_FLAG_IS_HYPEREDGE;
    }
  }

  /**
   * @return whether this edge traverses a heuristic  deviation
   */
  public boolean isSelectable()
  {
    return (mFlags & EDGE_FLAG_IS_UNSELECTABLE) == 0;
  }

  /**
   * Set whether this edge traverses a heuristic deviation
   * @param hasHeuristicDeviation
   */
  public void setIsSelectable(boolean isSelectable)
  {
    assert(ThreadControl.checkTreeOwnership());

    if ( isSelectable )
    {
      mFlags &= ~EDGE_FLAG_IS_UNSELECTABLE;
    }
    else
    {
      mFlags |= EDGE_FLAG_IS_UNSELECTABLE;
    }
  }

  /**
   * @return True if the hyper-path passes through a stale node
   */
  public boolean hyperLinkageStale()
  {
    boolean linkageStale = false;
    long    expectedStepParentRef = nextHyperChild;

    for(TreeEdge nextEdge = hyperSuccessor; nextEdge != null; nextEdge = nextEdge.hyperSuccessor)
    {
      //  Stale linkage will manifest as a different childRef
      //  somewhere along the chain, which can only arise via re-expansion
      //  or unequal hyperChild <->next step parent which can arise if an intermediary
      //  edge is freed and later re-used
      if ( expectedStepParentRef != nextEdge.mParentRef )
      {
        linkageStale = true;
        break;
      }

      expectedStepParentRef = nextEdge.nextHyperChild;

      if ( getChildRef() != nextEdge.getChildRef() )
      {
        linkageStale = true;
        break;
      }
    }

    return linkageStale;
  }

  /**
   * @return number of times this edge has been selected through
   */
  public int getNumChildVisits()
  {
    return (numChildVisits & NUM_VISITS_MASK);
  }

  /**
   * @return whether this edge has been re-expanded after trimming
   */
  public boolean getHasBeenTrimmed()
  {
    return (numChildVisits & HAS_BEEN_TRIMMED_MASK) != 0;
  }

  /**
   * Increment the number of times this edge has been selected through
   */
  public void incrementNumVisits()
  {
    assert(ThreadControl.checkTreeOwnership());

    numChildVisits++;
  }

  /**
   * Set the number of times this edge has been selected through
   */
  public void setNumVisits(int count)
  {
    assert(ThreadControl.checkTreeOwnership());

    numChildVisits = (numChildVisits & HAS_BEEN_TRIMMED_MASK) | count;
  }

  /**
   * Note that this edge has been trimmed
   */
  public void setHasBeenTrimmed()
  {
    assert(ThreadControl.checkTreeOwnership());

    numChildVisits |= HAS_BEEN_TRIMMED_MASK;
  }

  /**
   * Set the edge's parent (and the move from the parent).
   *
   * @param xiParent      - the parent.
   * @param xiPartialMove - the move.
   */
  public void setParent(TreeNode xiParent, ForwardDeadReckonLegalMoveInfo xiPartialMove)
  {
    assert(ThreadControl.checkTreeOwnership());

    mParentRef = xiParent.getRef();
    mPartialMove = xiPartialMove;

    assert(mParentRef != TreeNode.NULL_REF);
    assert(mChildRef != mParentRef);
    assert(mChildRef == TreeNode.NULL_REF ||
           TreeNode.get(xiParent.mTree.mNodePool, mChildRef).mTree == xiParent.mTree);
    assert(!xiPartialMove.mIsPseudoNoOp || xiParent == xiParent.mTree.mRoot || xiParent.mNumChildren == 1);
  }

  /**
   * Set the edge's child.
   *
   * @param xiChild - the child.
   */
  public void setChild(TreeNode xiChild)
  {
    assert(ThreadControl.checkTreeOwnership());

    mChildRef = xiChild.getRef();

    assert(mChildRef != TreeNode.NULL_REF);
    assert(mChildRef != mParentRef);
    assert(mParentRef == TreeNode.NULL_REF ||
           TreeNode.get(xiChild.mTree.mNodePool, mParentRef).mTree == xiChild.mTree);
  }

  /**
   * @return a human-readable string describing this edge.
   */
  public String descriptiveName()
  {
    String result;

    if ( mPartialMove.mIsPseudoNoOp )
    {
      result = "<Pseudo no-op>";
    }
    else
    {
      result = mPartialMove.mMove.toString();
    }

    if ( isHyperEdge() )
    {
      result += " (hyper)";
    }

    return result;
  }

  /**
   * Reset an edge in preparation for re-use.
   */
  public void reset()
  {
    assert(ThreadControl.checkTreeOwnership());

    mParentRef = TreeNode.NULL_REF;
    mChildRef = TreeNode.NULL_REF;
    numChildVisits = 0;
    mPartialMove = null;
    explorationAmplifier = 0;
    mFlags = 0;
    hyperSuccessor = null;
    nextHyperChild = TreeNode.NULL_REF;
  }
}