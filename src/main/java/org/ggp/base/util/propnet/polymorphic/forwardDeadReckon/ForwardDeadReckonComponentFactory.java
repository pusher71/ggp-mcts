
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

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

/**
 * @author steve
 *  Factory for ForwardDeadReckon family components
 */
public class ForwardDeadReckonComponentFactory extends
                                              PolymorphicComponentFactory
{

  /**
   * Constructs a new factory
   */
  public ForwardDeadReckonComponentFactory()
  {
  }

  @Override
  public PolymorphicPropNet createPropNet(List<Role> roles,
                                          Set<PolymorphicComponent> components)
  {
    return new ForwardDeadReckonPropNet(roles, components, this);
  }

  @Override
  public PolymorphicAnd createAnd(int numInputs, int numOutputs)
  {
    return new ForwardDeadReckonAnd(numInputs, numOutputs);
  }

  @Override
  public PolymorphicOr createOr(int numInputs, int numOutputs)
  {
    return new ForwardDeadReckonOr(numInputs, numOutputs);
  }

  @Override
  public PolymorphicNot createNot(int numOutputs)
  {
    return new ForwardDeadReckonNot(numOutputs);
  }

  @Override
  public PolymorphicConstant createConstant(int numOutputs, boolean value)
  {
    return new ForwardDeadReckonConstant(numOutputs, value);
  }

  @Override
  public PolymorphicProposition createProposition(int numOutputs,
                                                  GdlSentence name)
  {
    return new ForwardDeadReckonProposition(numOutputs, name);
  }

  @Override
  public PolymorphicTransition createTransition(int numOutputs)
  {
    return new ForwardDeadReckonTransition(numOutputs);
  }
}
