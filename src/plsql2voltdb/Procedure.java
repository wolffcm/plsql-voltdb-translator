package plsql2voltdb;

public class Procedure {
    private final String m_name;

    Procedure(String name) {
        m_name = name;
    }

    public String getName() {
        return m_name;
    }
}
