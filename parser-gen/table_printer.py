from ll_table import ParseTable

def format_production(parse_table: ParseTable, production_index: int | None) -> str:
    """
    Format a production as a string (nonterminal -> rhs), given its index in the parse table.
    Return an empty string if the production index is None (indicating an error cell in the parse table).
    """
    if production_index is None:
        return ""
    nonterminal, rhs = parse_table.productions[production_index]
    return f"{nonterminal} -> {''.join(rhs)}"


def print_table_entries(parse_table: ParseTable) -> None:
    """
    Print the non-empty entries of the parse table in the format: M'[nonterminal, symbol] = production.
    """
    print("=== PARSE TABLE ===")
    for row_index, nonterminal in enumerate(parse_table.nonterminals):
        for column_index, symbol in enumerate(parse_table.symbols):
            production_index = parse_table.table[row_index][column_index]
            if production_index is not None:
                print(f"M'[{nonterminal}, {symbol}] = {format_production(parse_table, production_index)}")


def print_ascii_table(parse_table: ParseTable) -> None:
    """
    Print the parse table in an ASCII format. Each cell contains the production to use, or is empty if there is an error (no production).
    """
    all_productions = [
        format_production(parse_table, parse_table.table[r][c])
        for r in range(len(parse_table.nonterminals))
        for c in range(len(parse_table.symbols))
    ]

    col_w = max(len(s) for s in parse_table.symbols + all_productions)
    nt_w  = max(len(nt) for nt in parse_table.nonterminals)

    widths = [nt_w] + [col_w] * len(parse_table.symbols)
    headers = ["", *parse_table.symbols]

    border = "+" + "+".join("-" * (w + 2) for w in widths) + "+"

    def make_row(values: list[str]) -> str:
        return "|" + "|".join(f" {v.ljust(w)} " for v, w in zip(values, widths)) + "|"

    print()
    print("=== ASCII TABLE ===")
    print(border)
    print(make_row(headers))
    print(border)

    for row_index, nonterminal in enumerate(parse_table.nonterminals):
        row = [nonterminal] + [
            format_production(parse_table, parse_table.table[row_index][c])
            for c in range(len(parse_table.symbols))
        ]
        print(make_row(row))
        print(border)
