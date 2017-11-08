package org.z3950.zing.cql.cql2pgjson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.z3950.zing.cql.CQLAndNode;
import org.z3950.zing.cql.CQLBooleanNode;
import org.z3950.zing.cql.CQLNode;
import org.z3950.zing.cql.CQLNotNode;
import org.z3950.zing.cql.CQLOrNode;
import org.z3950.zing.cql.CQLParseException;
import org.z3950.zing.cql.CQLParser;
import org.z3950.zing.cql.CQLSortNode;
import org.z3950.zing.cql.CQLTermNode;
import org.z3950.zing.cql.Modifier;
import org.z3950.zing.cql.ModifierSet;

/**
 * Converter from CQL to SQL using PostgreSQL's JSON fields.
 *
 * Contextual Query Language (CQL) Specification:
 * https://www.loc.gov/standards/sru/cql/spec.html
 */
public class CQL2PgJSON {
  /**
   * Name of the JSON field, may include schema and table name (e.g. tenant1.user_table.json).
   * Must conform to SQL identifier requirements (characters, not a keyword), or properly
   * quoted using double quotes.
   */
  private String jsonField = null;
  private List<String> jsonFields = null;
  /** Local data model of JSON schema */
  private Schema schema;
  private Map<String,Schema> schemas;

  /** JSON number, see spec at http://json.org/ */
  private static final Pattern jsonNumber = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

  /**
   * Default index names to be used for cql.serverChoice.
   * May be empty, but not null. Must not contain null, names must not contain double quote or single quote.
   */
  private List<String> serverChoiceIndexes = Collections.emptyList();

  private enum CqlSort {
    ASCENDING, DESCENDING;
  }

  private enum CqlCase {
    IGNORE_CASE, RESPECT_CASE;
  }

  private enum CqlAccents {
    IGNORE_ACCENTS, RESPECT_ACCENTS;
  }

  private enum CqlMasking {
    MASKED, UNMASKED, SUBSTRING, REGEXP;
  }

  private class IndexTextAndJsonValues {
    String indexText;
    String indexJson;
  }

  private static class CqlModifiers {
    CqlSort    cqlSort    = CqlSort   .ASCENDING;
    CqlCase    cqlCase    = CqlCase   .IGNORE_CASE;
    CqlAccents cqlAccents = CqlAccents.IGNORE_ACCENTS;
    CqlMasking cqlMasking = CqlMasking.MASKED;

    public CqlModifiers(CQLTermNode node) {
      readModifiers(node.getRelation().getModifiers());
    }

    public CqlModifiers(ModifierSet modifierSet) {
      readModifiers(modifierSet.getModifiers());
    }

    /**
     * Read the modifiers and write the last for each enum into the enum variable.
     * Default is ascending, ignoreCase, ignoreAccents and masked.
     *
     * @param modifiers  where to read from
     */
    @SuppressWarnings("squid:MethodCyclomaticComplexity")
    public final void readModifiers(List<Modifier> modifiers) {
      for (Modifier m : modifiers) {
        switch (m.getType()) {
        case "sort.ascending" : cqlSort    = CqlSort   .ASCENDING;
        break;
        case "sort.descending": cqlSort    = CqlSort   .DESCENDING;
        break;
        case "ignorecase"     : cqlCase    = CqlCase   .IGNORE_CASE;
        break;
        case "respectcase"    : cqlCase    = CqlCase   .RESPECT_CASE;
        break;
        case "ignoreaccents"  : cqlAccents = CqlAccents.IGNORE_ACCENTS;
        break;
        case "respectaccents" : cqlAccents = CqlAccents.RESPECT_ACCENTS;
        break;
        case "masked"         : cqlMasking = CqlMasking.MASKED;
        break;
        case "unmasked"       : cqlMasking = CqlMasking.UNMASKED;
        break;
        case "substring"      : cqlMasking = CqlMasking.SUBSTRING;
        break;
        case "regexp"         : cqlMasking = CqlMasking.REGEXP;
        break;
        default:
          // ignore
        }
      }
    }
  }

