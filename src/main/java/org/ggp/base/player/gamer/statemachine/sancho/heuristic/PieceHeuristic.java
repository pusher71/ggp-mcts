
package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.gdl.grammar.GdlFunction;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.stats.PearsonCorrelation;

/**
 * Heuristic which assumes that it's better to have more pieces than few.
 */
public class PieceHeuristic implements Heuristic
{
  /**
   * Logger instance
   */
  static final Logger LOGGER = LogManager.getLogger();

  private static final int                                               MIN_PIECE_PROP_ARITY      = 3;    // Assume board of at least 2 dimensions + piece type
  private static final int                                               MAX_PIECE_PROP_ARITY      = 4;    // For now (until we can do game-specific learning) restrict to exactly 2-d boards
  private static final int                                               MIN_PIECES_THRESHOLD      = 6;
  private static final double                                            MIN_HEURISTIC_CORRELATION = 0.06;
  //  Whether to use mobility values for pieces (else score-weighted aggregate presence)
  //  Empirically (in its current form at least) aggregate presence does not seem to work well
  private static final boolean                                           USE_MOBILITY_VALUES_FOR_PIECES = true;
  //  This is a bit of a hack (well, a lot of one really).  Empirically the metagaming tunes the piece values badly in
  //  the only known games with 2 piece types (checkers variants), so FOR NOW we disable differentiated piece
  //  type weights for <=2 piece types
  private static final int                                               MIN_NUM_PIECES            = 3;
  //  Similarly - we don't want to consider very large choices as possibly being piece types - for now we
  //  cap at what chess needs (6 types of pieces, possibly encoded separately for each of two roles)
  private static final int                                               MAX_NUM_PIECES            = 12;
  //  Don't consider board sizes less than 4 (X4 in the 2D case)
  private static final int                                               MIN_BOARD_DIMENSION       = 4;
  //  How much of a material difference do we consider enough to warrant sequence flagging
  private static final double                                            MIN_HEURISTIC_DIFF_FOR_SEQUENCE = 0.01;

  private static final double                                            EPSILON                   = 1e-6;

  private Map<PieceMaskSpecifier, HeuristicScoreInfo>                    propGroupScoreSets        = null;
  int                                                                    numRoles                  = 0;
  protected PieceMaskSpecifier[]                                           pieceSets                 = null;
  private int                                                            totalSimulatedTurns       = 0;
  //  The following track runtime usage state and are dependent on the current game-state
  protected int                                                            heuristicSampleWeight     = 10;
  protected double[]                                                       rootPieceValues           = null;
  private boolean                                                        mTuningComplete           = false;
  private boolean                                                        mUseSimpleHeuristic = false;
  private TreeNode                                                       mRootNode = null;

  private static class GdlFunctionInfo
  {
    private String           name;
    public List<Set<String>> paramRanges;

    public GdlFunctionInfo(String xiName, int arity)
    {
      name = xiName;
      paramRanges = new ArrayList<>();

      for (int i = 0; i < arity; i++)
      {
        paramRanges.add(new HashSet<String>());
      }
    }

    public String getName()
    {
      return name;
    }
  }

  private static class PotentialPiecePropSet
  {
    String fnName;
    int    potentialRoleArgIndex;

    public PotentialPiecePropSet(String name, int index)
    {
      fnName = name;
      potentialRoleArgIndex = index;
    }
  }

  private static class HeuristicScoreInfo
  {
    private PearsonCorrelation[] roleCorrelation;
    int                          lastValue        = -1;
    boolean[]                    hasRoleChanges;
    double                       noChangeTurnRate = 0;

    public HeuristicScoreInfo(int numRoles)
    {
      roleCorrelation = new PearsonCorrelation[numRoles];
      hasRoleChanges = new boolean[numRoles];
      for (int i = 0; i < numRoles; i++)
      {
        roleCorrelation[i] = new PearsonCorrelation();
      }
    }

    public void accrueSample(int value, int[] roleValues)
    {
      for (int i = 0; i < roleCorrelation.length; i++)
      {
        roleCorrelation[i].sample(value, roleValues[i]);
      }
    }

    public double[] getRoleCorrelations()
    {
      double[] result = new double[roleCorrelation.length];

      for (int i = 0; i < roleCorrelation.length; i++)
      {
        result[i] = roleCorrelation[i].getCorrelation();
      }

      return result;
    }
  }

  private class ConstituentPieceInfo
  {
    final String name;
    final public ForwardDeadReckonInternalMachineState mask;
    public double value;

    public ConstituentPieceInfo(String xiName, ForwardDeadReckonInternalMachineState pieceMask)
    {
      name = xiName;
      mask = pieceMask;
      value = 1;
    }

    @Override
    public String toString()
    {
      return name + ":" + value;
    }
  }

  private class PieceUsageStats
  {
    private int aggregatePresenceInGame;
    private int maxPresenceInGame;
    private final double[] scoreWeightedPresence;
    private final double[] scoreWeightedUsage;
    private double usageInGame;
    private int usageCountInGame;
    private int numSampleGames;
    private int numSampleMovesInGame;
    private ForwardDeadReckonInternalMachineState previousMaskedState;
    private final ConstituentPieceInfo pieceInfo;

