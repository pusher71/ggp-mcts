package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;

public class StateSimilarityMap
{
  private class StateSimilarityBucket
  {
    private final int capacity = 4;

    public final long[] refs = new long[capacity];
    public int  size = 0;

    public void addNode(long xiNodeRef)
    {
      // Check if this node is already present.  If not, no need to store.
      for (int i = 0; i < size; i++)
      {
        if (xiNodeRef == refs[i])
        {
          //  Already present
          return;
        }
      }

      if (size < capacity)
      {
        // We still have space available.  Just store the reference.
        refs[size++] = xiNodeRef;
      }
      else
      {
        int evictee = -1;

        TreeNode lNodeToAdd = getNode(xiNodeRef);
        assert(lNodeToAdd != null);

        double highestEvictionMeasure = -Math.log(lNodeToAdd.mNumVisits + 1);

        for (int i = 0; i < capacity; i++)
        {
          TreeNode lNode = getNode(refs[i]);
          if (lNode == null)
          {
            //  Effectively a free slot - no loss to evict it
            evictee = i;
            break;
          }

          double evictionMeasure = -Math.log(lNode.mNumVisits + 1);

          if ( evictionMeasure > highestEvictionMeasure )
          {
            evictionMeasure = highestEvictionMeasure;
            evictee = i;
          }
        }

        //  If the cache contained something less useful than the new entry replace it
        if ( evictee != -1 )
        {
          refs[evictee] = xiNodeRef;
        }
      }
    }
  }

  private final StateSimilarityBucket[] buckets;
  private final StateSimilarityHashGenerator hashGenerator;
  private final int maxMovesConsidered = 64;
  private final ForwardDeadReckonLegalMoveInfo[] moveBuffer = new ForwardDeadReckonLegalMoveInfo[maxMovesConsidered];
  private final double[] moveValueBuffer = new double[maxMovesConsidered];
  private final double[] moveWeightBuffer = new double[maxMovesConsidered];
  private final double[] topValues = new double[maxMovesConsidered];
  private final double[] topWeights = new double[maxMovesConsidered];
  private final Pool<TreeNode> mNodePool;
  private int numMovesBuffered;

  public StateSimilarityMap(ForwardDeadReckonPropNet propNet, Pool<TreeNode> xiNodePool)
  {
    hashGenerator = new StateSimilarityHashGenerator(propNet);
    buckets = new StateSimilarityBucket[1<<StateSimilarityHashGenerator.hashSize];
    mNodePool = xiNodePool;
  }

  public void add(TreeNode xiNode)
  {
    int hash = hashGenerator.getHash(xiNode.mState);

    if (buckets[hash] == null)
    {
      buckets[hash] = new StateSimilarityBucket();
    }
    buckets[hash].addNode(xiNode.getRef());
  }

  public int getScoreEstimate(ForwardDeadReckonInternalMachineState state, double[] result)
  {
    for(int i = 0; i < result.length; i++)
    {
      result[i] = 0;
    }

    int hash = hashGenerator.getHash(state);

    StateSimilarityBucket bucket = buckets[hash];
    if ( bucket != null )
    {
      double totalWeight = 0;

      for(int i = 0; i < bucket.size; i++)
      {
        TreeNode lNode = getNode(bucket.refs[i]);

        if (lNode != null && lNode.mNumVisits > 0 && state != lNode.mState)
        {
          double distanceWeight = (1 - state.distance(lNode.mState));
          double weight = distanceWeight*distanceWeight*Math.log(lNode.mNumVisits+1);

          for(int j = 0; j < result.length; j++)
          {
            result[j] += lNode.getAverageScore(j)*weight;
            assert(!Double.isNaN(result[j]));
          }

          totalWeight += weight;
        }
      }

      if ( totalWeight > 0 )
      {
        for(int i = 0; i < result.length; i++)
        {
          result[i] /= totalWeight;
          assert(!Double.isNaN(result[i]));
        }
      }
      return (int)(totalWeight);
    }

    return 0;
  }

  private TreeNode getJointMoveParent(TreeNode moveRoot, ForwardDeadReckonLegalMoveInfo[] partialJointMove)
  {
    int index = 0;
    TreeNode result = null;

    if ( partialJointMove[partialJointMove.length-1] != null )
    {
      return moveRoot;
    }

    while(index < partialJointMove.length && partialJointMove[index] != null)
    {
      if (moveRoot.mNumChildren == 0)
      {
        return null;
      }

      boolean childFound = false;
      for (int lii = 0; lii < moveRoot.mNumChildren; lii++)
      {
        Object child = moveRoot.mChildren[lii];
        ForwardDeadReckonLegalMoveInfo targetPartialMove = partialJointMove[index];
        TreeEdge childEdge = (child instanceof TreeEdge ? (TreeEdge)child : null);
        if ( child == targetPartialMove || (childEdge != null && childEdge.mPartialMove == targetPartialMove))
        {
          childFound = true;

          if (childEdge != null &&
              childEdge.getChildRef() != TreeNode.NULL_REF &&
              getNode(childEdge.getChildRef()) != null &&
              getNode(childEdge.getChildRef()).mNumChildren != 0)
          {
            result = getNode(childEdge.getChildRef());
            moveRoot = result;
            index++;
            break;
          }

          return null;
        }
      }

      if ( !childFound )
      {
        return null;
      }
    }

    return result;
  }

