package plsql2voltdb;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Stack;

import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupFile;

import plsql_parser.PlSqlParser;
import plsql_parser.PlSqlParser.Create_procedure_bodyContext;
import plsql_parser.PlSqlParser.Null_statementContext;
import plsql_parser.PlSqlParser.ParameterContext;
import plsql_parser.PlSqlParser.Seq_of_statementsContext;
import plsql_parser.PlSqlParserBaseListener;

public class ProcedureEmitter {
    private static final String[] PACKAGES = {
            "org.voltdb.VoltProcedure",
            "org.voltdb.VoltTable"
    };

    private final String m_package;
    private final String m_targetDirectory;
    private final STGroupFile m_templateGroup = new STGroupFile("string-templates/voltdb-procedure.stg");

    public ProcedureEmitter(String targetDirectory, String packageName) {
        m_package = packageName;
        m_targetDirectory = targetDirectory;
    }

    private static String getTimeString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        return sdf.format(new Date());
    }

    private class EmittingListener extends PlSqlParserBaseListener {
        Stack<List<ST>> m_stmtBlockStack = new Stack<>();

        @Override
        public void enterCreate_procedure_body(Create_procedure_bodyContext ctx) {
            m_stmtBlockStack.push(new ArrayList<>());
        }

        @Override
        public void enterSeq_of_statements(Seq_of_statementsContext ctx) {
            m_stmtBlockStack.push(new ArrayList<>());
        }

        @Override
        public void exitSeq_of_statements(Seq_of_statementsContext ctx) {
            List<ST> stmts = m_stmtBlockStack.pop();
            ST slist = m_templateGroup.getInstanceOf("slist");
            slist.add("stmts", stmts);
            m_stmtBlockStack.peek().add(slist);
        }

        @Override
        public void exitNull_statement(Null_statementContext ctx) {
            m_stmtBlockStack.peek().add(m_templateGroup.getInstanceOf("null_stmt"));
        }

        @Override
        public void exitCreate_procedure_body(PlSqlParser.Create_procedure_bodyContext ctx) {
            String className = ctx.procedure_name().getText();
            ST srcFile = m_templateGroup.getInstanceOf("src_file");
            srcFile.add("header_comment",
                    "/**\n"
                    + " * " + className + ".java\n"
                    + " *\n"
                    + " * Generated from PL/SQL code by plsqltranslator on " + getTimeString() + "\n"
                    + " */");
            srcFile.add("package", m_package);
            srcFile.add("imported_pkgs", PACKAGES);

            ST classDef = m_templateGroup.getInstanceOf("class_def");
            classDef.add("name", ctx.procedure_name().getText());

            ST runMethod = m_templateGroup.getInstanceOf("run_method");
            runMethod.add("ret_type", "long");
            for (ParameterContext param : ctx.parameter()) {
                String javaType = TypeTranslator.translate(param.type_spec());
                String paramName = param.parameter_name().getText();
                runMethod.addAggr("args.{ type, name }", javaType, paramName);
            }

            assert(m_stmtBlockStack.size() == 1);
            runMethod.add("stmts", m_stmtBlockStack.pop());

            classDef.add("members", runMethod);

            srcFile.add("class_def", classDef);
            System.out.println("--------");
            System.out.println(srcFile.render());
        }

    }
    public void emit(ParseTree tree) {
        ParseTreeWalker walker = new ParseTreeWalker();
        EmittingListener listener = new EmittingListener();
        walker.walk(listener, tree);
    }
}