    public PieceUsageStats(ConstituentPieceInfo xiPieceInfo, int numRoles)
    {
      pieceInfo = xiPieceInfo;
      scoreWeightedPresence = new double[numRoles];
      scoreWeightedUsage = new double[numRoles];

      for(int i = 0; i < numRoles; i++)
      {
        scoreWeightedPresence[i] = 0;
        scoreWeightedUsage[i] = 0;
      }

      numSampleGames = 0;
    }

    public void startSampleGame()
    {
      aggregatePresenceInGame = 0;
      maxPresenceInGame = 0;
      numSampleMovesInGame = 0;
      usageCountInGame = 0;
      usageInGame = 0;

      previousMaskedState = null;
    }

    public void sampleMove(ForwardDeadReckonInternalMachineState xiState)
    {
      ForwardDeadReckonInternalMachineState maskedState = new ForwardDeadReckonInternalMachineState(xiState);
      maskedState.intersect(pieceInfo.mask);

      int presence = pieceInfo.mask.intersectionSize(xiState);

      if ( presence > maxPresenceInGame )
      {
        maxPresenceInGame = presence;
      }

      aggregatePresenceInGame += presence;
      numSampleMovesInGame++;

      if ( previousMaskedState != null &&
           maskedState.size() > 0 )
      {
        if ( previousMaskedState.size() == maskedState.size() &&
             previousMaskedState.intersectionSize(maskedState) != maskedState.size() )
        {
          //  A piece of this type has been moved
          usageInGame += (double)1/maskedState.size();
        }
        usageCountInGame++;
      }

      previousMaskedState = maskedState;
    }

    public void endSampleGame(int[] result)
    {
      double weightedPresence;
      double weightedUsage;
      double averagePresenceInGame = aggregatePresenceInGame/numSampleMovesInGame;

      if ( maxPresenceInGame > 0 )
      {
        weightedPresence = averagePresenceInGame/maxPresenceInGame;
      }
      else
      {
        weightedPresence = 0;
      }

      if ( usageCountInGame > 0 )
      {
        weightedUsage = usageInGame/usageCountInGame;
      }
      else
      {
        weightedUsage = 0;
      }

      for(int i = 0; i < result.length; i++)
      {
        double normalizedScore = ((double)result[i])/100;
        scoreWeightedPresence[i] = (scoreWeightedPresence[i]*numSampleGames + weightedPresence*normalizedScore)/(numSampleGames+1);
        scoreWeightedUsage[i] = (scoreWeightedUsage[i]*numSampleGames + weightedUsage*normalizedScore)/(numSampleGames+1);

        assert(!Double.isNaN(scoreWeightedUsage[i]));
        assert(!Double.isInfinite(scoreWeightedUsage[i]));
      }
      numSampleGames++;
    }

    public double getPresenceWeighting(int roleIndex)
    {
      return scoreWeightedPresence[roleIndex];
    }

    public double getMobilityWeighting(int roleIndex)
    {
      return scoreWeightedUsage[roleIndex];
    }

    public String getPieceName()
    {
      return pieceInfo.name;
    }

    public ConstituentPieceInfo getPieceInfo()
    {
      return pieceInfo;
    }
  }

  protected class PieceMaskSpecifier
  {
    public final ForwardDeadReckonInternalMachineState  overallPieceMask;
    public ConstituentPieceInfo[]                       individualPieceTypes = null;
    public List<PieceUsageStats>                        individualPieceUsageStats = new ArrayList<>();
    private int                                         numPieceTypes = 1;

