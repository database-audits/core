package io.github.databaseaudits.audit.finding;

/**
 * A mapped column whose live type is not compatible with the type the JPA entity
 * declares.
 *
 * @param qualifiedTable The mapped table, schema-qualified when the mapping
 *            carries a schema.
 * @param column The column whose type differs.
 * @param foundType The type found in the live schema.
 * @param expectedType The type the mapping expects.
 */
public record SchemaColumnTypeMismatchFinding(String qualifiedTable,
        String column, String foundType, String expectedType)
        implements Finding {
    @Override
    public String description() {
        return "wrong column type in column [" + column + "] in table ["
                + qualifiedTable + "]; found [" + foundType
                + "], but expecting [" + expectedType + "]";
    }
}