  /** includes unicode characters */
  private static final String WORD_CHARACTER_REGEXP = "[^[:punct:][:space:]]";

  /**
   * Create an instance for the specified schema.
   *
   * @param field Name of the JSON field, may include schema and table name (e.g. tenant1.user_table.json).
   *   Must conform to SQL identifier requirements (characters, not a keyword), or properly
   *   quoted using double quotes.
   * @throws FieldException provided field is not valid
   */
  public CQL2PgJSON(String field) throws FieldException {
    this.jsonField = trimNotEmpty(field);
  }

  /**
   * Create an instance for the specified schema.
   *
   * @param field Name of the JSON field, may include schema and table name (e.g. tenant1.user_table.json).
   *   Must conform to SQL identifier requirements (characters, not a keyword), or properly
   *   quoted using double quotes.
   * @param schemaJson JSON String representing the schema of the field the CQL queries against.
   * @throws IOException if the JSON structure is invalid
   * @throws FieldException (subclass of CQL2PgJSONException) provided field is not valid
   * @throws SchemaException (subclass of CQL2PgJSONException) provided JSON schema not valid
   */
  public CQL2PgJSON(String field, String schemaJson) throws IOException, FieldException, SchemaException {
    this(field);
    setSchema(schemaJson);
  }

  /**
   * Create an instance for the specified schema.
   *
   * @param field Name of the JSON field, may include schema and table name (e.g. tenant1.user_table.json).
   *   Must conform to SQL identifier requirements (characters, not a keyword), or properly
   *   quoted using double quotes.
   * @param serverChoiceIndexes       List of field names, may be empty, must not contain null,
   *                                  names must not contain double quote or single quote.
   * @throws FieldException (subclass of CQL2PgJSONException) - provided field is not valid
   * @throws ServerChoiceIndexesException (subclass of CQL2PgJSONException) - provided serverChoiceIndexes is not valid
   */
  public CQL2PgJSON(String field, List<String> serverChoiceIndexes) throws FieldException, ServerChoiceIndexesException {
    this(field);
    setServerChoiceIndexes(serverChoiceIndexes);
  }

  /**
   * Create an instance for the specified schema.
   *
   * @param field Name of the JSON field, may include schema and table name (e.g. tenant1.user_table.json).
   *   Must conform to SQL identifier requirements (characters, not a keyword), or properly
   *   quoted using double quotes.
   * @param schemaJson JSON String representing the schema of the field the CQL queries against.
   * @param serverChoiceIndexes       List of field names, may be empty, must not contain null,
   *                                  names must not contain double quote or single quote.
   * @throws IOException if the JSON structure is invalid
   * @throws FieldException (subclass of CQL2PgJSONException) - provided field is not valid
   * @throws SchemaException (subclass of CQL2PgJSONException) provided JSON schema not valid
   * @throws ServerChoiceIndexesException (subclass of CQL2PgJSONException) - provided serverChoiceIndexes is not valid
   */
  public CQL2PgJSON(String field, String schemaJson, List<String> serverChoiceIndexes)
      throws IOException, SchemaException, ServerChoiceIndexesException, FieldException {
    this(field);
    setSchema(schemaJson);
    setServerChoiceIndexes(serverChoiceIndexes);
  }

  /**
   * Create an instance for the specified list of schemas. If only one field name is provided, queries will
   * default to the handling of single field queries.
   *
   * @param fields Field names of the JSON fields, may include schema and table name (e.g. tenant1.user_table.json).
   *  Must conform to SQL identifier requirements (characters, not a keyword), or properly quoted using double quotes.
   *  The first field name on the list will be the default field for terms in queries that don't specify a json field.
   * @throws FieldException (subclass of CQL2PgJSONException) - provided field is not valid
   */
  public CQL2PgJSON(List<String> fields) throws FieldException {
    if (fields == null || fields.isEmpty()) {
      throw new FieldException("fields list must not be empty");
    }
    jsonFields = new ArrayList<>();
    for (String field : fields) {
      jsonFields.add(trimNotEmpty(field));
    }
    if (jsonFields.size() == 1) {
      jsonField = jsonFields.get(0);
    }
    schemas = new HashMap<>();
  }

