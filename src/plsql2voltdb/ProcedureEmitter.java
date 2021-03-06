package plsql2voltdb;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Stack;

import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupFile;

import plsql2voltdb.SqlAnalyzer.AnalyzedSqlStmt;
import plsql_parser.PlSqlParser;
import plsql_parser.PlSqlParser.Assignment_statementContext;
import plsql_parser.PlSqlParser.BodyContext;
import plsql_parser.PlSqlParser.Cursor_loop_paramContext;
import plsql_parser.PlSqlParser.If_statementContext;
import plsql_parser.PlSqlParser.Loop_statementContext;
import plsql_parser.PlSqlParser.ParameterContext;
import plsql_parser.PlSqlParser.Return_statementContext;
import plsql_parser.PlSqlParser.Seq_of_statementsContext;
import plsql_parser.PlSqlParser.Sql_statementContext;
import plsql_parser.PlSqlParser.StatementContext;
import plsql_parser.PlSqlParser.Variable_declarationContext;
import plsql_parser.PlSqlParserBaseListener;

public class ProcedureEmitter {
    private final STGroupFile m_templateGroup;
    private final SqlAnalyzer m_analyzer;
    private final String m_package;
    private final String m_targetDirectory;
    private final TokenStream m_tokenStream;

    private final Map<String, String> m_generatedFiles = new HashMap<>();

    public ProcedureEmitter(SqlAnalyzer analyzer, String targetDirectory, String packageName, TokenStream tokenStream) {
        URL groupFile = ProcedureEmitter.class.getResource("voltdb-procedure.stg");
        m_templateGroup = new STGroupFile(groupFile, "UTF-8", '<', '>');

        m_analyzer = analyzer;
        m_package = packageName;
        m_targetDirectory = targetDirectory;
        m_tokenStream = tokenStream;

    }

