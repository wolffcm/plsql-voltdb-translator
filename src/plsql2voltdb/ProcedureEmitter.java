package plsql2voltdb;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.TreeMap;

import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupFile;

import plsql2voltdb.SqlAnalyzer.AnalyzedSqlStmt;
import plsql_parser.PlSqlParser;
import plsql_parser.PlSqlParser.Assignment_statementContext;
import plsql_parser.PlSqlParser.BodyContext;
import plsql_parser.PlSqlParser.Create_procedure_bodyContext;
import plsql_parser.PlSqlParser.If_statementContext;
import plsql_parser.PlSqlParser.ParameterContext;
import plsql_parser.PlSqlParser.Return_statementContext;
import plsql_parser.PlSqlParser.Seq_of_statementsContext;
import plsql_parser.PlSqlParser.Sql_statementContext;
import plsql_parser.PlSqlParser.Variable_declarationContext;
import plsql_parser.PlSqlParserBaseListener;

public class ProcedureEmitter {
    private final String m_package;
    private final String m_targetDirectory;
    private final STGroupFile m_templateGroup = new STGroupFile("string-templates/voltdb-procedure.stg");
    private final TokenStream m_tokenStream;

    public ProcedureEmitter(String targetDirectory, String packageName, TokenStream tokenStream) {
        m_package = packageName;
        m_targetDirectory = targetDirectory;
        m_tokenStream = tokenStream;
    }

    private static String getTimeString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        return sdf.format(new Date());
    }

    private class EmittingListener extends PlSqlParserBaseListener {
        List<ParameterContext> m_inputParameters = new ArrayList<>();
        ParameterContext m_outputParameter = null;
        Map<String, String> m_sqlStmts = new TreeMap<>();

        List<Variable_declarationContext> m_constants = new ArrayList<>();
        List<Variable_declarationContext> m_localVariables = new ArrayList<>();

        Stack<Deque<ST>> m_stmtBlockStack = new Stack<>();


        String getOutputParameterName() {
            return m_outputParameter.parameter_name().getText();
        }

        @Override
        public void enterCreate_procedure_body(Create_procedure_bodyContext ctx) {
            m_stmtBlockStack.push(new ArrayDeque<>());
        }

        @Override
        public void exitParameter(ParameterContext ctx) {
            assert(ctx.INOUT().isEmpty());
            if (!ctx.OUT().isEmpty()) {
                assert (ctx.IN().isEmpty());
                assert (m_outputParameter == null);
                m_outputParameter = ctx;
            }
            else {
                m_inputParameters.add(ctx);
            }
        }

        @Override
        public void exitVariable_declaration(Variable_declarationContext ctx) {
            if (ctx.CONSTANT() != null) {
                m_constants.add(ctx);
            }
            else {
                m_localVariables.add(ctx);
            }
        }

        @Override
        public void exitSql_statement(Sql_statementContext ctx) {
            AnalyzedSqlStmt analyzedStmt = SqlAnalyzer.analyze(m_tokenStream, ctx);

            String stmtName = "sql" + m_sqlStmts.size();
            m_sqlStmts.put(stmtName, analyzedStmt.getRewrittenStmt());

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
        public void enterSeq_of_statements(Seq_of_statementsContext ctx) {
            m_stmtBlockStack.push(new ArrayDeque<>());
        }

        @Override
        public void exitBody(BodyContext ctx) {
            Deque<ST> stmts = m_stmtBlockStack.pop();
            ST slist = m_templateGroup.getInstanceOf("slist");
            slist.add("stmts", stmts);
            m_stmtBlockStack.peek().add(slist);
        }


        @Override
        public void exitIf_statement(If_statementContext ctx) {
            // For an if statement, get the seq_of_statements
            Deque<ST> thenStmts = m_stmtBlockStack.pop();
            ST stmtList = m_templateGroup.getInstanceOf("slist");
            stmtList.add("stmts", thenStmts);

            ST ifStmt = m_templateGroup.getInstanceOf("if_stmt");
            String cond = ExpressionFormatter.format(ctx.condition().expression());
            ifStmt.add("cond", cond);
            ifStmt.add("then_block", stmtList);
            m_stmtBlockStack.peek().add(ifStmt);
        }

        private ST getVarDeclST(Variable_declarationContext varDeclCtx) {
            ST varDecl = m_templateGroup.getInstanceOf("variable_decl");
            varDecl.add("var_type", TypeTranslator.translate(varDeclCtx.type_spec()));
            varDecl.add("var_name", varDeclCtx.identifier().getText());

            if (varDeclCtx.default_value_part() != null) {
                varDecl.add("init", ExpressionFormatter.format(varDeclCtx.default_value_part().expression()));
            }

            if (varDeclCtx.CONSTANT() != null) {
                // Create a constant declaration
                varDecl.add("is_static", true);
                varDecl.add("is_final", true);

            }

            return varDecl;
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

            for (Variable_declarationContext varDecl : m_constants) {
                ST decl = getVarDeclST(varDecl);
                classDef.add("constant_decls", decl);
            }

            ST runMethod = m_templateGroup.getInstanceOf("run_method");
            runMethod.add("ret_type", TypeTranslator.translate(m_outputParameter.type_spec()));
            for (ParameterContext param : m_inputParameters) {
                String javaType = TypeTranslator.translate(param.type_spec());
                String paramName = param.parameter_name().getText();
                runMethod.addAggr("args.{ type, name }", javaType, paramName);
            }

            assert(m_stmtBlockStack.size() == 1);
            Deque<ST> methodStmts = m_stmtBlockStack.pop();
            assert(methodStmts.size() == 1); // should be just one stmt list

            // Add the final return statement
            ST returnStmt = m_templateGroup.getInstanceOf("return_stmt");
            returnStmt.add("ret_val", getOutputParameterName());
            methodStmts.peek().add("stmts", returnStmt);

            runMethod.add("stmts", methodStmts);

            for (Map.Entry<String, String> mapEntry : m_sqlStmts.entrySet()) {
                ST sqlStmtST = m_templateGroup.getInstanceOf("sql_stmt");
                sqlStmtST.add("name", mapEntry.getKey());
                sqlStmtST.add("java_string", mapEntry.getValue());
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
