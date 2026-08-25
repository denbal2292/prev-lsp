from ll_table import ParseTable
from constants import EPS, NO_ENTRY, ABANDON
from grammar import START_SYMBOL

def _clean_name(s: str) -> str:
    mapping = {"'": "_PRIME", "+": "PLUS", "*": "STAR", "(": "LPAREN", ")": "RPAREN", "$": "EOF"}
    res = "".join(mapping.get(c, c) for c in s)
    return res.upper() if res != "" and res != EPS else "EPSILON"

def generate_python_parse_table(parse_table: ParseTable) -> str:
    """
    Generate a Python parsing table based on the given parse table. The output is a string containing the Python code for the parsing table.
    """
    num_symbols = len(parse_table.symbols)
    # num_terms = len(parse_table.terminals)
    num_nonterms = len(parse_table.nonterminals)

    output = ["from enum import IntEnum\n\n"]
    
    # 1. Enum of all symbols (terminals and nonterminals)
    output.append("# All symbols in the grammar")
    output.append("\nclass Symbol(IntEnum):\n")
    output.append("    # === NON-TERMINALS ===\n")

    for i, t in enumerate(parse_table.symbols):
        if i == num_nonterms:
            output.append("\n    # === TERMINALS ===\n")
        output.append(f"    {_clean_name(t)} = {i}\n")

    output.append(f"\n# Start symbol")
    output.append(f"\nSTART_SYMBOL = Symbol.{_clean_name(START_SYMBOL)}\n")
    output.append("\n# Number of non-terminals in the grammar")
    output.append(f"\nNONTERMINAL_COUNT = {num_nonterms}\n")

    output.append("\ndef is_terminal(symbol: Symbol) -> bool:\n")
    output.append("    \"\"\"Check if a symbol is a terminal.\"\"\"\n")
    output.append("    return symbol >= NONTERMINAL_COUNT\n")

    # 2. Productions List (RHS only)
    output.append("\n# List of RHS for each production\nPRODUCTIONS: list[list[Symbol]] = [\n")
    for nt, rhs in parse_table.productions:
        rhs_symbols = [f"Symbol.{_clean_name(s)}" for s in rhs if s != EPS]
        output.append(f"    [{', '.join(rhs_symbols)}],  # {nt} -> {' '.join(rhs)}\n")
    output.append("]\n")

    # 3. 2D Parse Table
    # M[nonterminal (top symbol), lookahead] = production index, with error
    # recovery folded into the cells plain LL(1) leaves empty:
    #     >= 0   expand this production   (LL(1) OR recovery -- no distinction)
    #     -1     NO_ENTRY -> delete the lookahead, retry (default policy)
    #     -2     ABANDON  -> pop top symbol at a sync token, keep the lookahead
    output.append(
        "\n# M[nonterminal (top symbol), lookahead] = production index, with recovery:\n"
        "#   >= 0  expand production (LL(1) or recovery)\n"
        f"#   {NO_ENTRY}    NO_ENTRY -> delete lookahead, retry\n"
        f"#   {ABANDON}    ABANDON  -> pop top symbol at sync token, keep lookahead\n"
    )
    output.append("PARSE_TABLE: list[list[int]] = [\n")
    for r_idx in range(num_nonterms):
        row = [str(parse_table.table[r_idx][c_idx]) for c_idx in range(num_symbols)]
        output.append(f"    [{', '.join(row)}],  # {parse_table.nonterminals[r_idx]}\n")
    output.append("]\n")

    return "".join(output)


