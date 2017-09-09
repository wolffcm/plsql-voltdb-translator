package plsql2voltdb;

import java.io.IOException;
import java.util.List;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import plsql_parser.PlSqlLexer;
import plsql_parser.PlSqlParser;

public class PlSql2JavaTranslator {
    public static void main(String[] args) {
        for (String arg : args) {
            CharStream cs = null;
            try {
                cs = CharStreams.fromFileName(arg);
            }
            catch(IOException ioExc) {
                System.err.println("Couldn't open file \"" + arg + "\": " + ioExc.getMessage());
                return;
            }

            PlSqlLexer lexer = new PlSqlLexer(cs);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            PlSqlParser parser = new PlSqlParser(tokens);
            ParseTree tree = parser.sql_script();

            ProcedureExtractor extractor = new ProcedureExtractor();
            List<Procedure> procedures = extractor.extract(tree);
            System.out.println("PL/SQL file " + arg + ":");
            ProcedureEmitter emitter = new ProcedureEmitter("my_pkg", ".");
            for (Procedure proc : procedures) {
                emitter.emit(proc);
            }
        }
    }
}
