package plsql2voltdb;

import org.junit.Test;

public class TestTranslator {

    @Test
    public void testBasic() {
        PlSql2JavaTranslator.main(new String[] {
                "-ddl", "test/plsql2voltdb/voter-ddl.sql",
                "-package", "voter",
                "test/plsql2voltdb/vote.pls"});
    }
}
