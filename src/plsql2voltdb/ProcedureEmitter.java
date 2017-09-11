package plsql2voltdb;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.TreeMap;

import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupFile;

import plsql_parser.PlSqlParser;
import plsql_parser.PlSqlParser.Assignment_statementContext;
import plsql_parser.PlSqlParser.BodyContext;
import plsql_parser.PlSqlParser.Create_procedure_bodyContext;
import plsql_parser.PlSqlParser.ParameterContext;
import plsql_parser.PlSqlParser.Return_statementContext;
import plsql_parser.PlSqlParser.Sql_statementContext;
import plsql_parser.PlSqlParserBaseListener;

public class ProcedureEmitter {
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
        List<ParameterContext> m_parameters = new ArrayList<>();
        Stack<List<ST>> m_stmtBlockStack = new Stack<>();
        Map<String, String> m_sqlStmts = new TreeMap<>();

        String getOutputParameterName() {
            String name = null;
            for (ParameterContext param : m_parameters) {
                if (! param.OUT().isEmpty()) {
                    assert(name == null);
                    name = param.parameter_name().getText();
                }
            }

            assert (name != null);
            return name;
        }

        @Override
        public void enterCreate_procedure_body(Create_procedure_bodyContext ctx) {
            m_stmtBlockStack.push(new ArrayList<>());
        }

        @Override
        public void exitParameter(ParameterContext ctx) {
            m_parameters.add(ctx);
        }

        @Override
        public void exitSql_statement(Sql_statementContext ctx) {
            String stmtName = "sql" + m_sqlStmts.size();
            m_sqlStmts.put(stmtName, ctx.getText());

            ST queueSql = m_templateGroup.getInstanceOf("queue_sql_stmt");
            queueSql.add("stmt_name", stmtName);
            m_stmtBlockStack.peek().add(queueSql);

            ST execSql = m_templateGroup.getInstanceOf("execute_sql_stmt");
            execSql.add("variable_name", "vt");
            m_stmtBlockStack.peek().add(execSql);
        }

        @Override
        public void exitAssignment_statement(Assignment_statementContext ctx) {
            ST assignStmt = m_templateGroup.getInstanceOf("assignment_stmt");
            assignStmt.add("lhs", ctx.general_element().getText());
            assignStmt.add("rhs", ctx.expression().getText());
            m_stmtBlockStack.peek().add(assignStmt);
        }

        @Override
        public void exitReturn_statement(Return_statementContext ctx) {
            ST returnStmt = m_templateGroup.getInstanceOf("return_stmt");
            returnStmt.add("ret_val", getOutputParameterName());
            m_stmtBlockStack.peek().add(returnStmt);
        }

        @Override
        public void enterBody(BodyContext ctx) {
            m_stmtBlockStack.push(new ArrayList<>());
        }

        @Override
        public void exitBody(BodyContext ctx) {
            List<ST> stmts = m_stmtBlockStack.pop();
            ST slist = m_templateGroup.getInstanceOf("slist");
            slist.add("stmts", stmts);
            m_stmtBlockStack.peek().add(slist);
        }

        @Override
        public void exitCreate_procedure_body(PlSqlParser.Create_procedure_bodyContext ctx) {
            String className = ctx.procedure_name().getText();
            ST srcFile = m_templateGroup.getInstanceOf("src_file");
            srcFile.add("header_comment",
                    "/**\n"
                    + " * " + className + ".java\n"
                    + " *\n"
                    + " * Generated from PL/SQL code\n"
                    + " * by plsqltranslator\n"
                    + " * on " + getTimeString() + "\n"
                    + " */");
            srcFile.add("package", m_package);

            ST classDef = m_templateGroup.getInstanceOf("class_def");
            classDef.add("name", className);

            ST runMethod = m_templateGroup.getInstanceOf("run_method");
            runMethod.add("ret_type", "long");
            for (ParameterContext param : ctx.parameter()) {
                String javaType = TypeTranslator.translate(param.type_spec());
                String paramName = param.parameter_name().getText();
                runMethod.addAggr("args.{ type, name }", javaType, paramName);
            }

            assert(m_stmtBlockStack.size() == 1);
            List<ST> methodStmts = m_stmtBlockStack.pop();
            assert(methodStmts.size() == 1); // should be just one stmt list

            // Add the final return statement
            ST returnStmt = m_templateGroup.getInstanceOf("return_stmt");
            returnStmt.add("ret_val", getOutputParameterName());
            methodStmts.get(0).add("stmts", returnStmt);

            runMethod.add("stmts", methodStmts);

            for (Map.Entry<String, String> mapEntry : m_sqlStmts.entrySet()) {
                ST sqlStmtST = m_templateGroup.getInstanceOf("sql_stmt");
                sqlStmtST.add("name", mapEntry.getKey());
                sqlStmtST.add("sql_text", mapEntry.getValue());
                classDef.add("sql_stmts", sqlStmtST);
            }

            classDef.add("methods", runMethod);

            srcFile.add("class_def", classDef);
            System.out.println("-------- " + m_targetDirectory + "/"
                    + m_package + "/" + className + ".java");
            System.out.println(srcFile.render());
        }

    }
    public void emit(ParseTree tree) {
        ParseTreeWalker walker = new ParseTreeWalker();
        EmittingListener listener = new EmittingListener();
        walker.walk(listener, tree);
    }
}