    public PieceMaskSpecifier(ForwardDeadReckonInternalMachineState pieceMask)
    {
      overallPieceMask = pieceMask;

      //  Can we see multiple different piece types?
      int propIndex = -1;

      Map<String, GdlFunctionInfo> basePropFns = new HashMap<>();

      while( (propIndex = overallPieceMask.getContents().nextSetBit(propIndex+1)) != -1 )
      {
        ForwardDeadReckonPropositionInfo prop = overallPieceMask.resolveIndex(propIndex);

        addSentenceToFnMap(prop.sentence, basePropFns);
      }

      Map<String, ForwardDeadReckonInternalMachineState> pieceTypeMasks = new HashMap<>();

      //  We only expect one piece fn name (e.g. 'cell' or similar which is the state of a board cell)
      //  If we have a more complex situation don't attempt to find piece types
      //  FUTURE - this may need further generalization
      if(basePropFns.size() == 1)
      {
        GdlFunctionInfo fnInfo = basePropFns.values().iterator().next();
        int smallestRangeSize = Integer.MAX_VALUE;
        int smallestRangeIndex = -1;
        int ignoreSize = -1;

        for (int i = 0; i < fnInfo.paramRanges.size(); i++)
        {
          int rangeSize = fnInfo.paramRanges.get(i).size();

          //  Special case - a range size of 1 could just be a role indicator
          //  so if the arity is sufficient that ignoring such a range still
          //  leaves sufficient arity for a piece prop ignore it
          if ( rangeSize > 1 || fnInfo.paramRanges.size() == MIN_PIECE_PROP_ARITY )
          {
            if ( rangeSize < smallestRangeSize )
            {
              smallestRangeSize = rangeSize;
            }
            //  Slight hack - we're trying to avoid accidentally trying to treat a board
            //  dimension as a piece type or owning role (which can be combined in some games)
            //  For this reason if the smallest arity is actually a board dimension (if there
            //  are more types of pieces than that, as in Los Alamos Chess) then we want to
            //  ignore the smallest arity and try the next size up.  However, only do this
            //  if the size we're ignoring is a plausible board dimension.  If the actual role/type
            //  indicator is not of unique range size we cannot reliably find it in this manner
            //  (which is why it's a slight hack and needs to be replaced with something more
            //  robust at some point).  Awkward cases arise if the number of piece types are
            //  equal to a board dimension (too bad!) or if the role and piece type are separated
            //  but have the same cardinality (English Draughts for example).  In the event of a tie
            //  we take the first one mentioned in the sentence (because it's a slightly more
            //  natural way to write the GDL to say 'black king' then 'king black') but obviously
            //  this is essentially a guess!
            else if ( rangeSize == smallestRangeSize && rangeSize >= MIN_BOARD_DIMENSION )
            {
              ignoreSize = smallestRangeSize;
            }
          }
        }

        smallestRangeSize = Integer.MAX_VALUE;
        int fallbackSingletonRangeIndex = -1;

        for(int rangeIndex = 0; rangeIndex < fnInfo.paramRanges.size(); rangeIndex++)
        {
          int rangeSize = fnInfo.paramRanges.get(rangeIndex).size();

          //  Special case - a range size of 1 could just be a role indicator
          //  so if the arity is sufficient that ignoring such a range still
          //  leaves sufficient arity for a piece prop ignore it
          //  MASSIVE HACK - if all the plausible ranges have the same size then this will be the ignore size
          //  and we cannot be sure (without much more analysis!) which is the plausible piece-type one.  For now we
          //  just take the last in such cases which tends to work because the GDL tends to specify coordinates then type
          //  However this is totally arbitrary and therefore a complete hack
          //  TODO - more advanced analysis needed of how props change to detect the difference between a corrdinate
          //  a a piece type qualifier more reliably
          if ( (rangeSize != ignoreSize || rangeIndex == fnInfo.paramRanges.size()-1) && rangeSize < smallestRangeSize )
          {
            if ( rangeSize > 1 || fnInfo.paramRanges.size() == MIN_PIECE_PROP_ARITY )
            {
              smallestRangeSize = rangeSize;
              smallestRangeIndex = rangeIndex;
            }
            else
            {
              fallbackSingletonRangeIndex = rangeIndex;
            }
          }
        }

        if ( smallestRangeIndex == -1 && fallbackSingletonRangeIndex != -1 )
        {
          smallestRangeSize = 1;
          smallestRangeIndex = fallbackSingletonRangeIndex;
        }

        if ( smallestRangeIndex == -1 )
        {
          //  Cound not identify a piece type term
          return;
        }

        //  Should validate that the different values of this arg actually
        //  plausibly correspond to pieces (by checking that moves which preserve
        //  the overall piece count predominantly change exactly 2 bits in the
        //  piece mask - only 'predominantly' because we don't want rare piece
        //  converting moves like pawns reaching the 8th rank in chess preventing
        //  recognition)
        //  TODO - write some code to do this validation

        //  Create masking info for each piece type
        while( (propIndex = overallPieceMask.getContents().nextSetBit(propIndex+1)) != -1 )
        {
          ForwardDeadReckonPropositionInfo prop = overallPieceMask.resolveIndex(propIndex);

          if (prop.sentence.arity() == 1 &&
              prop.sentence.getBody().get(0) instanceof GdlFunction)
          {
            GdlFunction propFn = (GdlFunction)prop.sentence.getBody().get(0);

            String pieceType = propFn.getBody().get(smallestRangeIndex).toString();
            ForwardDeadReckonInternalMachineState pieceTypeMask;

            if ( pieceTypeMasks.containsKey(pieceType))
            {
              pieceTypeMask = pieceTypeMasks.get(pieceType);
            }
            else
            {
              pieceTypeMask = new ForwardDeadReckonInternalMachineState(pieceMask);
              pieceTypeMask.clear();
              pieceTypeMasks.put(pieceType, pieceTypeMask);
            }

            pieceTypeMask.add(prop);
          }
        }

        assert(pieceTypeMasks.size() == smallestRangeSize);

        //if ( pieceTypeMasks.size() >= MIN_NUM_PIECES )
        {
          individualPieceTypes = new ConstituentPieceInfo[pieceTypeMasks.size()];

          int index = 0;
          for(Entry<String, ForwardDeadReckonInternalMachineState> entry : pieceTypeMasks.entrySet())
          {
            individualPieceTypes[index] = new ConstituentPieceInfo(entry.getKey(), entry.getValue());
            individualPieceUsageStats.add(new PieceUsageStats(individualPieceTypes[index], numRoles));
            index++;
          }
        }
      }
    }

