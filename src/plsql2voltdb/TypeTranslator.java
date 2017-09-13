package plsql2voltdb;

import org.voltdb.VoltType;

import plsql_parser.PlSqlParser.Type_specContext;

public class TypeTranslator {

    private static String[] PLSQL_STRING_TYPES = {
            "CHAR",
            "NCHAR",
            "VARCHAR",
            "VARCHAR2",
            "NVARCHAR2",
            "CHARACTER",
            "STRING"
    };

    public static String translate(Type_specContext type_spec) {
        if (type_spec.datatype() == null
                || type_spec.datatype().native_datatype_element() == null) {
            return null;
        }

        String plsqlType = type_spec.datatype().native_datatype_element().getText();
        String javaType = null;

        for (String ty : PLSQL_STRING_TYPES) {
            if (ty.equalsIgnoreCase(plsqlType)) {
                javaType = "String";
                break;
            }
        }

        if (javaType == null) {
            if ("INTEGER".equalsIgnoreCase(plsqlType)) {
                javaType = "long";
            }
        }

        return javaType;
    }

    public static String translate(VoltType type) {
        String javaType = null;

        switch (type) {
        case BIGINT:
        case SMALLINT:
        case INTEGER:
        case TINYINT:
            javaType = "long";
            break;
        case STRING:
            javaType = "String";
            break;
        default:
            break;
        }

        return javaType;
    }

}
