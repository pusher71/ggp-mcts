package org.ggp.base.util.propnet.polymorphic.tristate;

import java.util.List;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponentFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.statemachine.Role;

public class TristateComponentFactory extends PolymorphicComponentFactory
{
  private TristatePropNet mNetwork;

  @Override
  public void setNetwork(PolymorphicPropNet xiNetwork)
  {
    mNetwork = (TristatePropNet)xiNetwork;
  }

  @Override
  public PolymorphicPropNet createPropNet(List<Role> xiRoles,
                                          Set<PolymorphicComponent> xiComponents)
  {
    throw new RuntimeException("Not implemented");
  }

  @Override
  public PolymorphicAnd createAnd(int xiNumInputs, int xiNumOutputs)
  {
    return new TristateAnd(mNetwork);
  }

  @Override
  public PolymorphicOr createOr(int xiNumInputs, int xiNumOutputs)
  {
    return new TristateOr(mNetwork);
  }

  @Override
  public PolymorphicNot createNot(int xiNumOutputs)
  {
    return new TristateNot(mNetwork);
  }

  @Override
  public PolymorphicConstant createConstant(int xiNumOutputs, boolean xiValue)
  {
    return new TristateConstant(mNetwork, xiValue);
  }

  @Override
  public PolymorphicProposition createProposition(int xiNumOutputs, GdlSentence xiName)
  {
    return new TristateProposition(mNetwork, xiName);
  }

  @Override
  public PolymorphicTransition createTransition(int xiNumOutputs)
  {
    return new TristateTransition(mNetwork);
  }
}
