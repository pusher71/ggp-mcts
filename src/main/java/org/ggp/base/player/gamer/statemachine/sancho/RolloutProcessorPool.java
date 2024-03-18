package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * A fixed size pool of rollout processors.
 */
public class RolloutProcessorPool
{
  private static final Logger LOGGER = LogManager.getLogger();

  private final Pipeline                               mPipeline;
  private final RolloutProcessor[]                     mRolloutProcessors;

  /**
   * Create a pool of rollout processors.
   *
   * @param xiPipeline - the work pipeline.
   * @param xiUnderlyingStateMachine - the underlying state machine.
   * @param xiRoleOrdering - the role ordering.
   */
  public RolloutProcessorPool(Pipeline xiPipeline,
                              ForwardDeadReckonPropnetStateMachine xiUnderlyingStateMachine,
                              RoleOrdering xiRoleOrdering,
                              String xiLogName)
  {
    mPipeline = xiPipeline;
    mRolloutProcessors = new RolloutProcessor[ThreadControl.ROLLOUT_THREADS];
    for (int lii = 0; lii < ThreadControl.ROLLOUT_THREADS; lii++)
    {
      mRolloutProcessors[lii] = new RolloutProcessor(lii,
                                                     mPipeline,
                                                     xiUnderlyingStateMachine.createInstance(),
                                                     xiRoleOrdering,
                                                     xiLogName);
    }
  }

  /**
   * Stop all rollout processors.
   */
  public void stop()
  {
    LOGGER.info("Stop rollout processors");

    for (int lii = 0; lii < ThreadControl.ROLLOUT_THREADS; lii++)
    {
      mRolloutProcessors[lii].stop();
    }

    LOGGER.info("Finished stopping rollout processors");
  }

  /**
   * Disable greedy rollouts for all rollout processors.
   */
  public void disableGreedyRollouts()
  {
    for (int ii = 0; ii < ThreadControl.ROLLOUT_THREADS; ii++)
    {
      mRolloutProcessors[ii].disableGreedyRollouts();
    }
  }
}
