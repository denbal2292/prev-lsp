from constants import EPS

START_SYMBOL = "program"

grammar = {
    # Program structure
    "program": [["definitions"]], # EOF is added implicitly
    "definitions": [["definition", "definitions'"]],
    "definitions'": [["definition", "definitions'"], [EPS]],

    # Separated from global definitions to keep FOLLOW sets separate
    "let_definitions": [["definition", "let_definitions'"]],
    "let_definitions'": [["definition", "let_definitions'"], [EPS]],

    # Definitions
    "definition": [["type_def"], ["var_def"], ["fun_def"]],
    "type_def": [["TYP", "ID", "ASSIGN", "type"]],
    "var_def": [["VAR", "ID", "COLON", "type"]],
    "fun_def": [["FUN", "ID", "LPAREN", "eparams", "RPAREN", "COLON", "type", "fun_body"]],
    "fun_body": [["ASSIGN", "exprs"], [EPS]],

    # Parameters: only under a fun_def's eparams.
    "eparams": [["params"], [EPS]],  # empty params
    "params": [["param", "params'"]],
    "params'": [["COMMA", "param", "params'"], [EPS]],
    "param": [["ID", "COLON", "type"]],

    # Fields: record types and function-pointer arg lists.
    "fields": [["field", "fields'"]],
    "fields'": [["COMMA", "field", "fields'"], [EPS]],
    "field": [["ID", "COLON", "type"]],

    # Separate out for easier flattening
    "pfields": [["COMMA", "field", "pfields'"], [EPS]],
    "pfields'": [["COMMA", "field", "pfields'"], [EPS]],

    # Types
    "types": [["type", "types'"]],
    "types'": [["COMMA", "type", "types'"], [EPS]],
    "etypes": [["types"], [EPS]],  # empty types
    "type": [["ID"], ["type_non_id"]],
    "type_paren": [
        ["ID", "type_paren_id"],
        ["COLON", "etypes", "COLON", "type", "RPAREN"],
        ["type_non_id", "RPAREN"],
    ],
    "type_paren_id": [["COLON", "type", "pfields", "RPAREN"], ["RPAREN"]],
    "type_non_id": [
        ["INT"],
        ["CHAR"],
        ["BOOL"],
        ["VOID"],
        ["LPAREN", "type_paren"],
        ["LSQUARE", "int_pfx_expr", "INTCONST", "RSQUARE", "type"],
        ["LCURLY", "fields", "RCURLY"],
        ["CARET", "type"],
    ],
    "int_pfx_expr": [["PLUS"], ["MINUS"], [EPS]],

    # Expressions
    "exprs": [["expr", "exprs'"]],
    "exprs'": [["COMMA", "expr", "exprs'"], [EPS]],
    "eexprs": [["exprs"], [EPS]],  # empty exprs

    "expr": [["assign_expr"]],

    "assign_expr": [["cast_expr", "assign_expr'"]],
    "assign_expr'": [["ASSIGN", "cast_expr"], [EPS]],

    "cast_expr": [["logical_or", "cast_expr'"]],
    "cast_expr'": [["AS", "type", "cast_expr'"], [EPS]],

    "logical_or": [["logical_and", "logical_or'"]],
    "logical_or'": [["OR", "logical_and", "logical_or'"], [EPS]],

    "logical_and": [["comparison", "logical_and'"]],
    "logical_and'": [["AND", "comparison", "logical_and'"], [EPS]],

    "comparison": [["add_expr", "comparison'"]],
    "comparison'": [
        ["EQU", "add_expr"],
        ["NEQ", "add_expr"],
        ["LT", "add_expr"],
        ["GT", "add_expr"],
        ["LEQ", "add_expr"],
        ["GEQ", "add_expr"],
        [EPS],
    ],

    "add_expr": [["mul_expr", "add_expr'"]],
    "add_expr'": [
        ["PLUS", "mul_expr", "add_expr'"],
        ["MINUS", "mul_expr", "add_expr'"],
        [EPS],
    ],

    "mul_expr": [["pfx_expr", "mul_expr'"]],
    "mul_expr'": [
        ["STAR", "pfx_expr", "mul_expr'"],
        ["SLASH", "pfx_expr", "mul_expr'"],
        ["PERCENT", "pfx_expr", "mul_expr'"],
        [EPS],
    ],

    "pfx_expr": [
        ["NOT", "pfx_expr"],
        ["PLUS", "pfx_expr"],
        ["MINUS", "pfx_expr"],
        ["CARET", "pfx_expr"],
        ["postfix_expr"],
    ],

    "postfix_expr": [["primary_expr", "postfix_expr'"]],
    "postfix_expr'": [
        ["LPAREN", "eexprs", "RPAREN", "postfix_expr'"],
        ["LSQUARE", "expr", "RSQUARE", "postfix_expr'"],
        ["CARET", "postfix_expr'"],
        ["DOT", "ID", "postfix_expr'"],
        [EPS],
    ],

    "primary_expr": [
        ["INTCONST"],
        ["bool_expr"],
        ["CHARCONST"],
        ["STRINGCONST"],
        ["void_expr"],
        ["ptr_expr"],
        ["ID"],
        ["SIZEOF", "type"],
        ["WHILE", "expr", "DO", "exprs", "END"],
        ["LET", "let_definitions", "IN", "exprs", "END"],
        ["IF", "expr", "THEN", "exprs", "else_part", "END"],
        ["LPAREN", "exprs", "RPAREN"],
    ],

    "else_part": [["ELSE", "exprs"], [EPS]],
    "bool_expr": [["TRUE"], ["FALSE"]],
    "ptr_expr": [["NIL"]],
    "void_expr": [["NONE"]],
}
