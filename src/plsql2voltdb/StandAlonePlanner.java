package plsql2voltdb;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.voltdb.VoltType;
import org.voltdb.catalog.Column;
import org.voltdb.catalog.Table;
import org.voltdb.parser.SQLLexer;
import org.voltdb.parser.SQLParser;
import org.voltdb.parser.SQLParser.FileInfo;
import org.voltdb.planner.CompiledPlan;
import org.voltdb.planner.PlannerTestAideDeCamp;
import org.voltdb.plannodes.NodeSchema;

public class StandAlonePlanner {

    private final PlannerTestAideDeCamp m_aide;


    StandAlonePlanner(String ddlPath) throws Exception {

        String allDdl = new String(Files.readAllBytes(Paths.get(ddlPath)));

        String[] lines = allDdl.split("\n");
        List<String> linesNoFileCmd = new ArrayList<>();
        Set<String> endOfBatchTokens = new HashSet<>();
        FileInfo fileInfo = new FileInfo(ddlPath);
        for (String line : lines) {
            List<FileInfo> infos = SQLParser.parseFileStatement(fileInfo, line);
            if (infos != null) {
                for (FileInfo info : infos) {
                    String token = info.getDelimiter();
                    if (token != null && token.length() > 0) {
                        endOfBatchTokens.add(token);
                    }
                }
            }
            else {
                linesNoFileCmd.add(line);
            }
        }

        List<String> goodLines = new ArrayList<>();
        for (String line : linesNoFileCmd) {
            if (! endOfBatchTokens.contains(line)) {
                goodLines.add(line);
            }
        }

        String unbatchedDDL = String.join("\n", goodLines);
        List<String> stmts = SQLLexer.splitStatements(unbatchedDDL).getCompletelyParsedStmts();
        List<String> goodStmts = new ArrayList<>();

        for (String stmt : stmts) {
            if (stmt.substring(0, 6).equalsIgnoreCase("create")) {
                goodStmts.add(stmt);
            }
        }

        // So there will be a trailing semicolon...
        goodStmts.add("");

        String goodDdl = String.join(";\n", goodStmts);
        m_aide = PlannerTestAideDeCamp.fromLiteralDDL(ddlPath, goodDdl);
    }

    public NodeSchema planAndGetOutputSchema(String sql) {
        CompiledPlan plan = m_aide.compileAdHocPlan(sql);
        return plan.rootPlanGraph.getOutputSchema();
    }

    public VoltType getTypeForColumn(String tableName, String columnName) {
        Table tbl = m_aide.getDatabase().getTables().get(tableName);
        Column col = tbl.getColumns().get(columnName);
        return VoltType.get((byte)col.getType());
    }

}
