package plsql2voltdb;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import plsql_parser.PlSqlParser.Cursor_loop_paramContext;
import plsql_parser.PlSqlParser.If_statementContext;
import plsql_parser.PlSqlParser.Loop_statementContext;
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
        private List<ParameterContext> m_inputParameters = new ArrayList<>();
        private ParameterContext m_outputParameter = null;
        private Map<String, String> m_sqlStmts = new TreeMap<>();

        private List<Variable_declarationContext> m_constants = new ArrayList<>();
        private List<Variable_declarationContext> m_localVariables = new ArrayList<>();

        private Stack<List<ST>> m_stmtBlockStack = new Stack<>();


        String getOutputParameterName() {
            return m_outputParameter.parameter_name().getText();
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

        // Could memo-ize this at some point...
        private Set<String> getVisibleVariables() {
            Set<String> vars = new HashSet<>();

            for (ParameterContext paramCtx : m_inputParameters) {
                vars.add(paramCtx.parameter_name().getText());
            }

            vars.add(m_outputParameter.parameter_name().getText());

            for (Variable_declarationContext varCtx : m_constants) {
                vars.add(varCtx.identifier().getText());
            }

            for (Variable_declarationContext varCtx : m_localVariables) {
                vars.add(varCtx.identifier().getText());
            }

            return vars;
        }

        @Override
        public void exitSql_statement(Sql_statementContext ctx) {
            AnalyzedSqlStmt analyzedStmt = SqlAnalyzer.analyze(m_tokenStream, getVisibleVariables(), ctx);

            String stmtName = "sql" + m_sqlStmts.size();
            m_sqlStmts.put(stmtName, analyzedStmt.getRewrittenStmt());

            ST queueSql = m_templateGroup.getInstanceOf("queue_sql_stmt");
            queueSql.add("stmt_name", stmtName);
            for (String inputParam : analyzedStmt.getInputParams()) {
                queueSql.add("params", inputParam);
            }
            m_stmtBlockStack.peek().add(queueSql);

            ST execSql = m_templateGroup.getInstanceOf("execute_sql_stmt");
            execSql.add("variable_name", "vt");
            m_stmtBlockStack.peek().add(execSql);

            // Now assign the result of the SQL execution to the variables.
            if (analyzedStmt.getOutputParams().size() > 0) {
                // TODO: optimize to a simple "asScalarLong()"
                // if only one output of integral type.
                ST advance = m_templateGroup.getInstanceOf("freeform_line");
                advance.add("text", "vt.advanceRow();");
                m_stmtBlockStack.peek().add(advance);
                int i = 0;
                for (String var : analyzedStmt.getOutputParams()) {
                    ST assign = m_templateGroup.getInstanceOf("assignment_stmt");
                    assign.add("lhs", var);
                    // TODO: create the right getter method based on type.
                    assign.add("rhs", "vt.getLong(" + i + ")");
                    m_stmtBlockStack.peek().add(assign);
                    ++i;
                }
            }
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
            m_stmtBlockStack.push(new ArrayList<>());
        }

        @Override
        public void exitIf_statement(If_statementContext ctx) {
            // For an if statement, get the seq_of_statements
            List<ST> thenStmts = m_stmtBlockStack.pop();
            ST stmtList = m_templateGroup.getInstanceOf("slist");
            stmtList.add("stmts", thenStmts);

            ST ifStmt = m_templateGroup.getInstanceOf("if_stmt");
            String cond = ExpressionFormatter.format(m_tokenStream, ctx.condition().expression());
            ifStmt.add("cond", cond);
            ifStmt.add("then_block", stmtList);
            m_stmtBlockStack.peek().add(ifStmt);
        }

        @Override
        public void exitLoop_statement(Loop_statementContext ctx) {
            /*
             * FOR <row-var> IN (<sql-stmt>)
             * LOOP
             *     ... <row-var>.<field> ...
             * END LOOP;
             *
             *  -->
             *
             * VoltTable <row-var>;
             * voltQueueSQL(<sql-stmt>);
             * <row-var> = voltExecuteSQL()[0];
             * while (<row-var>.advanceRow()) {
             *     ...<row-var>.get<String | Long>("<field>")...
             * }
             */

            Cursor_loop_paramContext cursorLoopParam = ctx.cursor_loop_param();
            assert(cursorLoopParam != null);

            // Add the SQL statement to the statements used by the procedure
            AnalyzedSqlStmt analyzedStmt = SqlAnalyzer.analyze(m_tokenStream, getVisibleVariables(), cursorLoopParam.select_statement());
            String stmtName = "sql" + m_sqlStmts.size();
            m_sqlStmts.put(stmtName, analyzedStmt.getRewrittenStmt());

            // Pop the loop body off of the stack.
            List<ST> loopBody = m_stmtBlockStack.pop();
            ST stmtList = m_templateGroup.getInstanceOf("slist");
            stmtList.add("stmts", loopBody);

            // VoltTable declaration
            String rowVarName = cursorLoopParam.record_name().getText();
            ST rowVarDecl = m_templateGroup.getInstanceOf("variable_decl");
            rowVarDecl.add("var_type", "VoltTable");
            rowVarDecl.add("var_name", rowVarName);
            m_stmtBlockStack.peek().add(rowVarDecl);

            ST queueSql = m_templateGroup.getInstanceOf("queue_sql_stmt");
            queueSql.add("stmt_name", stmtName);
            queueSql.add("params", analyzedStmt.getInputParams());
            m_stmtBlockStack.peek().add(queueSql);

            ST executeSql = m_templateGroup.getInstanceOf("execute_sql_stmt");
            executeSql.add("variable_name", rowVarName);
            m_stmtBlockStack.peek().add(executeSql);

            ST whileStmt = m_templateGroup.getInstanceOf("while_stmt");
            whileStmt.add("cond", rowVarName + "." + "advanceRow()");
            whileStmt.add("body", stmtList);
            m_stmtBlockStack.peek().add(whileStmt);
        }

        private ST getVarDeclST(Variable_declarationContext varDeclCtx) {
            ST varDecl = m_templateGroup.getInstanceOf("variable_decl");
            varDecl.add("var_type", TypeTranslator.translate(varDeclCtx.type_spec()));
            varDecl.add("var_name", varDeclCtx.identifier().getText());

            if (varDeclCtx.default_value_part() != null) {
                varDecl.add("init", ExpressionFormatter.format(m_tokenStream, varDeclCtx.default_value_part().expression()));
            }

            if (varDeclCtx.CONSTANT() != null) {
                // Create a constant declaration
                varDecl.add("is_static", true);
                varDecl.add("is_final", true);

            }

            return varDecl;
        }

        private ST getVarDeclST(ParameterContext parCtx) {
            ST varDecl = m_templateGroup.getInstanceOf("variable_decl");
            varDecl.add("var_type", TypeTranslator.translate(parCtx.type_spec()));
            varDecl.add("var_name", parCtx.parameter_name().getText());

            return varDecl;
        }

        private ST getVarDeclST(String javaType, String varName, String init) {
            ST varDecl = m_templateGroup.getInstanceOf("variable_decl");
            varDecl.add("var_type", javaType);
            varDecl.add("var_name", varName);
            varDecl.add("init", init);

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
            // There is one ST for each statement
            List<ST> methodStmts = m_stmtBlockStack.pop();

            // Add the output variable declaration to the beginning
            List<ST> allStmts = new ArrayList<>();
            allStmts.add(getVarDeclST(m_outputParameter));
            allStmts.add(getVarDeclST("VoltTable", "vt", null));
            for (Variable_declarationContext varDeclCtx : m_localVariables) {
                allStmts.add(getVarDeclST(varDeclCtx));
            }

            allStmts.add(m_templateGroup.getInstanceOf("empty_line"));

            // Add the final return statement
            ST returnStmt = m_templateGroup.getInstanceOf("return_stmt");
            returnStmt.add("ret_val", getOutputParameterName());
            methodStmts.add(returnStmt);
            allStmts.addAll(methodStmts);

            ST slist = m_templateGroup.getInstanceOf("slist");
            slist.add("stmts", allStmts);
            runMethod.add("stmts", slist);

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
