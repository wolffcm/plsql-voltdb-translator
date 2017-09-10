package plsql2voltdb;

import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupFile;

public class ProcedureEmitter {
    private final String m_package;
    private final String m_targetDirectory;

    public ProcedureEmitter(String packageName, String targetDirectory) {
        m_package = packageName;
        m_targetDirectory = targetDirectory;
    }

    public void emit(Procedure proc) {
        STGroupFile group = new STGroupFile("string-templates/java-procedure.stg");

        ST st = group.getInstanceOf("procedure");
        st.add("plsql_proc", proc);
        st.add("package", m_package);

        String procedureSource = st.render();
        System.out.println(procedureSource);
    }
}
