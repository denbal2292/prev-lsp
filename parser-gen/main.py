from constants import EPS
from error_recovery import add_error_recovery
from recovery_config import RECOVERY_CONFIG
from grammar import grammar, START_SYMBOL
from first_follow import compute_first_follow, get_symbols
from ll_table import build_parse_table
from table_printer import print_table_entries, print_ascii_table
from codegen import generate_java_parse_table, generate_python_parse_table

def main() -> None:
    terminals, nonterminals = get_symbols(grammar)
    symbols = terminals | nonterminals | { EPS }

    nullable, follow, first = compute_first_follow(grammar, symbols, START_SYMBOL)
    # print_sets(nullable, follow, first, nonterminals)

    parse_table = build_parse_table(grammar, terminals, nonterminals, nullable, follow, first)
    add_error_recovery(parse_table, nullable, first, follow, RECOVERY_CONFIG)

    print("Terminals:", terminals)
    print("Nullable:", { nt for nt in nonterminals if nullable[nt] })

    # print_table_entries(parse_table)
    # print_ascii_table(parse_table)

    with open("parse_table.py", "w") as f:
        f.write(generate_python_parse_table(parse_table))

    print("Python parse table written to parse_table.py")

    symbol_java, parse_table_java = generate_java_parse_table(parse_table, "parserbench.parser")
    with open("Symbol.java", "w") as f:
        f.write(symbol_java)
    with open("ParseTable.java", "w") as f:
        f.write(parse_table_java)

    print("Java tables written to Symbol.java and ParseTable.java")


def print_sets(nullable, follow, first, nonterminals):
    print("=== nullable ===")
    for symbol in nonterminals:
        print(f"{symbol}: {nullable[symbol]}")

    print("\n=== FIRST ===")
    for symbol in nonterminals:
        print(f"{symbol}: {first[symbol]}")

    print("\n=== FOLLOW ===")
    for symbol in nonterminals:
        print(f"{symbol}: {follow[symbol]}")

    print()


if __name__ == "__main__":
    main()