    /**
     * Merge the info for two piece maps together to create an amalgamated one
     * @param other
     */
    public void merge(PieceMaskSpecifier other)
    {
      overallPieceMask.merge(other.overallPieceMask);

      int newPieceTypeCount = (individualPieceTypes == null ? 1 : individualPieceTypes.length) + (other.individualPieceTypes == null ? 1 : other.individualPieceTypes.length);

      if ( newPieceTypeCount > 0 )
      {
        ConstituentPieceInfo[] combined = new ConstituentPieceInfo[newPieceTypeCount];

        int index = 0;

        if ( individualPieceTypes != null )
        {
          for(ConstituentPieceInfo pieceInfo : individualPieceTypes)
          {
            combined[index++] = pieceInfo;
          }
        }

        if ( other.individualPieceTypes != null )
        {
          for(ConstituentPieceInfo pieceInfo : other.individualPieceTypes)
          {
            combined[index++] = pieceInfo;
          }
        }

        individualPieceTypes = combined;
        individualPieceUsageStats.addAll(other.individualPieceUsageStats);
      }
    }

    public int getNumPieceTypes()
    {
      return numPieceTypes;
    }

    public void finalizePieceValues(int roleIndex)
    {
      numPieceTypes = (individualPieceTypes != null ? individualPieceTypes.length : 1);
      if ( numPieceTypes < MIN_NUM_PIECES )
      {
        individualPieceTypes = null;
      }

      if ( USE_MOBILITY_VALUES_FOR_PIECES )
      {
        double minExpMobilityMeasure = Double.MAX_VALUE;
        double mobilityNormalizer = 1;

        for(PieceUsageStats pieceUsage : individualPieceUsageStats)
        {
          double mobilityMeasure = pieceUsage.getMobilityWeighting(roleIndex);
          double presenceMeasure = pieceUsage.getPresenceWeighting(roleIndex);

          if ( presenceMeasure != 0 )
          {
            if ( mobilityMeasure < minExpMobilityMeasure )
            {
              //  If a piece has correlation but no mobility at all we cannot measure relative values
              //  so don't attempt to discriminate
              if ( mobilityMeasure == 0 )
              {
                individualPieceTypes = null;
              }

              minExpMobilityMeasure = mobilityMeasure;
              mobilityNormalizer = 1/minExpMobilityMeasure;
            }
          }
        }

        for(PieceUsageStats pieceUsage : individualPieceUsageStats)
        {
          LOGGER.info("Piece type " + pieceUsage.getPieceName() + " mobility value: " + pieceUsage.getMobilityWeighting(roleIndex)*mobilityNormalizer);

          pieceUsage.getPieceInfo().value = pieceUsage.getMobilityWeighting(roleIndex)*mobilityNormalizer;
        }
      }
      else
      {
        double minPresenceMeasure = Double.MAX_VALUE;
        double presenceNormalizer = 1;

        for(PieceUsageStats pieceUsage : individualPieceUsageStats)
        {
          double presenceMeasure = pieceUsage.getPresenceWeighting(roleIndex);

          if ( presenceMeasure < minPresenceMeasure )
          {
            minPresenceMeasure = presenceMeasure;
            presenceNormalizer = 1/minPresenceMeasure;
          }
        }

        for(PieceUsageStats pieceUsage : individualPieceUsageStats)
        {
          LOGGER.info("Piece type " + pieceUsage.getPieceName() + " presence value: " + pieceUsage.getPresenceWeighting(roleIndex)*presenceNormalizer);

          pieceUsage.getPieceInfo().value = pieceUsage.getPresenceWeighting(roleIndex)*presenceNormalizer;
        }
      }
    }

    /**
     * Return the material value of a state
     * @param state
     * @return material value according to the known piece types and values
     */
    public double getValue(ForwardDeadReckonInternalMachineState state)
    {
      double result = 0;

      if ( individualPieceTypes == null )
      {
        result += overallPieceMask.intersectionSize(state);
      }
      else
      {
        for(ConstituentPieceInfo pieceInfo : individualPieceTypes)
        {
          result += pieceInfo.mask.intersectionSize(state)*pieceInfo.value;
        }
      }
      return result;
    }

    @Override
    public boolean equals(Object other)
    {
      if ( other instanceof PieceMaskSpecifier )
      {
        return overallPieceMask.equals(((PieceMaskSpecifier)other).overallPieceMask);
      }

      return false;
    }

    @Override
    public String toString()
    {
      return overallPieceMask.toString() + " with piece types " + java.util.Arrays.toString(individualPieceTypes);
    }
  }

  /**
   * Default constructor
   */
  public PieceHeuristic()
  {
  }

  private PieceHeuristic(PieceHeuristic copyFrom)
  {
    propGroupScoreSets         = copyFrom.propGroupScoreSets;
    numRoles                   = copyFrom.numRoles;
    pieceSets                  = copyFrom.pieceSets;
    totalSimulatedTurns        = copyFrom.totalSimulatedTurns;
    //  The following track runtime usage state and are dependent on the current game-state
    rootPieceValues            = new double[numRoles];
    mUseSimpleHeuristic        = copyFrom.mUseSimpleHeuristic;
  }