  /**
   * Create an instance for the specified list of schemas. If only one field name is provided, queries will
   * default to the handling of single field queries.
   *
   * @param fields Field names of the JSON fields, may include schema and table name (e.g. tenant1.user_table.json).
   *  Must conform to SQL identifier requirements (characters, not a keyword), or properly quoted using double quotes.
   *  The first field name on the list will be the default field for terms in queries that don't specify a json field.
   * @param serverChoiceIndexes  List of field names, may be empty, must not contain null,
   *                             names must not contain double quote or single quote and must identify the jsonb
   *                             field to which they apply. (e.g. "group_jsonb.patronGroup.group" )
   * @throws FieldException (subclass of CQL2PgJSONException) - provided field is not valid
   * @throws ServerChoiceIndexesException (subclass of CQL2PgJSONException) - provided serverChoiceIndexes is not valid
   */
  public CQL2PgJSON(List<String> fields, List<String> serverChoiceIndexes)
      throws ServerChoiceIndexesException, FieldException {
    this(fields);
    setServerChoiceIndexes(serverChoiceIndexes);
  }

  /**
   * Create an instance for the specified list of schemas. If only one field name is provided, queries will
   * default to the handling of single field queries.
   *
   * @param fieldsAndSchemaJsons Field names of the JSON fields as keys,
   *  JSON String representing the schema of the field the CQL queries against as values.
   *  Field names may include schema and table name, (e.g. tenant1.user_table.json) and must conform to
   *  SQL identifier requirements (characters, not a keyword), or properly quoted using double quotes.
   *  Schemas values may be null if a particular field has no available schema.
   *  The first field name in the map will be the default field for terms in queries that don't specify a json field.
   * @throws IOException if the JSON structure is invalid
   * @throws FieldException (subclass of CQL2PgJSONException) - provided field is not valid
   * @throws SchemaException (subclass of CQL2PgJSONException) provided JSON schema not valid
   */
  public CQL2PgJSON(Map<String,String> fieldsAndSchemaJsons)
      throws FieldException, IOException, SchemaException {
    if (fieldsAndSchemaJsons == null || fieldsAndSchemaJsons.isEmpty()) {
      throw new FieldException( "fields map must not be empty" );
    }
    jsonFields = new ArrayList<>();
    schemas = new HashMap<>();
    for (Entry<String,String> e : fieldsAndSchemaJsons.entrySet()) {
      String field = trimNotEmpty(e.getKey());
      jsonFields.add(field);
      String schemaJson = StringUtils.trimToNull(e.getValue());
      if (schemaJson == null) {
        continue;
      }
      schemas.put(field, new Schema(schemaJson));
    }
    if (jsonFields.size() == 1) {
      jsonField = jsonFields.get(0);
      if (schemas.containsKey(jsonField)) {
        schema = schemas.get(jsonField);
      }
    }
  }

  /**
   * Create an instance for the specified list of schemas.
   *
   * @param fieldsAndSchemaJsons Field names of the JSON fields as keys,
   *   JSON String representing the schema of the field the CQL queries against as values.
   *   Field names may include schema and table name, (e.g. tenant1.user_table.json) and must conform to
   *   SQL identifier requirements (characters, not a keyword), or properly quoted using double quotes.
   *   Schemas values may be null if a particular field has no available schema.
   *  The first field name on the list will be the default field for terms in queries that don't specify a json field.
   * @param serverChoiceIndexes  List of field names, may be empty, must not contain null,
   *                             names must not contain double quote or single quote and must either identify the
   *                             jsonb field to which they apply (e.g. "group_jsonb.patronGroup.group" ) or if
   *                             all included fields have schemas provided they must be entirely unambiguous.
   * @throws IOException if the JSON structure is invalid
   * @throws FieldException (subclass of CQL2PgJSONException) - provided field is not valid
   * @throws SchemaException (subclass of CQL2PgJSONException) provided JSON schema not valid
   * @throws ServerChoiceIndexesException (subclass of CQL2PgJSONException) - provided serverChoiceIndexes is not valid
   */
  public CQL2PgJSON(Map<String,String> fieldsAndSchemaJsons, List<String> serverChoiceIndexes)
      throws FieldException, IOException, SchemaException, ServerChoiceIndexesException  {
    this(fieldsAndSchemaJsons);
    setServerChoiceIndexes(serverChoiceIndexes);
  }