    private static String getTimeString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        return sdf.format(new Date());
    }

    private class EmittingListener extends PlSqlParserBaseListener {
        private Map<String, Integer> m_sqlStmtNameMap = new HashMap<>();
        private Map<String, String> m_sqlStmts = new LinkedHashMap<>();
        private Map<Cursor_loop_paramContext, AnalyzedSqlStmt> m_cursorLoopMap = new HashMap<>();

        private List<ParameterContext> m_inputParameters = new ArrayList<>();
        private ParameterContext m_outputParameter = null;
        private List<Variable_declarationContext> m_constants = new ArrayList<>();
        private List<Variable_declarationContext> m_localVariables = new ArrayList<>();
        private Stack<Var> m_loopScopeVars = new Stack<>();

        private Stack<List<ST>> m_stmtBlockStack = new Stack<>();

        private int m_lastStatementTokenOffset = 0;

        // Could memo-ize this at some point...
        private Map<String, Var> getVisibleVariables() {
            Map<String, Var> vars = new HashMap<>();

            for (ParameterContext paramCtx : m_inputParameters) {
                Var v = Var.fromPlSql(m_analyzer, paramCtx);
                vars.put(v.getName(), v);
            }

            Var outputVar = Var.fromPlSql(m_analyzer, m_outputParameter);
            vars.put(outputVar.getName(), outputVar);

            for (Variable_declarationContext varCtx : m_constants) {
                Var v = Var.fromPlSql(m_analyzer, varCtx);
                vars.put(v.getName(), v);
            }

            for (Variable_declarationContext varCtx : m_localVariables) {
                Var v = Var.fromPlSql(m_analyzer, varCtx);
                vars.put(v.getName(), v);
            }

            for (Var v : m_loopScopeVars) {
                vars.put(v.getName(), v);
            }

            return vars;
        }



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

        private String getSqlStmtName(AnalyzedSqlStmt stmt) {
            String prefix = stmt.getNamePrefix();
            Integer prefixCount = m_sqlStmtNameMap.get(prefix);
            String name = prefix;
            if (prefixCount != null) {
                name += prefixCount.toString();
                m_sqlStmtNameMap.put(prefix, prefixCount.intValue() + 1);
            }
            else {
                name += "0";
                m_sqlStmtNameMap.put(prefix, 1);
            }

            return name;
        }

        @Override
        public void exitSql_statement(Sql_statementContext ctx) {
            AnalyzedSqlStmt analyzedStmt = m_analyzer.analyze(m_tokenStream, getVisibleVariables(), ctx);

            String stmtName = getSqlStmtName(analyzedStmt);
            m_sqlStmts.put(stmtName, ExpressionFormatter.formatAsJavaString(analyzedStmt.getRewrittenStmt()));

            ST queueSql = m_templateGroup.getInstanceOf("queue_sql_stmt");
            queueSql.add("stmt_name", stmtName);
            for (String inputParam : analyzedStmt.getInputParams()) {
                queueSql.add("params", inputParam);
            }
            m_stmtBlockStack.peek().add(queueSql);

            List<String> outputParams = analyzedStmt.getOutputParams();
            ST execSql = m_templateGroup.getInstanceOf("execute_sql_stmt");
            switch (outputParams.size()) {
            case 0:
                // Just execute the SQL.  No need to assign result to anything.
                m_stmtBlockStack.peek().add(execSql);
                break;
            case 1:
                if (analyzedStmt.producesSingleIntegerColumn()) {
                    execSql.add("var_name", outputParams.get(0));
                    execSql.add("post_text", "[0].asScalarLong()");
                    m_stmtBlockStack.peek().add(execSql);
                    break;
                }
                // otherwise, fall through to general case
            default: {
                execSql.add("var_name", "vt");
                execSql.add("post_text", "[0]");
                m_stmtBlockStack.peek().add(execSql);

                // Extract each field to local variables
                ST advance = m_templateGroup.getInstanceOf("freeform_line");
                advance.add("text", "vt.advanceRow();");
                m_stmtBlockStack.peek().add(advance);
                int i = 0;
                for (String var : analyzedStmt.getOutputParams()) {
                    ST assign = m_templateGroup.getInstanceOf("assignment_stmt");
                    assign.add("lhs", var);
                    String voltTableAccessor = ExpressionFormatter.getVoltTableAccessor(analyzedStmt.getOutputSchema(), i);
                    assert (voltTableAccessor != null);
                    assign.add("rhs", "vt." + voltTableAccessor);
                    m_stmtBlockStack.peek().add(assign);
                    ++i;
                }

            }
            }
        }

        @Override
        public void exitAssignment_statement(Assignment_statementContext ctx) {
            ST assignStmt = m_templateGroup.getInstanceOf("assignment_stmt");
            assignStmt.add("lhs", ctx.general_element().getText());
            assignStmt.add("rhs", ExpressionFormatter.format(m_tokenStream, getVisibleVariables(), ctx.expression()));
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
            m_lastStatementTokenOffset = ctx.getStart().getTokenIndex() + 1;
        }

        @Override
        public void enterSeq_of_statements(Seq_of_statementsContext ctx) {
            m_stmtBlockStack.push(new ArrayList<>());
        }

        @Override
        public void enterStatement(StatementContext ctx) {
            // Print out any comments that might be between this statement and the last.
            int lastTokenOffset = ctx.getStart().getTokenIndex() - 1;
            if (lastTokenOffset > m_lastStatementTokenOffset) {
                Interval interval = new Interval(m_lastStatementTokenOffset, lastTokenOffset);
                String commentText = m_tokenStream.getText(interval).trim();
                if (commentText.startsWith("--")) {
                    commentText = commentText.substring(2);
                    commentText = commentText.trim();
                    commentText = "// " + commentText;
                    ST commentST = m_templateGroup.getInstanceOf("freeform_line");
                    commentST.add("text", commentText);
                    m_stmtBlockStack.peek().add(commentST);
                }
            }
            m_lastStatementTokenOffset = ctx.getStop().getTokenIndex() + 2;
        }

        @Override
        public void exitStatement(StatementContext ctx) {
            m_lastStatementTokenOffset = ctx.getStop().getTokenIndex() + 2;
        }

        @Override
        public void exitIf_statement(If_statementContext ctx) {
            // For an if statement, get the seq_of_statements
            List<ST> thenStmts = m_stmtBlockStack.pop();
            ST stmtList = m_templateGroup.getInstanceOf("slist");
            stmtList.add("stmts", thenStmts);

            ST ifStmt = m_templateGroup.getInstanceOf("if_stmt");
            String cond = ExpressionFormatter.format(m_tokenStream, getVisibleVariables(), ctx.condition().expression());
            ifStmt.add("cond", cond);
            ifStmt.add("then_block", stmtList);
            m_stmtBlockStack.peek().add(ifStmt);
        }

        @Override
        public void enterLoop_statement(Loop_statementContext ctx) {
            Cursor_loop_paramContext cursorLoopParam = ctx.cursor_loop_param();
            AnalyzedSqlStmt analyzedStmt = m_analyzer.analyze(m_tokenStream, getVisibleVariables(), cursorLoopParam.select_statement());
            m_cursorLoopMap.put(cursorLoopParam, analyzedStmt);
            assert(cursorLoopParam != null);

            String rowVarName = cursorLoopParam.record_name().getText();

            // Add the row variable to the visible variables
            // So we can format expressions:
            //   rowVar.field
            // to
            //   rowVar.getString("field")
            m_loopScopeVars.push(Var.fromSchema(analyzedStmt.getOutputSchema(), rowVarName));
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
            AnalyzedSqlStmt analyzedStmt = m_cursorLoopMap.get(cursorLoopParam);
            assert (analyzedStmt != null);
            String stmtName = getSqlStmtName(analyzedStmt);
            m_sqlStmts.put(stmtName, ExpressionFormatter.formatAsJavaString(analyzedStmt.getRewrittenStmt()));

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
            executeSql.add("var_name", rowVarName);
            executeSql.add("post_text", "[0]");
            m_stmtBlockStack.peek().add(executeSql);

            ST whileStmt = m_templateGroup.getInstanceOf("while_stmt");
            whileStmt.add("cond", rowVarName + "." + "advanceRow()");
            whileStmt.add("body", stmtList);
            m_stmtBlockStack.peek().add(whileStmt);

            m_loopScopeVars.pop();
        }

        private ST getVarDeclST(Variable_declarationContext varDeclCtx) {
            ST varDecl = m_templateGroup.getInstanceOf("variable_decl");
            varDecl.add("var_type", TypeTranslator.translate(m_analyzer, varDeclCtx.type_spec()));
            varDecl.add("var_name", varDeclCtx.identifier().getText());

            if (varDeclCtx.default_value_part() != null) {
                varDecl.add("init", ExpressionFormatter.format(m_tokenStream, getVisibleVariables(), varDeclCtx.default_value_part().expression()));
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
            varDecl.add("var_type", TypeTranslator.translate(m_analyzer, parCtx.type_spec()));
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
            ST srcFileST = m_templateGroup.getInstanceOf("src_file");
            srcFileST.add("header_comment",
                    "/**\n"
                    + " * " + className + ".java\n"
                    + " *\n"
                    + " * Generated from PL/SQL code\n"
                    + " * by plsqltranslator\n"
                    + " * on " + getTimeString() + "\n"
                    + " */");
            srcFileST.add("package", m_package);

            ST classDef = m_templateGroup.getInstanceOf("class_def");
            classDef.add("name", className);

            for (Variable_declarationContext varDecl : m_constants) {
                ST decl = getVarDeclST(varDecl);
                classDef.add("constant_decls", decl);
            }

            ST runMethod = m_templateGroup.getInstanceOf("run_method");
            runMethod.add("ret_type", TypeTranslator.translate(m_analyzer, m_outputParameter.type_spec()));
            for (ParameterContext param : m_inputParameters) {
                String javaType = TypeTranslator.translate(m_analyzer, param.type_spec());
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

            srcFileST.add("class_def", classDef);
            String srcFile = srcFileST.render();
            m_generatedFiles.put(className + ".java", srcFile);
        }

    }
    public int emit(ParseTree tree) {
        ParseTreeWalker walker = new ParseTreeWalker();
        EmittingListener listener = new EmittingListener();
        walker.walk(listener, tree);

        Path pkgDir = Paths.get(m_targetDirectory, m_package);
        if (! Files.exists(pkgDir)) {
            try {
                Files.createDirectories(pkgDir);
            }
            catch (IOException ioExc) {
                System.err.println("Could not create package directory: " + ioExc.getMessage());
            }
        }

        for (Entry<String, String> entry : m_generatedFiles.entrySet()) {
            Path javaSrc = Paths.get(m_targetDirectory, m_package, entry.getKey());
            System.out.println("Writing file " + javaSrc);
            try (PrintWriter out = new PrintWriter(javaSrc.toString())) {
                out.print(entry.getValue());
            }
            catch (Exception exc) {
                System.err.println("Could not write source file: " + exc.getMessage());
                return 1;
            }
        }

        return 0;
    }
}