  /**
   * @param value - whether to use simple piece heuristic (no use of heuristic
   * steps, based on piece count relative to root, applied simply)
   */
  public void setUseSimpleHeuristic(boolean value)
  {
    mUseSimpleHeuristic = value;
  }

  /**
   * Add values of each argument of a sentences body to a fn value map
   * @param sentence
   * @param fnMap
   */
  static void addSentenceToFnMap(GdlSentence sentence, Map<String, GdlFunctionInfo> fnMap)
  {
    if (sentence.arity() == 1 &&
        sentence.getBody().get(0) instanceof GdlFunction)
    {
      GdlFunction propFn = (GdlFunction)sentence.getBody().get(0);

      if (propFn.arity() >= MIN_PIECE_PROP_ARITY &&
          propFn.arity() <= MAX_PIECE_PROP_ARITY)
      {
        GdlFunctionInfo fnInfo;

        if (fnMap.containsKey(propFn.getName().toString()))
        {
          fnInfo = fnMap.get(propFn.getName().toString());
        }
        else
        {
          fnInfo = new GdlFunctionInfo(propFn.getName().toString(),
                                       propFn.arity());
          fnMap.put(propFn.getName().toString(), fnInfo);
        }

        for (int i = 0; i < propFn.arity(); i++)
        {
          fnInfo.paramRanges.get(i).add(propFn.getBody().get(i).toString());
        }
      }
    }
  }

  @Override
  public boolean tuningInitialise(ForwardDeadReckonPropnetStateMachine stateMachine,
                               RoleOrdering xiRoleOrdering)
  {
    pieceSets = null;
    numRoles = stateMachine.getRoles().size();
    propGroupScoreSets = new HashMap<>();

    //  Currently we only support piece heuristics in 2 player games as we apply them
    //  in an assumed fixed-sum manner
    if ( numRoles != 2 )
    {
      return false;
    }

    rootPieceValues = new double[numRoles];

    Map<String, GdlFunctionInfo> basePropFns = new HashMap<>();

    for (GdlSentence baseProp : stateMachine.getBasePropositions())
    {
      addSentenceToFnMap(baseProp, basePropFns);
    }

    Set<PotentialPiecePropSet> potentialPiecePropSets = new HashSet<>();

    for (GdlFunctionInfo fnInfo : basePropFns.values())
    {
      int smallestFit = Integer.MAX_VALUE;
      int smallestFitIndex = -1;
      int ignoreSize = -1;

      for (int i = 0; i < fnInfo.paramRanges.size(); i++)
      {
        int numValues = fnInfo.paramRanges.get(i).size();

        if ( numValues % numRoles == 0 || numValues % numRoles == 1)
        {
          if ( numValues < smallestFit )
          {
            smallestFit = numValues;
          }
          //  Slight hack - we're trying to avoid accidentally trying to treat a board
          //  dimension as a piece type or owning role (which can be combined in some games)
          //  For this reason if the smallest arity is actually a board dimension (if there
          //  are more types of pieces than that, as in Los Alamos Chess) then we want to
          //  ignore the smallest arity and try the next size up.  However, only do this
          //  if the size we're ignoring is a plausible board dimension.  If the actual role/type
          //  indicator is not of unique range size we cannot reliably find it in this manner
          //  (which is why it's a slight hack and needs to be replaced with something more
          //  robust at some point).  Awkward cases arise if the number of piece types are
          //  equal to a board dimension (too bad!) or if the role and piece type are separated
          //  but have the same cardinality (English Draughts for example).  In the event of a tie
          //  we take the first one mentioned in the sentence (because it's a slightly more
          //  natural way to write the GDL to say 'black king' then 'king black') but obviously
          //  this is essentially a guess!
          else if ( numValues == smallestFit && numValues >= MIN_BOARD_DIMENSION )
          {
            ignoreSize = smallestFit;
          }
        }
      }

      smallestFit = Integer.MAX_VALUE;

      //	Look for ranges that have cardinality of a multiple of the number of roles

      for (int i = 0; i < fnInfo.paramRanges.size(); i++)
      {
        int numValues = fnInfo.paramRanges.get(i).size();

        if (numValues != ignoreSize &&
            numValues < smallestFit &&
            (numValues % numRoles == 0 || numValues % numRoles == 1))
        {
          smallestFit = numValues;
          smallestFitIndex = i;
        }
      }

      if (smallestFitIndex != -1 && smallestFit <= MAX_NUM_PIECES)
      {
        potentialPiecePropSets
            .add(new PotentialPiecePropSet(fnInfo.getName(), smallestFitIndex));
      }
    }

    for (PotentialPiecePropSet pieceSet : potentialPiecePropSets)
    {
      GdlFunctionInfo info = basePropFns.get(pieceSet.fnName);

      for (String roleArgValue : info.paramRanges
          .get(pieceSet.potentialRoleArgIndex))
      {
        Set<GdlSentence> pieceSetSentences = new HashSet<>();

        for (GdlSentence baseProp : stateMachine.getBasePropositions())
        {
          if (baseProp.arity() == 1 &&
              baseProp.getBody().get(0) instanceof GdlFunction)
          {
            GdlFunction propFn = (GdlFunction)baseProp.getBody().get(0);

            if (propFn.arity() >= MIN_PIECE_PROP_ARITY &&
                propFn.arity() <= MAX_PIECE_PROP_ARITY &&
                pieceSet.fnName.equals(propFn.getName().toString()) &&
                propFn.getBody().get(pieceSet.potentialRoleArgIndex)
                    .toString().equals(roleArgValue))
            {
              pieceSetSentences.add(baseProp);
            }
          }
        }

        if (pieceSetSentences.size() >= MIN_PIECES_THRESHOLD)
        {
          LOGGER.debug("Possible piece set: " + pieceSetSentences);

          ForwardDeadReckonInternalMachineState pieceMask = stateMachine.createInternalState(
                                                                                   new MachineState(pieceSetSentences));
          PieceMaskSpecifier pieceSpecifier = new PieceMaskSpecifier(pieceMask);
          propGroupScoreSets.put(pieceSpecifier, new HeuristicScoreInfo(numRoles));
        }
      }
    }

    return propGroupScoreSets.size() > 0;
  }

