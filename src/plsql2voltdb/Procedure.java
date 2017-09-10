package plsql2voltdb;

import java.util.ArrayList;
import java.util.List;

import plsql_parser.PlSqlParser.Create_procedure_bodyContext;
import plsql_parser.PlSqlParser.ParameterContext;


public class Procedure {
    private final Create_procedure_bodyContext m_ctx;

    Procedure(Create_procedure_bodyContext ctx) {
        m_ctx = ctx;
    }

    public String getName() {
        return m_ctx.procedure_name().getText();
    }

    public Create_procedure_bodyContext getCtx() {
        return m_ctx;
    }

    public List<String> getJavaParameters() {
        List<String> params = new ArrayList<>();
        for (ParameterContext pc : m_ctx.parameter()) {
            String javaType = TypeTranslator.translate(pc.type_spec());
            String param = javaType + " " + pc.parameter_name().getText();
            params.add(param);
        }

        return params;
    }

    public List<ParameterContext> getParameters() {
        return m_ctx.parameter();
    }
}
