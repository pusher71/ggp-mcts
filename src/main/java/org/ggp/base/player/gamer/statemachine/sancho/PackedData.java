package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * Utility functions for loading and saving game state.
 */
public class PackedData
{
  private final String mPacked;
  private int mOffset;

  /**
   * Create packed data from a string (typically just read from a file).
   *
   * @param xiPacked - the packed data string.
   */
  public PackedData(String xiPacked)
  {
    mPacked = xiPacked;
  }

  /**
   * Check that the next section of the packed data contains the specified string.
   *
   * @param xiExpected - the expected string.
   */
  public void checkStr(String xiExpected)
  {
    if (!mPacked.substring(mOffset, mOffset + xiExpected.length()).equals(xiExpected))
      throw new RuntimeException("Unexpected packed data.  Expected " + xiExpected + " at offset " + mOffset + " of " +
                                 mPacked);
    mOffset += xiExpected.length();
  }

  /**
   * Read a boolean from the packed data.
   *
   * @return the value.
   */
  public boolean loadBool()
  {
    return exerpt().equals("true");
  }

  /**
   * Read an int from the packed data.
   *
   * @return the value.
   */
  public int loadInt()
  {
    return Integer.parseInt(exerpt());
  }

  /**
   * Read a long from the packed data.
   *
   * @return the value.
   */
  public long loadLong()
  {
    return Long.parseLong(exerpt());
  }

  /**
   * @return the string up to the next boundary character (comma, close brace or end of string).
   */
  private String exerpt()
  {
    String lResult;
    int lCommaOffset = mPacked.indexOf(',', mOffset);
    if (lCommaOffset == -1) lCommaOffset = Integer.MAX_VALUE;

    int lBraceOffset = mPacked.indexOf('}', mOffset);
    if (lBraceOffset == -1) lBraceOffset = Integer.MAX_VALUE;

    if (lCommaOffset < lBraceOffset)
    {
      lResult = mPacked.substring(mOffset, lCommaOffset);
      mOffset = lCommaOffset;
    }
    else if (lBraceOffset < lCommaOffset)
    {
      lResult = mPacked.substring(mOffset, lBraceOffset);
      mOffset = lBraceOffset;
    }
    else
    {

      mOffset = mPacked.length();
      lResult = mPacked.substring(mOffset);
    }

    return lResult;
  }
}
