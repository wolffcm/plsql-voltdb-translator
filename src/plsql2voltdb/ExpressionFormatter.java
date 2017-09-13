package plsql2voltdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.voltdb.plannodes.NodeSchema;
import org.voltdb.plannodes.SchemaColumn;

import plsql_parser.PlSqlParser.ExpressionContext;
import plsql_parser.PlSqlParser.General_element_partContext;
import plsql_parser.PlSqlParser.Id_expressionContext;
import plsql_parser.PlSqlParser.Quoted_stringContext;
import plsql_parser.PlSqlParser.Relational_operatorContext;
import plsql_parser.PlSqlParserBaseListener;

public class ExpressionFormatter {

    // This should use TokenStreamRewriter
    private static class ExpressionFormattingListener extends PlSqlParserBaseListener {
        private final Map<String, Var> m_vars;
        private final TokenStreamRewriter m_rewriter;


        ExpressionFormattingListener(Map<String, Var> vars, TokenStreamRewriter rewriter) {
            m_vars = vars;
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

        @Override
        public void exitGeneral_element_part(General_element_partContext ctx) {
            List<Id_expressionContext> idCtxs = ctx.id_expression();
            if (idCtxs.size() == 2) {
                // See if the LHS ID is a VoltTable.  If so
                // Generate an accessor.
                String lhsId = idCtxs.get(0).getText();
                Var v = m_vars.get(lhsId);
                if (v != null && "VoltTable".equals(v.getJavaType())) {
                    String rhsId = idCtxs.get(1).getText();
                    // TODO: compile the SQL statement to figure out
                    // the type of accessor to generate!
                    String accessor = getVoltTableAccessor(v.getSchema(), rhsId);
                    m_rewriter.replace(idCtxs.get(1).getStart(), idCtxs.get(1).getStop(), accessor);
                }

            }
        }
    }

    public static String formatAsJavaString(String rawString) {
        String lines[] = rawString.split("\n");

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

    public static String format(TokenStream tokenStream, Map<String, Var> vars, ExpressionContext condition) {
        TokenStreamRewriter rewriter = new TokenStreamRewriter(tokenStream);
        ParseTreeWalker walker = new ParseTreeWalker();
        ExpressionFormattingListener listener = new ExpressionFormattingListener(vars, rewriter);
        walker.walk(listener, condition);

        return rewriter.getText(condition.getSourceInterval());
    }

    public static String getVoltTableAccessor(NodeSchema outputSchema, int index) {
        String accessor = null;
        SchemaColumn column = outputSchema.getColumns().get(index);
        String javaType = TypeTranslator.translate(column.getType());
        if ("long".equals(javaType)) {
            accessor = "getLong(" + index + ")";
        }
        else if ("String".equals(javaType)) {
            accessor = "getString(" + index + ")";
        }

        return accessor;
    }

    private static String getVoltTableAccessor(NodeSchema outputSchema, String colName) {
        String accessor = null;
        int index = 0;
        // Could be duplicate column names.. in which case we would want to throw an error?
        for (SchemaColumn col : outputSchema.getColumns()) {
            if (col.getColumnAlias().equalsIgnoreCase(colName)) {
                break;
            }

            ++index;
        }

        SchemaColumn column = outputSchema.getColumns().get(index);
        String javaType = TypeTranslator.translate(column.getType());
        if ("long".equals(javaType)) {
            accessor = "getLong(" + index + ")";
        }
        else if ("String".equals(javaType)) {
            accessor = "getString(" + index + ")";
        }

        return accessor;
    }
}
