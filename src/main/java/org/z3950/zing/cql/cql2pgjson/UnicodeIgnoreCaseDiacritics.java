package org.z3950.zing.cql.cql2pgjson;

import java.util.Map;

/**
 * Provides regular expressions for matching Unicode characters ignoring case and accents/diacritics.
 */
public final class UnicodeIgnoreCaseDiacritics {
  private static Map<Character,String> unmodifiableMap = Unicode.readMappingFile("UnicodeIgnoreCaseDiacritics");

  private UnicodeIgnoreCaseDiacritics() {
    throw new UnsupportedOperationException("Cannot instantiate utility class.");
  }

  /**
   * For each Character c the map provides a regexp that matches any
   * String that is an accents/diacritics ignoring equivalent to c including c.
   * The map returns null if there is no other equivalent for c.
   * The map is unmodifiable.
   * @return  the map
   */
  public static Map<Character,String> getRegexpMap() {
    return unmodifiableMap;
  }
}
