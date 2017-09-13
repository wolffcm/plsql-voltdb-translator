package plsql2voltdb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import plsql_parser.PlSqlLexer;
import plsql_parser.PlSqlParser;

public class PlSql2JavaTranslator {

    public static void main(String[] args) {
        List<String> plSqlFiles = new ArrayList<>();
        String ddlPath = null;
        String targetDirectory = ".";
        String targetPackage = null;


        if (args.length == 0) {
            System.err.println("Usage: plsqltranslator -ddl FILE [-package PACKAGE_NAME] [-dir DIRECTORY] [FILE...]");
            System.exit(1);
        }

        int i = 0;
        while (i < args.length) {
            if (args[i].equals("-ddl")) {
                ++i;
                if (i >= args.length) {
                    System.err.println("Option \"ddl\" expects an argument");
                    System.exit(1);
                }

                ddlPath = args[i];
            }
            else if (args[i].equals("-package")) {
                ++i;
                if (i >= args.length) {
                    System.err.println("Option \"-package\" expects an argument");
                    System.exit(1);
                }
                targetPackage = args[i];
            }
            else if (args[i].equals("-dir")) {
                ++i;
                if (i >= args.length) {
                    System.err.println("Option \"-dir\" expects an argument");
                    System.exit(1);
                }
                targetDirectory = args[i];
            }
            else {
                plSqlFiles.add(args[i]);
            }

            ++i;
        }

        if (ddlPath == null) {
            System.err.println("Please specify a DDL file using the \"-ddl FILE\" option.");
            System.exit(1);
        }

        SqlAnalyzer analyzer = null;
        try {
            analyzer = new SqlAnalyzer(ddlPath);
        }
        catch (Exception exc) {
            System.out.println("Problem loading DDL: " + exc.getMessage());
            System.exit(1);
        }

        for (String plSqlFile : plSqlFiles) {
            CharStream cs = null;
            try {
                cs = CharStreams.fromFileName(plSqlFile);
            }
            catch(IOException ioExc) {
                System.err.println("Couldn't open file \"" + plSqlFile + "\": " + ioExc.getMessage());
                return;
            }

            PlSqlLexer lexer = new PlSqlLexer(cs);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            PlSqlParser parser = new PlSqlParser(tokens);
            ParseTree tree = parser.sql_script();

            ProcedureEmitter emitter = new ProcedureEmitter(analyzer, targetDirectory, targetPackage, tokens);
            emitter.emit(tree);
        }
    }
}