  /**
   * Return field.trim() and ensure that it is not empty.
   *
   * @param field  the field name to trim
   * @return field trimmed
   * @throws FieldException  if field is null or the trimmed field name is empty
   */
  private String trimNotEmpty(String field) throws FieldException {
    if (field == null) {
      throw new FieldException("a field name must not be null");
    }
    String fieldTrimmed = field.trim();
    if (fieldTrimmed.isEmpty()) {
      throw new FieldException("a field name must not be empty");
    }
    return fieldTrimmed;
  }

  /**
   * Set the schema of the field.
   * @param schema  JSON String representing the schema of the field the CQL queries against.
   * @throws IOException if the JSON structure is invalid
   * @throws SchemaException if the JSON is structurally acceptable but doesn't match expected schema
   */
  private void setSchema(String schema) throws IOException, SchemaException {
    this.schema = new Schema( schema );
  }

  /**
   * Set the index names (field names) for cql.serverChoice.
   * @param serverChoiceIndexes       List of field names, may be empty, must not contain null,
   *                                  names must not contain double quote or single quote.
   * @throws ServerChoiceIndexesException if serverChoiceIndexes value(s) are invalid
   */
  public void setServerChoiceIndexes(List<String> serverChoiceIndexes) throws ServerChoiceIndexesException {
    if (serverChoiceIndexes == null) {
      this.serverChoiceIndexes = Collections.emptyList();
      return;
    }
    for (String field : serverChoiceIndexes) {
      if (field == null) {
        throw new ServerChoiceIndexesException("serverChoiceFields must not contain null elements");
      }
      if (field.trim().isEmpty()) {
        throw new ServerChoiceIndexesException("serverChoiceFields must not contain empty field names");
      }
      int pos = field.indexOf('"');
      if (pos >= 0) {
        throw new ServerChoiceIndexesException("field contains double quote at position " + pos+1 + ": " + field);
      }
      pos = field.indexOf('\'');
      if (pos >= 0) {
        throw new ServerChoiceIndexesException("field contains single quote at position " + pos+1 + ": " + field);
      }
    }
    this.serverChoiceIndexes = serverChoiceIndexes;
  }

  /**
   * Convert a CQL query into a SQL query.
   * @param cql  the CQL query
   * @return the SQL query
   * @throws QueryValidationException  when the input violates CQL syntax
   */
  public String cql2pgJson(String cql) throws QueryValidationException {
    try {
      CQLParser parser = new CQLParser();
      CQLNode node = parser.parse(cql);
      return pg(node);
    } catch (IOException|CQLParseException e) {
      throw new QueryValidationException(e);
    }
  }

  private String pg(CQLNode node) throws QueryValidationException {
    if (node instanceof CQLTermNode) {
      return pg((CQLTermNode) node);
    }
    if (node instanceof CQLBooleanNode) {
      return pg((CQLBooleanNode) node);
    }
    if (node instanceof CQLSortNode) {
      return pg((CQLSortNode) node);
    }
    throw new CQLFeatureUnsupportedException("Not implemented yet: " + node.getClass().getName());
  }

