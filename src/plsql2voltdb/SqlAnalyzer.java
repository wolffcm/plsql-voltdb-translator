package plsql2voltdb;

import java.util.List;

import org.antlr.v4.runtime.TokenStream;

import plsql_parser.PlSqlParser.Sql_statementContext;

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

    public static AnalyzedSqlStmt analyze(TokenStream m_tokenStream, Sql_statementContext ctx) {
        // TODO Auto-generated method stub
        return null;
    }

}
