package dev.bre.facts;

import dev.bre.engine.bel.check.BelType;
import dev.bre.engine.bel.check.SymbolTable;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link SymbolTable} backed by a resolved fact schema, parameter set and reference lists.
 *
 * <p>The bridge the layering requires: {@code bre-engine-core} declares what the type checker may
 * ask (and so stays free of Jackson and of any schema document), and this supplies the answers from
 * the asset snapshot the compiler resolved at pipeline stage 2.
 *
 * <p>Parameters and lists are passed as type maps rather than values. The checker needs to know
 * that {@code param.INDEBTEDNESS_CAP} is a Decimal; it must not know that it is 200000, because a
 * policy that type-checked differently against different parameter values would not be a policy.
 */
public final class FactSchemaSymbols implements SymbolTable {

  private final FactSchema schema;
  private final Map<String, BelType> parameters;
  private final Map<String, BelType> referenceLists;
  private final Map<String, BelType> variables;
  private final Map<String, Map<String, BelType>> componentOutputs;

  /**
   * Creates a symbol table.
   *
   * @param schema the resolved fact schema
   * @param parameters parameter name to type, from the pinned parameter set
   * @param referenceLists list name to its list type, from the pinned reference lists
   * @param variables the policy's declared variables and their declared types
   * @param componentOutputs component code to its outputs and their declared types
   */
  public FactSchemaSymbols(
      FactSchema schema,
      Map<String, BelType> parameters,
      Map<String, BelType> referenceLists,
      Map<String, BelType> variables,
      Map<String, Map<String, BelType>> componentOutputs) {
    this.schema = schema;
    this.parameters = Map.copyOf(parameters);
    this.referenceLists = Map.copyOf(referenceLists);
    this.variables = Map.copyOf(variables);
    this.componentOutputs = Map.copyOf(componentOutputs);
  }

  /**
   * A symbol table over a schema alone, with no parameters, lists, variables or outputs.
   *
   * @param schema the fact schema
   * @return the table
   */
  public static FactSchemaSymbols of(FactSchema schema) {
    return new FactSchemaSymbols(schema, Map.of(), Map.of(), Map.of(), Map.of());
  }

  @Override
  public Optional<BelType> factPathType(String path) {
    return schema.field(path).flatMap(field -> BelType.of(field.belType()));
  }

  @Override
  public Optional<BelType> parameterType(String name) {
    return Optional.ofNullable(parameters.get(name));
  }

  @Override
  public Optional<BelType> referenceListType(String name) {
    return Optional.ofNullable(referenceLists.get(name));
  }

  @Override
  public Optional<BelType> variableType(String name) {
    return Optional.ofNullable(variables.get(name));
  }

  @Override
  public Optional<BelType> componentOutputType(String code, String output) {
    return Optional.ofNullable(componentOutputs.get(code)).map(outputs -> outputs.get(output));
  }

  @Override
  public boolean isProtected(String path) {
    return schema.field(path).filter(FactSchema.Field::isProtected).isPresent();
  }

  @Override
  public boolean isAadhaar(String path) {
    return schema.field(path).filter(FactSchema.Field::isAadhaar).isPresent();
  }

  @Override
  public Set<String> enumValues(String path) {
    return schema.field(path).map(FactSchema.Field::enumValues).orElse(Set.of());
  }
}
