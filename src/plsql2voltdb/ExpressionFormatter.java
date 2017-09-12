package plsql2voltdb;

import java.util.Stack;

import org.antlr.v4.runtime.tree.ParseTreeWalker;

import plsql_parser.PlSqlParser.AtomContext;
import plsql_parser.PlSqlParser.ExpressionContext;
import plsql_parser.PlSqlParser.Relational_expressionContext;
import plsql_parser.PlSqlParserBaseListener;

public class ExpressionFormatter {

    // This should use TokenStreamRewriter
    private static class ExpressionFormattingListener extends PlSqlParserBaseListener {
        private Stack<String> m_stack = new Stack<>();

        @Override
        public void exitAtom(AtomContext ctx) {
            m_stack.push(ctx.getText());
        }

        @Override
        public void exitRelational_expression(Relational_expressionContext ctx) {
            if (ctx.relational_operator() != null) {
                assert(m_stack.size() >= 2);
                String javaRelop = null;
                String plsqlRelop = ctx.relational_operator().getText();
                if ("=".equals(plsqlRelop)) {
                    javaRelop = "==";
                }

                assert(javaRelop != null);
                String rhs = m_stack.pop();
                String lhs = m_stack.pop();
                m_stack.push(lhs + " " + javaRelop + " " + rhs);
            }
        }

        public String formattedExpression() {
            assert(m_stack.size() == 1);
            return m_stack.pop();
        }
    }

    public static String format(ExpressionContext condition) {
        ParseTreeWalker walker = new ParseTreeWalker();
        ExpressionFormattingListener listener = new ExpressionFormattingListener();
        walker.walk(listener, condition);

        return listener.formattedExpression();
    }

}
