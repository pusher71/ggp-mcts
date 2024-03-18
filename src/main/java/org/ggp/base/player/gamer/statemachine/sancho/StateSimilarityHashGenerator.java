package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Random;

import org.apache.lucene.util.OpenBitSet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;

/**
 * @author steve
 *  Class acting as a hash-generator for a given game's states, such that
 *  the generated hashes are locality sensitive in that game's state space.
 *  In particular 'similar' states should hash to 'similar' hash codes (Hamming distance)
 *  The hash size is fixed, and currently set to 16 bits (arbitrary below 32).  This size
 *  is chosen because we want each hash bucket to typically contain a small number of states from those
 *  currently present in our search tree.  Since the tree is capped at circa 2 million nodes,
 *  which equates to (2 million/num roles) states, a 64 bit hash should typically have a few tens
 *  of nodes hashing to any given value.
 */
public class StateSimilarityHashGenerator
{
  static final public int hashSize = 16;
  final private int numPartitions = hashSize;
  final private int[] partitionMembership;
  final private int[] partitionPopulationBuffer;
  private final int partitionSampleBitIndex = 1;
  private final int partitionSampleBitMask = (1<<partitionSampleBitIndex);
  private final int controlMask;

  /**
   * Constructor
   * @param propNet propnet for the game in question
   */
  public StateSimilarityHashGenerator(ForwardDeadReckonPropNet propNet)
  {
    int numBasePropositions =  propNet.getBasePropositionsArray().length;

    partitionMembership = new int[numBasePropositions];
    partitionPopulationBuffer = new int[numPartitions];
    Random rand = new Random();
    int partitionIndex = 0;

    controlMask = rand.nextInt(1<<hashSize);

    for(int i = 0; i < numBasePropositions; i++)
    {
      partitionMembership[i] = -1;
    }

    //  Assign base propositions to random partitions such that each partition has the
    //  same number of base proposition members (up to rounding)
    for(int i = 0; i < numBasePropositions; i++)
    {
      int basePropIndex = rand.nextInt(numBasePropositions);

      while(partitionMembership[basePropIndex] != -1)
      {
        basePropIndex = (basePropIndex + 1)%numBasePropositions;
      }

      partitionMembership[basePropIndex] = partitionIndex;

      partitionIndex = (partitionIndex + 1)%numPartitions;
    }
  }

  /**
   * Generate a locality-sensitive hash (in state space) for a given
   * state
   * @param state
   * @return hash
   */
  public int getHash(ForwardDeadReckonInternalMachineState state)
  {
    OpenBitSet activeBaseProps = state.getContents();
    int result = 0;
    int firstBasePropIndex = state.firstBasePropIndex;
    int nextSetPropIndex = firstBasePropIndex-1;

    for(int i = 0; i < numPartitions; i++)
    {
      partitionPopulationBuffer[i] = 0;
    }

    while((nextSetPropIndex = activeBaseProps.nextSetBit(nextSetPropIndex+1)) != -1)
    {
      partitionPopulationBuffer[partitionMembership[nextSetPropIndex-firstBasePropIndex]]++;
    }

    for(int i = 0; i < numPartitions; i++)
    {
      if ( (partitionPopulationBuffer[i] & partitionSampleBitMask) != 0 )
      {
        result |= (1<<i);
      }
    }

    if ( state.isXState )
    {
      result ^= controlMask;
    }

    return result;
  }
}
