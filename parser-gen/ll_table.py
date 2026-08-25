from dataclasses import dataclass
from constants import EPS, NO_ENTRY
from first_follow import first_of_sequence

@dataclass(frozen=True)
class ParseTable:
    nonterminals: list[str]
    terminals: list[str]
    symbols: list[str] # Union of nonterminals and terminals
    productions: tuple[tuple[str, list[str]]] # list of (nonterminal, rhs) pairs
    table: list[list[int]] # row = stack top, column = input; NO_ENTRY/ABANDON mark error cells


def build_parse_table(grammar: dict, terminals: set[str], nonterminals: set[str], nullable: dict, follow: dict, first: dict) -> ParseTable:
    """
    Build the LL(1) parse table for the given grammar, using the nullable, FIRST, and FOLLOW sets.
    The table is represented as a 2D list, where rows correspond to nonterminals and columns correspond to symbols (terminals and nonterminals).
    Each cell contains the index of the production to use, or NO_ENTRY if there is an error (no production).
    """
    productions = flatten_productions(grammar)

    terminals = sorted(terminals)
    nonterminals = sorted(nonterminals)

    symbols = nonterminals + terminals

    # Initialize the parse table with NO_ENTRY (indicating an error)
    table = [[NO_ENTRY for _ in symbols] for _ in nonterminals]

    row_of = { nt: i for i, nt in enumerate(nonterminals) }
    col_of = { sym: i for i, sym in enumerate(symbols) }

    # Fill the parse table
    for i, production in enumerate(productions):
        nonterminal, _ = production
        cells = compute_cells(production, first, follow, nullable)
        row = row_of[nonterminal]

        for cell in cells:
            column = col_of[cell]

            if table[row][column] != NO_ENTRY:
                raise ValueError(f"Grammar is not LL(1): conflict at {nonterminal} with input {cell}")

            table[row][column] = i
    
    return ParseTable(nonterminals, terminals, symbols, productions, table)


def flatten_productions(grammar: dict) -> tuple[tuple[str, list[str]]]:
    """
    Flatten the grammar into a list of (nonterminal, rhs) pairs.
    """
    return tuple((nonterminal, rhs) for nonterminal in grammar for rhs in grammar[nonterminal])


def get_productions_by_lhs(productions, nonterminals) -> dict[str, list[int]]:
    """
    Group productions indices by their lhs nonterminal.
    """
    prods = { nt: [] for nt in nonterminals }

    for i, (nt, _) in enumerate(productions):
        prods[nt].append(i)
    
    return prods


def compute_cells(production, first, follow, nullable):
    """
    Compute the cells for a production nonterminal -> rhs.
    FIRST(rhs) is added to the cells. If all symbols in rhs are nullable, then FOLLOW(nonterminal) is also added to the cells.
    """
    nonterminal, rhs = production

    # FIRST(rhs) U (FOLLOW(nonterminal) if rhs =>* EPS else {}) 
    cells = first_of_sequence(rhs, first, nullable)

    if EPS in cells:
        cells |= follow[nonterminal]
        cells -= { EPS }
    
    return cells