  private int getMoveSlot(ForwardDeadReckonLegalMoveInfo move)
  {
    for(int i = 0; i < numMovesBuffered; i++)
    {
      if ( moveBuffer[i] == move )
      {
        return i;
      }
    }

    if ( numMovesBuffered < maxMovesConsidered )
    {
      numMovesBuffered++;
    }

    moveBuffer[numMovesBuffered-1] = move;
    moveWeightBuffer[numMovesBuffered-1] = 0;
    moveValueBuffer[numMovesBuffered-1] = 0;

    return numMovesBuffered-1;
  }

  public int getTopMoves(ForwardDeadReckonInternalMachineState state, ForwardDeadReckonLegalMoveInfo[] partialJointMove, ForwardDeadReckonLegalMoveInfo[] result)
  {
    int hash = hashGenerator.getHash(state);

    numMovesBuffered = 0;

    int hammingCloseHash = hash;

    for(int nearbyHashIndex = 0; nearbyHashIndex <= StateSimilarityHashGenerator.hashSize; nearbyHashIndex++)
    {
      StateSimilarityBucket bucket = buckets[hammingCloseHash];
      if ( bucket != null )
      {
        for(int i = 0; i < bucket.size; i++)
        {
          TreeNode lNode = getNode(bucket.refs[i]);

          if (lNode != null && lNode.mNumVisits > 0 && state != lNode.mState)
          {
            double distanceWeight = (1 - state.distance(lNode.mState));
            double weight = distanceWeight*distanceWeight*Math.log10(lNode.mNumVisits + 1);

            TreeNode node = getJointMoveParent(lNode, partialJointMove);
            if (node != null && node.mNumChildren != 0)
            {
              for (int lii = 0; lii < node.mNumChildren; lii++)
              {
                Object child = node.mChildren[lii];
                TreeEdge childEdge = (child instanceof TreeEdge ? (TreeEdge)child : null);
                if ( childEdge != null &&
                     childEdge.getChildRef() != TreeNode.NULL_REF &&
                     getNode(childEdge.getChildRef()) != null &&
                     getNode(childEdge.getChildRef()).mNumVisits > 0)
                {
                  TreeNode lChild = getNode(childEdge.getChildRef());
                  ForwardDeadReckonLegalMoveInfo move = childEdge.mPartialMove;
                  int moveSlotIndex = getMoveSlot(move);

                  double moveVal = weight*(lChild.getAverageScore(lChild.mDecidingRoleIndex));

                  moveValueBuffer[moveSlotIndex] = (moveValueBuffer[moveSlotIndex]*moveWeightBuffer[moveSlotIndex] + moveVal)/(moveWeightBuffer[moveSlotIndex] + weight);
                  moveWeightBuffer[moveSlotIndex] += weight;
                }
              }
            }
          }
        }

        //  We look at all hashes within a Hamming distance of 1 from the original
        hammingCloseHash = hash ^ (1<<nearbyHashIndex);
      }
    }

    int numTopMoves = 0;
    for(int i = 0; i < numMovesBuffered; i++)
    {
      int index = numTopMoves - 1;

      while( index >= 0 && moveValueBuffer[i] > topValues[index] )
      {
        index--;
      }

      if ( ++index < result.length )
      {
        for(int j = numTopMoves-1; j > index; j--)
        {
          topValues[j] = topValues[j-1];
          topWeights[j] = topWeights[j-1];

          result[j] = result[j-1];
        }

        topValues[index] = moveValueBuffer[i];
        topWeights[index] = moveWeightBuffer[i];
        result[index] = moveBuffer[i];

        if ( index == numTopMoves )
        {
          numTopMoves = index+1;
        }
      }
    }

    int i;
    double totalWeight = 0;
    double bestScore = topValues[0];
    final double ratioToBestCutoff = 0.8;

    for(i = 0; i < numTopMoves; i++)
    {
      if ( topValues[i] < ratioToBestCutoff*bestScore )
      {
        numTopMoves = i;
        break;
      }
      totalWeight += topWeights[i];
    }
    while(i < result.length)
    {
      result[i++] = null;
    }

    if ( numTopMoves > 0 )
    {
      totalWeight /= numTopMoves;
    }

    return (int)(totalWeight);
  }

  private TreeNode getNode(long xiNodeRef)
  {
    return TreeNode.get(mNodePool, xiNodeRef);
  }
}
