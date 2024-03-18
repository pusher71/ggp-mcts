package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateComponent.ContradictionException;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateComponent.Tristate;

public class TristatePropNet extends PolymorphicPropNet
{
  /**
   * Create a tri-state propnet from another propnet (of any kind).
   *
   * @param xiSourcePropnet - the source
   */
  public TristatePropNet(PolymorphicPropNet xiSourcePropnet)
  {
    super(xiSourcePropnet, new TristateComponentFactory());
  }

  /**
   * Reset the network to its default state.
   */
  public void reset()
  {
    // Reset all components.
    for (PolymorphicComponent lComponent : getComponents())
    {
      ((TristateComponent)lComponent).reset();
    }

    // Assume that the init proposition is false in all turns.  This means that we can't find latches which only rely on
    // something happening during the first turn, but we can live with that.
    try
    {
      TristateProposition lInitProp = ((TristateProposition)getInitProposition());
      if ( lInitProp != null )
      {
        for (int lii = 0; lii < 3; lii++)
        {
          lInitProp.mState[lii].mValue = Tristate.FALSE;
          lInitProp.propagateOutput(lii, false);
        }
      }
    }
    catch (ContradictionException lEx)
    {
      throw new RuntimeException("Couldn't even reset the latch analysis network");
    }
  }
}
