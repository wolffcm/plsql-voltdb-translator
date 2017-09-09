package plsql2voltdb;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import plsql_parser.PlSqlParser;
import plsql_parser.PlSqlParserBaseListener;

public class ProcedureExtractor {

    private static class ProcedureListener extends PlSqlParserBaseListener {
        private final List<Procedure> m_procedures = new ArrayList<>();

        public List<Procedure> getProcedures() {
            return m_procedures;
        }

        @Override
        public void exitCreate_procedure_body(PlSqlParser.Create_procedure_bodyContext ctx) {
            m_procedures.add(new Procedure(ctx.procedure_name().getText()));
        }
    }

    public List<Procedure> extract(ParseTree tree) {

        ParseTreeWalker walker = new ParseTreeWalker();
        ProcedureListener listener = new ProcedureListener();
        walker.walk(listener, tree);

        return listener.getProcedures();
    }

}
