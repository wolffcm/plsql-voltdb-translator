package plsql2voltdb;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestTranslator {

    private Path m_tempDir;

    @Before
    public void setUp() throws IOException {
        m_tempDir = Files.createTempDirectory("TestTranslator");
        //m_tempDir.toFile().deleteOnExit();
    }

    @After
    public void tearDown() throws IOException {
        //Files.deleteIfExists(m_tempDir);
    }

    @Test
    public void testBasic() {
        PlSql2JavaTranslator.main(new String[] {
                "-ddl", "test/plsql2voltdb/voter-ddl.sql",
                "-dir", m_tempDir.toString(),
                "-package", "voter",
                "test/plsql2voltdb/vote.pls"});

        assertTrue(Files.exists(m_tempDir.resolve(Paths.get("voter", "Vote.java"))));
    }
}
