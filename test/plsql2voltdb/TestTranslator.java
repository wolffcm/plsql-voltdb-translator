package plsql2voltdb;

import org.junit.Test;

public class TestTranslator {

    @Test
    public void testBasic() {
        PlSql2JavaTranslator.main(new String[] {"test/plsql2voltdb/vote.pls"});
    }

}
