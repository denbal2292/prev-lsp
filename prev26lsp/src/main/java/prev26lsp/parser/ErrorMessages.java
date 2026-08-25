package prev26lsp.parser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class ErrorMessages {

    private ErrorMessages() {}

    private static String terminalSpelling(Symbol sym) {
        return switch (sym) {
            // Punctuation and operators
            case ASSIGN -> "'='";
            case CARET -> "'^'";
            case COLON -> "':'";
            case COMMA -> "','";
            case DOT -> "'.'";
            case EQU -> "'=='";
            case GEQ -> "'>='";
            case GT -> "'>'";
            case LCURLY -> "'{'";
            case LEQ -> "'<='";
            case LPAREN -> "'('";
            case LSQUARE -> "'['";
            case LT -> "'<'";
            case MINUS -> "'-'";
            case NEQ -> "'!='";
            case PERCENT -> "'%'";
            case PLUS -> "'+'";
            case RCURLY -> "'}'";
            case RPAREN -> "')'";
            case RSQUARE -> "']'";
            case SLASH -> "'/'";
            case STAR -> "'*'";

            // Tokens with values
            case ID -> "an identifier";
            case INTCONST -> "a number";
            case STRINGCONST -> "a string";
            case CHARCONST -> "a character";
            case EOF -> "end of input";

            default -> "'" + sym.name().toLowerCase() + "'";
        };
    }

    private static final Set<Symbol> EXPR_SYMBOLS = EnumSet.of(
            Symbol.EXPR, Symbol.EXPRS, Symbol.EEXPRS, Symbol.ASSIGN_EXPR,
            Symbol.CAST_EXPR, Symbol.LOGICAL_OR, Symbol.LOGICAL_AND, Symbol.COMPARISON,
            Symbol.ADD_EXPR, Symbol.MUL_EXPR, Symbol.PFX_EXPR, Symbol.INT_PFX_EXPR,
            Symbol.POSTFIX_EXPR, Symbol.PRIMARY_EXPR, Symbol.BOOL_EXPR,
            Symbol.PTR_EXPR, Symbol.VOID_EXPR);

    private static final Set<Symbol> TYPE_SYMBOLS = EnumSet.of(
            Symbol.TYPE, Symbol.TYPES, Symbol.ETYPES, Symbol.TYPE_NON_ID,
            Symbol.TYPE_PAREN, Symbol.TYPE_PAREN_ID);

    private static final Set<Symbol> DEF_SYMBOLS = EnumSet.of(
            Symbol.DEFINITION, Symbol.DEFINITIONS, Symbol.FUN_DEF, Symbol.VAR_DEF,
            Symbol.TYPE_DEF);

    private static final Set<Symbol> PARAM_SYMBOLS = EnumSet.of(
            Symbol.PARAM, Symbol.PARAMS, Symbol.EPARAMS, Symbol.FIELD, Symbol.FIELDS);

    // Maximum number of alternatives shown in the expected ... message
    private static final int MAX_EXPECTED = 6;

    public static String describeSymbol(Symbol sym) {
        if (sym.isTerminal()) {
            return terminalSpelling(sym);
        }

        String category = category(sym);
        if (category != null) {
            return category;
        }

        return sym.name().replace("_PRIME", "").replace("_", " ").toLowerCase();
    }

    public static String describeFound(Symbol sym, String value) {
        return switch (sym) {
            case ID -> "identifier '" + value + "'";
            case INTCONST -> "number '" + value + "'";
            case STRINGCONST -> "string " + value;
            case CHARCONST -> "character " + value;
            default -> (value != null) ? "'" + value + "'" : describeSymbol(sym);
        };
    }

    private static String category(Symbol nt) {
        if (EXPR_SYMBOLS.contains(nt)) return "an expression";
        if (TYPE_SYMBOLS.contains(nt)) return "a type";
        if (DEF_SYMBOLS.contains(nt)) return "a definition";
        if (PARAM_SYMBOLS.contains(nt)) return "a parameter";
        return null;
    }

    public static String describeContext(Symbol nt) {
        String category = category(nt);
        if (category != null) {
            return category;
        }

        List<Symbol> wanted = expectedTerminals(nt);
        if (wanted.isEmpty()) {
            return describeSymbol(nt);
        }

        List<String> phrases = new ArrayList<>();
        for (int i = 0; i < Math.min(wanted.size(), MAX_EXPECTED); i++) {
            phrases.add(describeSymbol(wanted.get(i)));
        }
        if (wanted.size() > MAX_EXPECTED) {
            phrases.add("...");
        }
        return joinPhrases(phrases);
    }

    /** Terminals that are legal lookahead when expanding non-terminal */
    private static List<Symbol> expectedTerminals(Symbol nt) {
        if (nt.isTerminal()) {
            return List.of();
        }

        byte[] row = ParseTable.PARSE_TABLE[nt.ordinal()];
        Symbol[] all = Symbol.values();

        List<Symbol> wanted = new ArrayList<>();
        for (int t = 0; t < row.length; t++) {
            // A real production (rule idx. >= 0) whose column is a terminal is expected here.
            if (row[t] >= 0 && all[t].isTerminal()) {
                wanted.add(all[t]);
            }
        }
        return wanted;
    }

    /** Join phrases with commas and a trailing "and". */
    public static String joinPhrases(List<String> phrases) {
        if (phrases.size() == 1) {
            return phrases.get(0);
        }

        return String.join(", ", phrases.subList(0, phrases.size() - 1))
                + " and " + phrases.get(phrases.size() - 1);
    }
}
