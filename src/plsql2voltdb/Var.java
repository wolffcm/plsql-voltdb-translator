package plsql2voltdb;

import plsql_parser.PlSqlParser.ParameterContext;
import plsql_parser.PlSqlParser.Variable_declarationContext;

public class Var {
    private final String m_javaType;
    private final String m_name;

    public static Var fromPlSql(ParameterContext ctx) {
        String name = ctx.parameter_name().getText();
        String javaType = TypeTranslator.translate(ctx.type_spec());
        return new Var(javaType, name);
    }

    public static Var fromPlSql(Variable_declarationContext ctx) {
        String name = ctx.identifier().getText();
        String javaType = TypeTranslator.translate(ctx.type_spec());
        return new Var(javaType, name);
    }

    public static Var fromJava(String javaType, String name) {
        return new Var(javaType, name);
    }

    private Var(String javaType, String name) {
        m_javaType = javaType;
        m_name = name;

    }

    public String getJavaType() {
        return m_javaType;
    }

    public String getName() {
        return m_name;
    }


}
