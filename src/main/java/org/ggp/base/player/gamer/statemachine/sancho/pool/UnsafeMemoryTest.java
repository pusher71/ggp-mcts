package org.ggp.base.player.gamer.statemachine.sancho.pool;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import sun.misc.Unsafe;

public class UnsafeMemoryTest
{
  public static class MyStructure
  {
    public int x;
    public int y;
  }

  public static void main(String[] xiArgs) throws Exception
  {
    MyStructure structure = new MyStructure(); // create a test object
    structure.x = 777;

    long size = sizeof(MyStructure.class);
    long offheapPointer = getUnsafe().allocateMemory(size);
    getUnsafe().copyMemory(
                    structure,      // source object
                    0,              // source offset is zero - copy an entire object
                    null,           // destination is specified by absolute address, so destination object is null
                    offheapPointer, // destination address
                    size
    ); // test object was copied to off-heap

    Pointer p = new Pointer(); // Pointer is just a handler that stores address of some object
    long pointerOffset = getUnsafe().objectFieldOffset(Pointer.class.getDeclaredField("pointer"));
    getUnsafe().putLong(p, pointerOffset, offheapPointer); // set pointer to off-heap copy of the test object

    structure.x = 222; // rewrite x value in the original object
    System.out.println(structure.x);
    System.out.println(  ((MyStructure)p.pointer).x  ); // prints 777
  }

  public static Unsafe getUnsafe()
  {
    try
    {
      Field f = Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      return (Unsafe)f.get(null);
    }
    catch (Exception e) {/* Oh well */}
    return null;
  }

  public static long sizeof(Class<?> clazz)
  {
    long maximumOffset = 0;
    do
    {
      for (Field f : clazz.getDeclaredFields())
      {
        if (!Modifier.isStatic(f.getModifiers()))
        {
          maximumOffset = Math.max(maximumOffset, getUnsafe().objectFieldOffset(f));
        }
      }
    } while ((clazz = clazz.getSuperclass()) != null);

    return maximumOffset + 8;
  }

  public static class Pointer
  {
    public Object pointer;
  }
}
