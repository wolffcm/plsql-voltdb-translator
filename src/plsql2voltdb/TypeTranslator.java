package plsql2voltdb;

import plsql_parser.PlSqlParser.Type_specContext;

public class TypeTranslator {

    public static String translate(Type_specContext type_spec) {
        String plsqlType = type_spec.getText();
        if ("INTEGER".equalsIgnoreCase(plsqlType)) {
            return "long";
        }
        return null;
    }

}
