from error_recovery import RecoveryConfig

expr_exclude = {
    "PLUS", "MINUS", "STAR", "SLASH", "PERCENT",
    "AND", "OR",
    "EQU", "NEQ", "LT", "GT", "LEQ", "GEQ",
    "AS", "CARET", "DOT", "LSQUARE", "LPAREN"
}

RECOVERY_CONFIG = RecoveryConfig(
    # Expand nullable non-terminals when possible
    # except for definitions'/let_definitions', fun_body and eparams
    collapse_blocked={"definitions'", "let_definitions'", "fun_body", "eparams"},

    # Add definition starters to every sync set
    sync_extra={"VAR", "FUN", "TYP"},

    # Exclude certain terminals from some sync sets.
    sync_follow_exclude={
        "type": expr_exclude,
        "type_non_id": expr_exclude,
        "type_paren": expr_exclude,
        "type_paren_id": expr_exclude,
    },
)
