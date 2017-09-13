package plsql2voltdb;

import org.voltdb.plannodes.NodeSchema;

import plsql_parser.PlSqlParser.ParameterContext;
import plsql_parser.PlSqlParser.Variable_declarationContext;

public class Var {
    private final String m_javaType;
    private final String m_name;
    private final NodeSchema m_schema;

    public static Var fromPlSql(SqlAnalyzer analyzer, ParameterContext ctx) {
        String name = ctx.parameter_name().getText();
        String javaType = TypeTranslator.translate(analyzer, ctx.type_spec());
        return new Var(javaType, name);
    }

    public static Var fromPlSql(SqlAnalyzer analyzer, Variable_declarationContext ctx) {
        String name = ctx.identifier().getText();
        String javaType = TypeTranslator.translate(analyzer, ctx.type_spec());
        return new Var(javaType, name);
    }

    public static Var fromJava(String javaType, String name) {
        assert(!javaType.equals("VoltTable"));
        return new Var(javaType, name);
    }

    public static Var fromSchema(NodeSchema schema, String name) {
        return new Var(schema, name);
    }

    private Var(String javaType, String name) {
        m_javaType = javaType;
        m_name = name;
        m_schema = null;
    }

    private Var(NodeSchema schema, String name) {
        m_javaType = "VoltTable";
        m_name = name;
        m_schema = schema;
    }

    public String getJavaType() {
        return m_javaType;
    }

    public String getName() {
        return m_name;
    }

    public NodeSchema getSchema() {
        return m_schema;
    }
}
