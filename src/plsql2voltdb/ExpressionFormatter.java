package plsql2voltdb;

import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import plsql_parser.PlSqlParser.ExpressionContext;
import plsql_parser.PlSqlParser.Quoted_stringContext;
import plsql_parser.PlSqlParser.Relational_operatorContext;
import plsql_parser.PlSqlParserBaseListener;

public class ExpressionFormatter {

    // This should use TokenStreamRewriter
    private static class ExpressionFormattingListener extends PlSqlParserBaseListener {
        private final TokenStreamRewriter m_rewriter;

        ExpressionFormattingListener(TokenStreamRewriter rewriter) {
            m_rewriter = rewriter;
        }

        @Override
        public void exitRelational_operator(Relational_operatorContext ctx) {
            String newOp = null;
            if ("=".equals(ctx.getText())) {
                newOp = "==";
            }
            else if (ctx.not_equal_op() != null) {
                newOp = "!=";
            }
            else if (ctx.less_than_or_equals_op() != null) {
                // PL/SQL apparently allows whitespace between
                // < and =
                newOp = "<=";
            }
            else if (ctx.greater_than_or_equals_op() != null) {
                newOp = ">=";
            }

            if (newOp != null) {
                m_rewriter.replace(ctx.getStart(), ctx.getStop(), newOp);
            }
        }

        @Override
        public void exitQuoted_string(Quoted_stringContext ctx) {
            String orig = ctx.getText();
            int len = orig.length();

            // For now, just replace delimiting ' with "
            // To do this right we'd need to handle escaped embedded quotes, etc.
            assert(orig.charAt(0) == '\'');
            assert(orig.charAt(len - 1) == '\'');

            String newString = "\"" + orig.substring(1, len - 1) + "\"";
            m_rewriter.replace(ctx.getStart(), ctx.getStop(), newString);
        }
    }

    public static String format(TokenStream tokenStream, ExpressionContext condition) {
        TokenStreamRewriter rewriter = new TokenStreamRewriter(tokenStream);
        ParseTreeWalker walker = new ParseTreeWalker();
        ExpressionFormattingListener listener = new ExpressionFormattingListener(rewriter);
        walker.walk(listener, condition);

        return rewriter.getText(condition.getSourceInterval());
    }

}