  @Override
  public void tuningStartSampleGame()
  {
    for (Entry<PieceMaskSpecifier, HeuristicScoreInfo> e : propGroupScoreSets
        .entrySet())
    {
      HeuristicScoreInfo heuristicInfo = e.getValue();
      heuristicInfo.lastValue = -1;

      for(PieceUsageStats usageStats : e.getKey().individualPieceUsageStats)
      {
        usageStats.startSampleGame();
      }
    }
  }

  @Override
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState sampleState,
                                       int choosingRoleIndex)
  {
    for (Entry<PieceMaskSpecifier, HeuristicScoreInfo> e : propGroupScoreSets
        .entrySet())
    {
      int currentValue = e.getKey().overallPieceMask.intersectionSize(sampleState);

      HeuristicScoreInfo heuristicInfo = e.getValue();
      if (heuristicInfo.lastValue != -1)
      {
        if (heuristicInfo.lastValue == currentValue)
        {
          heuristicInfo.noChangeTurnRate++;
        }
        else if (choosingRoleIndex >= 0)
        {
          heuristicInfo.hasRoleChanges[choosingRoleIndex] = true;
        }
        else if (choosingRoleIndex == -1)
        {
          //	In a simultaneous turn game all roles choose
          for (int i = 0; i < numRoles; i++)
          {
            heuristicInfo.hasRoleChanges[i] = true;
          }
        }
      }

      heuristicInfo.lastValue = currentValue;

      for(PieceUsageStats usageStats : e.getKey().individualPieceUsageStats)
      {
        usageStats.sampleMove(sampleState);
      }
    }

    totalSimulatedTurns++;
  }

  @Override
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState finalState,
                                        int[] roleScores)
  {
    for (Entry<PieceMaskSpecifier, HeuristicScoreInfo> e : propGroupScoreSets.entrySet())
    {
      int heuristicScore = finalState.intersectionSize(e.getKey().overallPieceMask);
      e.getValue().accrueSample(heuristicScore, roleScores);

      for(PieceUsageStats usageStats : e.getKey().individualPieceUsageStats)
      {
        usageStats.endSampleGame(roleScores);
      }
    }
  }

  @Override
  public void tuningComplete()
  {
    mTuningComplete = true;

    for (Entry<PieceMaskSpecifier, HeuristicScoreInfo> e : propGroupScoreSets.entrySet())
    {
      HeuristicScoreInfo heuristicInfo = e.getValue();

      heuristicInfo.noChangeTurnRate /= totalSimulatedTurns;
    }

    for (Entry<PieceMaskSpecifier, HeuristicScoreInfo> e : propGroupScoreSets.entrySet())
    {
      if (e.getValue().noChangeTurnRate < 0.5)
      {
        LOGGER.debug("Eliminating potential piece set with no-change rate: " +
                     e.getValue().noChangeTurnRate + ": " + e.getKey());
      }
      else
      {
        double[] roleCorrelations = e.getValue().getRoleCorrelations();

        LOGGER.debug("Correlations for piece set: " + e.getKey());
        for (int i = 0; i < numRoles; i++)
        {
          LOGGER.debug("  Role " + i + ": " + roleCorrelations[i]);

          if (roleCorrelations[i] >= MIN_HEURISTIC_CORRELATION)
          {
            if (!e.getValue().hasRoleChanges[i])
            {
              LOGGER.debug("Eliminating potential piece set with no role decision changes for correlated role: " +
                           e.getKey());
            }
            else
            {
              LOGGER.debug("Using piece set above for role above");
              if (pieceSets == null)
              {
                pieceSets = new PieceMaskSpecifier[numRoles];
              }

              if (pieceSets[i] == null)
              {
                pieceSets[i] = e.getKey();
              }
              else
              {
                pieceSets[i].merge(e.getKey());
              }
            }
          }
          else
          {
            LOGGER.debug("Piece set insufficiently correlated for role");
          }
        }
      }
    }

    //  If both roles have exactly the SAME heuristic it is pointless (since we're looking at the difference when
    //  evaluating), so discount this case (motivated by Alerquerque where both roles like to maximize blanks with positive
    //  correlation [and will also find they like to minimize opponent pieces even more if you let it examine negative
    //  correlation as it will below])
    if ( pieceSets != null && numRoles > 1)
    {
      boolean pieceSetsMatchForAllRoles = true;

      for(int i = 0; i < numRoles; i++)
      {
        if ( pieceSets[i] != null && !pieceSets[i].equals(pieceSets[0]))
        {
          pieceSetsMatchForAllRoles = false;
          break;
        }
      }

      if ( pieceSetsMatchForAllRoles )
      {
        LOGGER.info("Positively correlated piece sets were the same for all roles so discounting and checking for negatively correlated sets");
        pieceSets = null;
      }
    }

    //  If we found no positively correlated piece set then (for two player games only) look for negatively correlated ones
    //  and switch the sets over if found.  This is a fairly crude approach (that doesn't extend easily from 2-player games),
    //  but for now it's a smaller and easier to test change to what we have than the more general approach of allowing
    //  negated heuristic values (because they tend to change on the other player's turn)
    if ( pieceSets == null && numRoles == 2 )
    {
      for (Entry<PieceMaskSpecifier, HeuristicScoreInfo> e : propGroupScoreSets.entrySet())
      {
        if (e.getValue().noChangeTurnRate >= 0.5)
        {
          double[] roleCorrelations = e.getValue().getRoleCorrelations();

          for (int i = 0; i < numRoles; i++)
          {
            if (roleCorrelations[i] <= -MIN_HEURISTIC_CORRELATION)
            {
              //  opposite sense of test since we're planning to swap over the piece sets
              if (!e.getValue().hasRoleChanges[i])
              {
                if (pieceSets == null)
                {
                  pieceSets = new PieceMaskSpecifier[numRoles];
                }

                if (pieceSets[i] == null)
                {
                  pieceSets[i] = e.getKey();
                }
                else
                {
                  pieceSets[i].merge(e.getKey());
                }
              }
            }
          }
        }
      }

      //  Swap the piece sets over
      if ( pieceSets != null )
      {
        PieceMaskSpecifier temp = pieceSets[0];

        pieceSets[0] = pieceSets[1];
        pieceSets[1] = temp;
      }
    }

    // Check that all roles have a set of pieces.  If not, disable the heuristic.
    if (pieceSets != null)
    {
      LOGGER.debug("Some roles have piece sets");
      for (int i = 0; i < numRoles; i++)
      {
        LOGGER.debug("  Checking role " + i);
        if (pieceSets[i] == null)
        {
          LOGGER.debug("      No piece set for this role");
          LOGGER.debug("Piece heuristic only identified for a subset of roles - disabling");
          pieceSets = null;
          break;
        }
        LOGGER.debug("    Final piece set is: " + pieceSets[i]);
      }

      if (pieceSets != null)
      {
        mUseSimpleHeuristic = true;

        LOGGER.debug("All roles have piece sets");
        for (int i = 0; i < numRoles; i++)
        {
          pieceSets[i].finalizePieceValues(i);
          //  Nasty hack that basically is used to cause checkers-type games to use simple piece heuristics for now
          if ( pieceSets[i].getNumPieceTypes() != 2 )
          {
            mUseSimpleHeuristic = false;
          }

          LOGGER.info("Role " + i + " will use piece set " + pieceSets[i]);
        }
      }
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
    //  The simple heuristic is exactly the one we used in 1.60, prior to the rework of
    //  heuristics to better support games like chess and skirmish
    if ( mUseSimpleHeuristic )
    {
      double total = 0;
      double rootTotal = 0;

      for (int i = 0; i < numRoles; i++)
      {
        // Set the initial heuristic value for this role according to the difference in number of pieces between this
        // state and the current state in the tree root.
        double numPieces = pieceSets[i].getValue(xiState);
        resultInfo.heuristicValue[i] = numPieces - rootPieceValues[i];

        total += numPieces;
        rootTotal += rootPieceValues[i];

        // Counter-weight exchange sequences slightly to remove the first-capture bias, at least to first order.
        double previousNumPieces = pieceSets[i].getValue(xiPreviousState);
        if (numPieces == rootPieceValues[i] &&
            previousNumPieces < rootPieceValues[i])
        {
          resultInfo.heuristicValue[i] += 0.1;
          total += 0.1;
        }
        else if (numPieces == rootPieceValues[i] &&
                 previousNumPieces > rootPieceValues[i])
        {
          resultInfo.heuristicValue[i] -= 0.1;
          total -= 0.1;
        }
      }

      for (int i = 0; i < numRoles; i++)
      {
        if (rootTotal != total)
        {
          // There has been an overall change in the number of pieces.  Calculate the proportion of that total gained/lost
          // by this role and use that to generate a new average heuristic value for the role.
          double proportion = (resultInfo.heuristicValue[i] - (total - rootTotal) / numRoles) / (total / numRoles);
          resultInfo.heuristicValue[i] = 100 / (1 + Math.exp(-proportion * 10));
        }
        else
        {
          // There has been no overall change to the number of pieces.  Assume an average value.
          // !! ARR Why?
          resultInfo.heuristicValue[i] = 50;
        }

        // Normalize against the root score since this is relative to the root state material balance.  Only do this if
        // the root has had enough visits to have a credible estimate.
        if (mRootNode != null && mRootNode.mNumVisits > 50)
        {
          // Set the average score for the child to the average score of the root displaced towards the extremities
          // (0/100) by a proportion of the amount that it currently deviates from the extremities, where that proportion
          // is equal to the proportion by which the heuristic value deviates from the centre.
          //
          // This assumes that the root's score is a very good basis as the initial estimate for this child node and is
          // certainly better than the heuristic value alone.
          double rootAverageScore = mRootNode.getAverageScore(i);
          if (resultInfo.heuristicValue[i] > 50)
          {
            resultInfo.heuristicValue[i] = rootAverageScore +
                                  (100 - rootAverageScore) *
                                  (resultInfo.heuristicValue[i] - 50) /
                                  50;
          }
          else
          {
            resultInfo.heuristicValue[i] = rootAverageScore -
                                  (rootAverageScore) *
                                  (50 - resultInfo.heuristicValue[i]) /
                                  50;
          }
        }
      }
    }
    else
    {
      //  If a piece value is 0 thn we risk divide by zero issues, so add EPSILON
      double ourPieceValue = pieceSets[0].getValue(xiState) + EPSILON;
      double theirPieceValue = pieceSets[1].getValue(xiState) + EPSILON;
      double proportion = (ourPieceValue - theirPieceValue) / (ourPieceValue + theirPieceValue);
      double referenceProportion = 0;

      if ( xiPreviousState != null )
      {
        double ourPreviousPieceValue = pieceSets[0].getValue(xiPreviousState) + EPSILON;
        double theirPreviousPieceValue = pieceSets[1].getValue(xiPreviousState) + EPSILON;
        double previousProportion = (ourPreviousPieceValue - theirPreviousPieceValue) / (ourPreviousPieceValue + theirPreviousPieceValue);

        resultInfo.treatAsSequenceStep = (Math.abs( proportion - previousProportion ) > MIN_HEURISTIC_DIFF_FOR_SEQUENCE);

        if ( xiReferenceState == xiPreviousState )
        {
          referenceProportion = (ourPreviousPieceValue - theirPreviousPieceValue) / (ourPreviousPieceValue + theirPreviousPieceValue);
        }
        else
        {
          double ourReferencePieceValue = pieceSets[0].getValue(xiReferenceState) + EPSILON;
          double theirReferencePieceValue = pieceSets[1].getValue(xiReferenceState) + EPSILON;

          referenceProportion = (ourReferencePieceValue - theirReferencePieceValue) / (ourReferencePieceValue + theirReferencePieceValue);
        }
      }
      else
      {
        proportion = 0;
        resultInfo.treatAsSequenceStep = false;
      }

      double sigmaScaling = (ourPieceValue+theirPieceValue)/4;

      resultInfo.heuristicValue[0] = 100 * sigma(sigmaScaling*(proportion-referenceProportion));
      resultInfo.heuristicValue[1] = 100 - resultInfo.heuristicValue[0];
    }

    resultInfo.heuristicWeight = heuristicSampleWeight;
  }

  private static double sigma(double value)
  {
    return 1/(1 + Math.exp(-value));
  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState, TreeNode xiNode)
  {
    if (pieceSets != null)
    {
      double total = 0;
      double ourPieceValue = 0;

      mRootNode = xiNode;

      for (int i = 0; i < numRoles; i++)
      {
        rootPieceValues[i] = pieceSets[i].getValue(xiState);
        total += rootPieceValues[i];

        if (i == 0)
        {
          ourPieceValue = total;
        }
      }

      double averageMaterial = total/numRoles;
      double ourMaterialDivergence = ourPieceValue - averageMaterial;

      //  Weight further material gain down the more we're already ahead/behind in material
      //  because in either circumstance it's likely to be position that is more important
      heuristicSampleWeight = (int)Math.max(2, 6.5 - Math.abs(ourMaterialDivergence)*48/averageMaterial);

      LOGGER.info("Piece heuristic weight set to: " + heuristicSampleWeight);
    }
    else
    {
      heuristicSampleWeight = 0;
    }
  }

  @Override
  public boolean isEnabled()
  {
    // We're enabled by default, unless we fail to discover piece sets by the end of tuning.
    return ((!mTuningComplete) || (pieceSets != null));
  }

  @Override
  public Heuristic createIndependentInstance()
  {
    return new PieceHeuristic(this);
  }

  @Override
  public boolean applyAsSimpleHeuristic()
  {
    return mUseSimpleHeuristic;
  }
}
