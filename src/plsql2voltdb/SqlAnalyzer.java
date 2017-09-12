package plsql2voltdb;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import plsql_parser.PlSqlParser.Into_clauseContext;
import plsql_parser.PlSqlParser.Sql_statementContext;
import plsql_parser.PlSqlParser.Variable_nameContext;
import plsql_parser.PlSqlParserBaseListener;

public class SqlAnalyzer {


    public static class AnalyzedSqlStmt {
        private final String m_rewrittenStmt;
        private final List<String> m_inputParams;
        private final List<String> m_outputParams;



        AnalyzedSqlStmt(String rewrittenStmt, List<String> inputParams, List<String> outputParams) {
            m_rewrittenStmt = rewrittenStmt;
            m_inputParams = inputParams;
            m_outputParams = outputParams;
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
    }

    private static class SqlAnalyzingListener extends PlSqlParserBaseListener {
        private final TokenStreamRewriter m_rewriter;
        private final List<String> m_outputVariables = new ArrayList<>();

        SqlAnalyzingListener(TokenStreamRewriter tokenStreamRewriter) {
            m_rewriter = tokenStreamRewriter;
        }

        @Override
        public void exitInto_clause(Into_clauseContext ctx) {
            m_rewriter.delete(ctx.getStart(), ctx.getStop());
            assert(m_outputVariables.isEmpty());
            for (Variable_nameContext name : ctx.variable_name()) {
                m_outputVariables.add(name.getText());
            }
        }

        public List<String> getOutputVariables() {
            return m_outputVariables;
        }
    }

    private static String formatAsJavaString(String text) {
        String lines[] = text.split("\n");

        String concat = "";
        List<String> stringLines = new ArrayList<>();
        for (int i = 0; i < lines.length; ++i) {
            String line = lines[i];
            line = line.trim();
            if (! line.isEmpty()) {
                line = line.replaceAll("\"", "\\\"");
                String stringifiedLine = concat + "\"" + line;
                if (i != lines.length - 1) {
                    stringifiedLine += " \"";
                } else {
                    stringifiedLine += "\"";
                }
                stringLines.add(stringifiedLine);
                concat = "    + ";
            }
        }
        return String.join("\n", stringLines);
    }

    public static AnalyzedSqlStmt analyze(TokenStream tokenStream, Sql_statementContext sqlStmtCtx) {
        ParseTreeWalker walker = new ParseTreeWalker();
        TokenStreamRewriter rewriter = new TokenStreamRewriter(tokenStream);
        SqlAnalyzingListener listener = new SqlAnalyzingListener(rewriter);
        walker.walk(listener, sqlStmtCtx);

        String rewrittenSql = formatAsJavaString(rewriter.getText(sqlStmtCtx.getSourceInterval()));

        return new AnalyzedSqlStmt(rewrittenSql, null, listener.getOutputVariables());
    }
}
