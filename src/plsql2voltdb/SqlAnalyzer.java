package plsql2voltdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.voltdb.VoltType;
import org.voltdb.plannodes.NodeSchema;

import plsql_parser.PlSqlParser.General_elementContext;
import plsql_parser.PlSqlParser.Id_expressionContext;
import plsql_parser.PlSqlParser.Into_clauseContext;
import plsql_parser.PlSqlParser.Type_nameContext;
import plsql_parser.PlSqlParser.Variable_nameContext;
import plsql_parser.PlSqlParserBaseListener;

public class SqlAnalyzer {

    private final StandAlonePlanner m_planner;

    SqlAnalyzer(String ddlPath) throws Exception {
        m_planner = new StandAlonePlanner(ddlPath);
    }

    public static class AnalyzedSqlStmt {
        private final String m_rewrittenStmt;
        private final List<String> m_inputParams;
        private final List<String> m_outputParams;
        private final NodeSchema m_outputSchema;
        private final String m_prefix;

        AnalyzedSqlStmt(
                String rewrittenStmt,
                List<String> inputParams,
                List<String> outputParams,
                NodeSchema outputSchema,
                String prefix) {
            m_rewrittenStmt = rewrittenStmt;
            m_inputParams = inputParams;
            m_outputParams = outputParams;
            m_outputSchema = outputSchema;
            m_prefix = prefix;
        }

        public String getRewrittenStmt() {
            return m_rewrittenStmt;
        }

        public List<String> getInputParams() {
            return m_inputParams;
        }

        public List<String> getOutputParams() {
            return m_outputParams;
        }

        public NodeSchema getOutputSchema() {
            return m_outputSchema;
        }

        public boolean producesSingleIntegerColumn() {

            if (m_outputSchema.size() != 1) {
                return false;
            }

            String outputJavaType = TypeTranslator.translate(m_outputSchema.getColumns().get(0).getType());
            return "long".equals(outputJavaType);
        }

        public String getNamePrefix() {
            return m_prefix;
        }
    }

    private static class SqlAnalyzingListener extends PlSqlParserBaseListener {
        private final TokenStreamRewriter m_rewriter;
        private final List<String> m_outputVariables = new ArrayList<>();
        private final List<String> m_inputVariables = new ArrayList<>();
        private final Map<String, Var> m_visibleVariables;

        SqlAnalyzingListener(TokenStreamRewriter tokenStreamRewriter, Map<String, Var> visibleVariables) {
            m_rewriter = tokenStreamRewriter;
            m_visibleVariables = visibleVariables;
        }

        // Remove the "INTO var1, var2, ..." clause
        @Override
        public void exitInto_clause(Into_clauseContext ctx) {
            m_rewriter.delete(ctx.getStart(), ctx.getStop());
            assert(m_outputVariables.isEmpty());
            for (Variable_nameContext name : ctx.variable_name()) {
                m_outputVariables.add(name.getText());
            }
        }

        // Any references to local variables should get converted to "?"
        @Override
        public void exitGeneral_element(General_elementContext ctx) {
            if (ctx.general_element_part().size() == 1) {
                String id = ctx.getText();
                if (m_visibleVariables.containsKey(id)) {
                    m_rewriter.replace(ctx.getStart(), ctx.getStop(), "?");
                    m_inputVariables.add(id);
                }
            }
        }

        public List<String> getOutputVariables() {
            return m_outputVariables;
        }

        public List<String> getInputVariables() {
            return m_inputVariables;
        }
    }

    public AnalyzedSqlStmt analyze(TokenStream tokenStream, Map<String, Var> visibleVariables, ParserRuleContext sqlStmtCtx) {
        ParseTreeWalker walker = new ParseTreeWalker();
        TokenStreamRewriter rewriter = new TokenStreamRewriter(tokenStream);
        SqlAnalyzingListener listener = new SqlAnalyzingListener(rewriter, visibleVariables);
        walker.walk(listener, sqlStmtCtx);

        String rewrittenSql = rewriter.getText(sqlStmtCtx.getSourceInterval());
        NodeSchema schema = m_planner.planAndGetOutputSchema(rewrittenSql);

        String prefix = getPrefixForName(tokenStream, sqlStmtCtx);
        return new AnalyzedSqlStmt(
                rewrittenSql,
                listener.getInputVariables(),
                listener.getOutputVariables(),
                schema,
                prefix);
    }

    private String getPrefixForName(TokenStream tokenStream, ParserRuleContext sqlStmtCtx) {
        String prefix = "sql";
        int start = sqlStmtCtx.getStart().getTokenIndex();
        int stop = sqlStmtCtx.getStop().getTokenIndex();
        for (int i = start; i <= stop; ++i) {
            // take the first token that is not a parenthesis
            String tok = tokenStream.get(i).getText();
            if (tok.equals("(")) {
                continue;
            }

            prefix = tok.toLowerCase();
            break;
        }
        return prefix;
    }

    public VoltType getTypeForColumn(Type_nameContext typeNameContext) {
        List<Id_expressionContext> ids = typeNameContext.id_expression();
        if (ids.size() != 2) {
            return null;
        }

        return m_planner.getTypeForColumn(ids.get(0).getText(), ids.get(1).getText());
    }
}