def generate_java_parse_table(parse_table: ParseTable, package: str) -> tuple[str, str]:
    """
    Generate the Java parsing tables. Returns a (Symbol.java, ParseTable.java) pair.

    Symbol.java holds the shared grammar alphabet (the enum, START_SYMBOL,
    NONTERMINAL_COUNT, isTerminal) imported by the lexer, parser and tree.
    ParseTable.java holds PRODUCTIONS and PARSE_TABLE plus the recovery sentinels,
    and statically imports Symbol.* so its members stay unqualified.

    PARSE_TABLE is emitted as byte[][]; this asserts the production count fits a
    signed byte so indices and the -1/-2 sentinels stay in range. Widen to short
    if a larger grammar trips the assertion.
    """
    num_symbols   = len(parse_table.symbols)
    num_nonterms  = len(parse_table.nonterminals)
    num_prods     = len(parse_table.productions)

    # byte assumption: indices and -1/-2 must fit signed 8-bit
    assert num_prods <= 127, f"{num_prods} productions exceed signed-byte range; widen PARSE_TABLE to short"

    # Symbol.java: grammar symbols
    sym = []
    sym.append("// GENERATED FILE — do not edit. Produced by parser-gen/codegen.py.\n")
    sym.append(f"package {package};\n\n")
    sym.append("// Grammar symbols: produced by the lexer, consumed by the\n")
    sym.append("// parser and the tree. Declaration order == ordinal(), so ordinals match\n")
    sym.append("// the parse-table indices.\n")
    sym.append("public enum Symbol {\n")
    sym.append("    // === NON-TERMINALS ===\n")
    for i, t in enumerate(parse_table.symbols):
        if i == num_nonterms:
            sym.append("\n    // === TERMINALS ===\n")
        sep = ";" if i == num_symbols - 1 else ","
        sym.append(f"    {_clean_name(t)}{sep}\n")

    sym.append(f"\n    public static final Symbol START_SYMBOL = Symbol.{_clean_name(START_SYMBOL)};\n")
    sym.append(f"\n    public static final int NONTERMINAL_COUNT = {num_nonterms};\n")
    sym.append("\n    /** A symbol is a terminal iff its ordinal is at/after the terminals block. */\n")
    sym.append("    public boolean isTerminal() {\n")
    sym.append("        return ordinal() >= NONTERMINAL_COUNT;\n")
    sym.append("    }\n")
    sym.append("}\n")

    # === ParseTable.java: productions + parse table ===
    tbl = []
    tbl.append("// GENERATED FILE — do not edit. Produced by parser-gen/codegen.py.\n")
    tbl.append(f"package {package};\n\n")
    tbl.append(f"import static {package}.Symbol.*;\n\n")
    tbl.append("public final class ParseTable {\n\n")
    tbl.append("    private ParseTable() {}\n\n")
    tbl.append("    public static final byte NO_ENTRY = -1, ABANDON = -2;\n\n")

    # PRODUCTIONS: RHS only, indexed by production number.
    tbl.append("    // RHS of each production, indexed by production number.\n")
    tbl.append("    public static final Symbol[][] PRODUCTIONS = {\n")
    for nt, rhs in parse_table.productions:
        syms = ", ".join(_clean_name(s) for s in rhs if s != EPS)
        tbl.append(f"        {{ {syms} }},  // {nt} -> {' '.join(rhs)}\n")
    tbl.append("    };\n\n")

    # PARSE_TABLE: error recovery folded into the cells.
    tbl.append(
        "    // M[nonterminal.ordinal()][lookahead.ordinal()] = production index, with recovery:\n"
        "    //   >= 0  expand production (LL(1) or recovery)\n"
        f"    //   {NO_ENTRY}    NO_ENTRY -> delete lookahead, retry\n"
        f"    //   {ABANDON}    ABANDON  -> pop top symbol at sync token, keep lookahead\n"
    )
    tbl.append("    public static final byte[][] PARSE_TABLE = {\n")
    for r in range(num_nonterms):
        row = ", ".join(str(parse_table.table[r][c]) for c in range(num_symbols))
        tbl.append(f"        {{ {row} }},  // {parse_table.nonterminals[r]}\n")
    tbl.append("    };\n}\n")

    return "".join(sym), "".join(tbl)
