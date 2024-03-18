package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool.ObjectAllocator;

/**
 * Path through an MCTS tree.
 */
public class TreePath
{
  /**
   * The maximum possible path length.  Game of "Connect 4 Larger" could run to 400.  We'll give a bit of room for
   * bigger games.
   */
  public static final int MAX_PATH_LEN = 512;

  /**
   * Utility class for allocating tree paths from a pool.
   */
  public static class TreePathAllocator implements ObjectAllocator<TreePath>
  {
    private final MCTSTree mAllocatorTree;

    /**
     * Create an allocator for nodes in the the specified MCTS tree.
     *
     * @param xiTree - the tree.
     */
    public TreePathAllocator(MCTSTree xiTree)
    {
      mAllocatorTree = xiTree;
    }

    @Override
    public TreePath newObject(int xiPoolIndex)
    {
      return new TreePath(mAllocatorTree);
    }

    @Override
    public void resetObject(TreePath xiPath, boolean xiFree)
    {
      xiPath.reset(mAllocatorTree);
    }

    @Override
    public boolean shouldReset(TreePath xiPath)
    {
      assert(false) : "Shouldn't call shouldReset(TreePath)";
      return false;
    }
  }

  /**
   * Individual element in a tree path
   */
  class TreePathElement
  {
    // The parent node, the edge leading from it and the child.  It is only valid to access the edge if both node
    // references are still valid and equal to the references stored in the edge.
    private long     mParentRef;
    private TreeEdge mEdge;
    private long     mChildRef;

    // Score overrides to use above this point in the path.
    private double[] scoreOverrides;

    /**
     * Create a TreePathElement.  For use in pre-allocation.
     */
    public TreePathElement()
    {
      scoreOverrides = null; // !! ARR Pre-allocate this too.
      reset();
    }

    /**
     * Set up the TreePathElement.
     *
     * @param xiParent - the parent, used to check child validity.
     * @param xiEdge   - the edge to encapsulate.
     */
    public void set(TreeNode xiParent, TreeEdge xiEdge)
    {
      mParentRef = xiParent.getRef();
      mEdge      = xiEdge;
      mChildRef  = mEdge.getChildRef();

      assert(mParentRef != mChildRef) : "Parent and child mustn't be the same";
      assert(mParentRef == xiEdge.mParentRef) : "Edge must come from the parent";
      assert(getNode(mChildRef) != null) : "Can't add invalid node ref to path";
      assert(xiEdge.getNumChildVisits() <= getNode(mChildRef).mNumVisits) : "Edge has more visits than child";
    }

    /**
     * Set override scores that are to be fed upward from this element during update propagation, rather than those
     * flowing from lower in the path.
     *
     * @param overridingNode - the node containing the scores to use.
     */
    public void setScoreOverrides(TreeNode overridingNode)
    {
      scoreOverrides = new double[mTree.mNumRoles]; // !! ARR Just store a node ref and get the scores on demand, but be
                                                   // !! ARR careful because the array returned by getScoreOverrides()
                                                   // !! ARR is subsequently modified.

      for (int i = 0; i < mTree.mNumRoles; i++)
      {
        scoreOverrides[i] = overridingNode.getAverageScore(i);
      }
    }

    /**
     * Retrieve override score vector (if any).
     *
     * @return overrides, or null if there are none.
     */
    public double[] getScoreOverrides()
    {
      return scoreOverrides;
    }

    /**
     * Retrieve the node this path element leads to (downwards).
     *
     * @return the child node, or null if it has been freed.
     */
    public TreeNode getChildNode()
    {
      // !! ARR We only ever call this function in cases where we already know the child reference is valid.  Better to
      // !! ARR store the child (as well as it's ref) at creation time and then just return directly.  Should also
      // !! ARR rename this method to getChildNodeUnsafe at that point, to indicate that the caller is responsible for
      // !! ARR knowing that the child reference is still valid.
      return getNode(mChildRef);
    }

    /**
     * Retrieve the node this path element leads from (upwards).
     *
     * @return the parent node, or null if it has been freed.
     */
    public TreeNode getParentNode()
    {
      return getNode(mParentRef);
    }

    /**
     * @return the edge this element encapsulates, or null if the edge has become invalid (because the parent and/or the
     *         child has been freed).
     */
    public TreeEdge getEdge()
    {
      // Check that the edge is still valid before returning it.
      if ((getNode(mParentRef) == null) ||
          (getNode(mChildRef) == null)  ||
          (mEdge.getChildRef() != mChildRef) ||
          (mEdge.mParentRef != mParentRef))
      {
        return null;
      }
      return mEdge;
    }

    /**
     * WARNING: It is the caller's responsibility to ensure that this edge is still valid.  Typically the caller already
     *          knows that it is valid because it has just validated the entire path (with {@link TreePath#isFreed()}).
     *
     * @return the edge this element encapsulates.
     */
    public TreeEdge getEdgeUnsafe()
    {
      //assert(getEdge() != null);
      return mEdge;
    }

    private TreeNode getNode(long xiNodeRef)
    {
      return TreeNode.get(mTree.mNodePool, xiNodeRef);
    }

