package plsql2voltdb;

import org.junit.Test;
import org.voltdb.plannodes.NodeSchema;

public class TestStandAlonePlanner {
    @Test
    public void testIt() throws Exception {
        StandAlonePlanner planner = new StandAlonePlanner("./test/plsql2voltdb/voter-ddl.sql");

        NodeSchema schema = planner.planAndGetOutputSchema("select * from votes");
        System.out.println(schema);
    }
}