  private String pg(CQLSortNode node) throws QueryValidationException {
    StringBuilder order = new StringBuilder();
    order.append(pg(node.getSubtree()))
    .append(" ORDER BY ");
    boolean firstIndex = true;
    for (ModifierSet modifierSet : node.getSortIndexes()) {
      if (firstIndex) {
        firstIndex = false;
      } else {
        order.append(", ");
      }
      String index = modifierSet.getBase();
      if (jsonField != null) {
        order.append(index2sqlJson(jsonField, index));
      } else {
        // multifield
        IndexTextAndJsonValues vals = multiFieldProcessing( index );
        order.append(vals.indexJson);
      }
      CqlModifiers modifiers = new CqlModifiers(modifierSet);
      if (modifiers.cqlSort == CqlSort.DESCENDING) {
        order.append(" DESC");
      }  // ASC not needed, it's Postgres' default
    }
    return order.toString();
  }

  private static String sqlOperator(CQLBooleanNode node) throws CQLFeatureUnsupportedException {
    if (node instanceof CQLAndNode) {
      return "AND";
    }
    if (node instanceof CQLOrNode) {
      return "OR";
    }
    if (node instanceof CQLNotNode) {
      // CQL "NOT" means SQL "AND NOT", see section "7. Boolean Operators" in
      // https://www.loc.gov/standards/sru/cql/spec.html
      return "AND NOT";
    }
    throw new CQLFeatureUnsupportedException("Not implemented yet: " + node.getClass().getName());
  }

  private String pg(CQLBooleanNode node) throws QueryValidationException {
    String operator = sqlOperator(node);
    String isNotTrue = "";

    if ("AND NOT".equals(operator)) {
      operator = "AND (";
      isNotTrue = ") IS NOT TRUE";
      // NOT TRUE is (FALSE or NULL) to catch the NULL case when the field does not exist.
      // This completely inverts the right operand.
    }

    return "(" + pg(node.getLeftOperand()) + ") "
        + operator
        + " (" + pg(node.getRightOperand()) + isNotTrue + ")";
  }

  /**
   * unicode.getEquivalents(c) but with \ and " masked using backslash.
   * @param unicode quivalence to use
   * @param c  character to use
   * @return masked equivalents
   */
  private static String equivalents(Unicode unicode, char c) {
    String s = unicode.getEquivalents(c);
    // JSON requires special quoting of \ and ".
    // The blackslash needs to be doubled for Java, Postgres and JSON each (2*2*2=8)
    if (s.startsWith("[\\")) {  // s == [\﹨＼]
      return "(\\\\|[" + s.substring(2) + ")";
    }
    if (s.startsWith("[\"")) {  // s == ["＂]
      return "(\\\\\"|[" + s.substring(2) + ")";
    }

    return s;
  }

  private static String regexp(Unicode unicode, String s) {
    StringBuilder regexp = new StringBuilder();
    boolean backslash = false;
    for (char c : s.toCharArray()) {
      if (backslash) {
        // Backslash (\) is used to escape '*', '?', quote (") and '^' , as well as itself.
        // Backslash followed by any other characters is an error (see cql spec), but
        // we handle it gracefully matching that character.
        regexp.append(equivalents(unicode, c));
        backslash = false;
        continue;
      }
      switch (c) {
      case '\\':
        backslash = true;
        break;
      case '?':
        regexp.append(WORD_CHARACTER_REGEXP);
        break;
      case '*':
        regexp.append(WORD_CHARACTER_REGEXP + "*");
        break;
      case '^':
        regexp.append("(^|$)");
        break;
      default:
        regexp.append(equivalents(unicode, c));
      }
    }

    if (backslash) {
      // a single backslash at the end is an error but we handle it gracefully matching one.
      regexp.append(equivalents(unicode, '\\'));
    }

    // mask ' used for quoting postgres strings
    return regexp.toString().replace("'", "''");
  }

  private static Unicode unicode(CqlModifiers modifiers) {
    if (modifiers.cqlCase == CqlCase.IGNORE_CASE) {
      if (modifiers.cqlAccents == CqlAccents.IGNORE_ACCENTS) {
        return Unicode.IGNORE_CASE_AND_ACCENTS;
      }
      return Unicode.IGNORE_CASE;
    }
    if (modifiers.cqlAccents == CqlAccents.IGNORE_ACCENTS) {
      return Unicode.IGNORE_ACCENTS;
    }
    return Unicode.IGNORE_NONE;
  }