    /**
     * Reset this tree path element, ready for re-use.
     */
    public void reset()
    {
      mParentRef     = TreeNode.NULL_REF;
      mEdge          = null;
      mChildRef      = TreeNode.NULL_REF;
      scoreOverrides = null; // !! ARR Don't do this.
    }
  }

  /**
   * The tree through which this is a path.
   */
  MCTSTree mTree;

  // The elements that make up the path.  There are very few paths allocated (<50) and they're always recycled, so it's
  // simpler just to ensure this array is big enough for the longest possible path that we'll ever see in any game.
  private final TreePathElement[] mElements    = new TreePathElement[MAX_PATH_LEN];
  private int                     mNumElements = 0;

  // A cursor to an item in the path.  Used for iteration.
  private int mCursor = 0;

  /**
   * Construct a new selection path.
   *
   * @param xiTree - tree the path will be within.
   */
  public TreePath(MCTSTree xiTree)
  {
    reset(xiTree);
    for (int lii = 0; lii < MAX_PATH_LEN; lii++)
    {
      mElements[lii] = new TreePathElement();
    }
  }

  /**
   * Add a new element to the path (building up from root first downwards).
   *
   * @param xiParent - the parent node to add.
   * @param xiEdge   - the selected edge from the parent node.
   *
   * @return the added element.
   */
  public TreePathElement push(TreeNode xiParent, TreeEdge xiEdge)
  {
    TreePathElement lElement = mElements[mNumElements++];
    lElement.set(xiParent, xiEdge);
    mCursor++;
    return lElement;
  }

  /**
   * Pop the leaf element off the path
   */
  public void pop()
  {
    mCursor = --mNumElements;
  }

  /**
   * Reset enumeration cursor to the leaf of the path
   */
  public void resetCursor()
  {
    mCursor = mNumElements;
  }

  /**
   * Are there more elements to be enumerated
   * @return whether there are more
   */
  public boolean hasMore()
  {
    return mCursor > 0;
  }

  /**
   * Advance to the next element during enumeration.  Note that this enumerates from deepest point first up to the root.
   *
   * @return the next node in the path, or null if the path has become invalid at this point (because the node has been
   *         recycled).
   */
  public TreeNode getNextNode()
  {
    mCursor--;
    if (hasMore())
    {
      TreePathElement element = mElements[mCursor - 1];
      TreeNode node = element.getChildNode();
      return node;
    }
    return mTree.mRoot;
  }

  /**
   * Trim elements from the tail of the path until at most one
   * leaf element is complete
   */
  public void trimToCompleteLeaf()
  {
    while(mNumElements > 1)
    {
      TreePathElement element = mElements[mNumElements - 2];
      TreeNode node = element.getChildNode();

      if ( node == null || node.mComplete )
      {
        mNumElements--;
      }
      else
      {
        break;
      }
    }

    resetCursor();
  }

  /**
   * @return the tail path element.
   */
  public TreePathElement getTailElement()
  {
    if (mNumElements == 0)
    {
      return null;
    }
    return mElements[mNumElements-1];
  }

  /**
   * @return the current path element during enumeration.
   */
  public TreePathElement getCurrentElement()
  {
    if (mCursor == mNumElements)
    {
      return null;
    }
    return mElements[mCursor];
  }

  /**
   * @return whether any node on the path has been freed.
   */
  public boolean isFreed()
  {
    for (int lii = 0; lii < mNumElements; lii++)
    {
      TreeEdge edge = mElements[lii].getEdge();
      if (edge == null)
      {
        return true;
      }
    }

    return false;
  }

  /**
   * Reset the tree path for re-use.
   *
   * @param xiTree - the tree that it will be used in.
   */
  public void reset(MCTSTree xiTree)
  {
    mTree = xiTree;
    for (int lii = 0; lii < mNumElements; lii++)
    {
      mElements[lii].reset();
    }
    mNumElements = 0;
    mCursor = 0;
  }

  /**
   * @return whether the path through the tree is still valid.  For use in asserts only.  Production code should call
   * isFreed().
   */
  public boolean isValid()
  {
    while(hasMore())
    {
      // Get the edge in the path.  If this returns null, the path has become invalid at this point.
      getNextNode();
      assert(getCurrentElement() != null);

      TreeEdge lEdge = getCurrentElement().getEdge();
      if (lEdge == null)
      {
        return false;
      }

      // The edge can't have been visited more often than its child.  (The converse isn't true because children can
      // have multiple parents.)
      assert(getCurrentElement().getChildNode() != null) : "Child is null even after edge validated";
      assert(lEdge.getNumChildVisits() <= getCurrentElement().getChildNode().mNumVisits) :
        "Edge " + lEdge + " has been visited " + lEdge.getNumChildVisits() + " times, but the child (" +
        getCurrentElement().getChildNode() + ") only has " + getCurrentElement().getChildNode().mNumVisits + " visits!";
    }
    resetCursor();
    return true;
  }

  @Override
  public String toString()
  {
    String result = "";

    for (int lii = 0; lii < mNumElements; lii++)
    {
      TreeEdge edge = mElements[lii].getEdge();
      if (edge != null)
      {
        if ( !result.isEmpty())
        {
          result += ", ";
        }
        result += edge.mPartialMove;
      }
      else
      {
        result += "XXX";
        break;
      }
    }

    return result;
  }
}