  private static String regexp(CqlModifiers modifiers, String s) {
    return regexp(unicode(modifiers), s);
  }

  /**
   * Return a POSIX regexp expression for each word in cql. Words are delimited by whitespace.
   * @param modifiers  CqlModifiers to use
   * @param cql   words to convert
   * @return resulting regexps
   */
  private static String [] wordRegexp(CqlModifiers modifiers, String cql) {
    String [] split = cql.trim().split("\\s+");  // split at whitespace
    if (split.length == 1 && "".equals(split[0])) {
      // The variable cql contains whitespace only. honorWhitespace is not implemented yet.
      // So there is no word at all. Therefore no restrictions for matching - anything matches.
      return new String [] { " ~ ''" };  // matches any (existing non-null) value
    }
    Unicode unicode = unicode(modifiers);
    for (int i=0; i<split.length; i++) {
      // A word is delimited by any of: the beginning ^ or the end $ of the field or
      // by punctuation or by whitespace.
      split[i] = " ~ '(^|[[:punct:]]|[[:space:]])"
          + regexp(unicode, split[i])
          + "($|[[:punct:]]|[[:space:]])'";
    }
    return split;
  }

  private static String [] match(CQLTermNode node) throws CQLFeatureUnsupportedException {
    CqlModifiers modifiers = new CqlModifiers(node);
    if (modifiers.cqlMasking != CqlMasking.MASKED) {
      throw new CQLFeatureUnsupportedException("This masking is not implemented yet: " + modifiers.cqlMasking);
    }
    String comparator = node.getRelation().getBase();
    switch (comparator) {
    case "==":
      // accept quotes at beginning and end because JSON string are
      // quoted (but JSON numbers aren't)
      return new String [] {  " ~ '^" + regexp(modifiers, node.getTerm()) + "$'" };
    case "<>":
      return new String [] { " !~ '^" + regexp(modifiers, node.getTerm()) + "$'" };
    case "=":
      return wordRegexp(modifiers, node.getTerm());
    case "<":
    case "<=":
    case ">":
    case ">=":
      return new String [] { comparator + "'" + node.getTerm().replace("'", "''") + "'" };
    default:
      throw new CQLFeatureUnsupportedException("Relation " + node.getRelation().getBase()
          + " not implemented yet: " + node.toString());
    }
  }

  /**
   * Test if s is a JSON number.
   * @param s  String to test
   * @return true if s is a JSON number, false otherwise
   */
  private static boolean isJsonNumber(String s) {
    return jsonNumber.matcher(s).matches();
  }

  /**
   * Returns a numeric match like ">=17" if the node term is a JSON number, null otherwise.
   * @param node  the node to get the comparator operator and the term from
   * @return  the comparison or null
   * @throws CQLFeatureUnsupportedException if cql query attempts to use unsupported operators.
   */
  static String getNumberMatch(CQLTermNode node) throws CQLFeatureUnsupportedException {
    if (! isJsonNumber(node.getTerm())) {
      return null;
    }
    String comparator = node.getRelation().getBase();
    switch (comparator) {
    case "==":
      comparator = "=";
      break;
    case "<>":
    case "=":
    case "<":
    case "<=":
    case ">":
    case ">=":
      break;
    default:
      throw new CQLFeatureUnsupportedException("Relation " + node.getRelation().getBase()
          + " not implemented yet: " + node.toString());
    }
    return comparator + node.getTerm();
  }

  /**
   * Convert index name to SQL term of type text.
   * Example result for field=user and index=foo.bar:
   * user->'foo'->>'bar'
   * @param jsonField
   * @param index name to convert
   *
   * @return SQL term
   */
  private static String index2sqlText(String jsonField, String index) {
    String result = jsonField + "->'" + index.replace(".", "'->'") + "'";
    int lastArrow = result.lastIndexOf("->'");
    return result.substring(0,  lastArrow) + "->>" + result.substring(lastArrow + 2);
  }

  /**
   * Convert index name to SQL term of type json.
   * Example result for field=user and index=foo.bar:
   * user->'foo'->'bar'
   * @param jsonField
   * @param index name to convert
   *
   * @return SQL term
   */
  private static String index2sqlJson(String jsonField, String index) {
    return jsonField + "->'" + index.replace(".", "'->'") + "'";
  }

  /**
   * Create an SQL expression where index is applied to all matches.
   * @param index  index to use
   * @param matches  list of match expressions
   * @param numberMatch  match expression for numeric comparison (null for no numeric comparison)
   * @return SQL expression
   * @throws QueryValidationException
   */
  private String index2sql(String index, String [] matches, String numberMatch) throws QueryValidationException {
    StringBuilder s = new StringBuilder();
    for (String match : matches) {
      if (s.length() > 0) {
        s.append(" AND ");
      }
      IndexTextAndJsonValues vals = new IndexTextAndJsonValues();
      if (jsonField == null) {
        // multiField processing
        vals = multiFieldProcessing( index );
      } else {
        String finalIndex = index;
        if (schema != null) {
          finalIndex = schema.mapFieldNameAgainstSchema(index);
        }
        vals.indexJson = index2sqlJson(jsonField, finalIndex);
        vals.indexText = index2sqlText(jsonField, finalIndex);
      }
      if (numberMatch == null) {
        s.append(vals.indexText).append(match);
      } else {
        /* CASE jsonb_typeof(jsonb->amount)
         * WHEN 'number' then (jsonb->>amount)::numeric = 100
         * ELSE jsonb->>amount ~ '(^|[[:punct:]]|[[:space:]])100($|[[:punct:]]|[[:space:]])'
         * END
         */
        s.append(" CASE jsonb_typeof(").append(vals.indexJson).append(")")
        .append(" WHEN 'number' then (").append(vals.indexText).append(")::numeric ").append(numberMatch)
        .append(" ELSE ").append(vals.indexText).append(match)
        .append(" END");
      }
    }
    if (matches.length <= 1) {
      return s.toString();
    }
    return "(" + s.toString() + ")";
  }

  private IndexTextAndJsonValues multiFieldProcessing( String index ) throws QueryValidationException {
    IndexTextAndJsonValues vals = new IndexTextAndJsonValues();

    // processing for case where index is prefixed with json field name
    for (String field : jsonFields)
      if (index.startsWith(field+'.')) {
        String indexTermWithinField;
        if (schemas.containsKey(field)) {
            indexTermWithinField = schemas.get(field).mapFieldNameAgainstSchema( index.substring(field.length()+1) );
        } else {
            indexTermWithinField = index.substring(field.length()+1);
        }
        vals.indexJson = index2sqlJson(field, indexTermWithinField);
        vals.indexText = index2sqlText(field, indexTermWithinField);
        return vals;
      }

    // if no json field name prefix is found, the default field name gets applied.
    String defaultJsonField = jsonFields.get(0);
    String finalIndex = index;
    if (schemas.containsKey(defaultJsonField)) {
      finalIndex = schemas.get(defaultJsonField).mapFieldNameAgainstSchema(index);
    }
    vals.indexJson = index2sqlJson(defaultJsonField, finalIndex);
    vals.indexText = index2sqlText(defaultJsonField, finalIndex);
    return vals;
  }

  private String pg(CQLTermNode node) throws QueryValidationException {
    String [] matches = match(node);
    String numberMatch = getNumberMatch(node);
    if ("cql.allRecords".equalsIgnoreCase(node.getIndex())) {
      return "true";
    }
    if ("cql.serverChoice".equalsIgnoreCase(node.getIndex())) {
      if (serverChoiceIndexes.isEmpty()) {
        throw new QueryValidationException("cql.serverChoice requested, but no serverChoiceIndexes defined.");
      }
      List<String> sqlPieces = new ArrayList<>();
      for(String index : serverChoiceIndexes) {
        sqlPieces.add(index2sql(index, matches, numberMatch));
      }
      return String.join(" OR ", sqlPieces);
    }
    return index2sql(node.getIndex(), matches, numberMatch);
  }
}
