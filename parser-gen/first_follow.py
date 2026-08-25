from constants import EOF, EPS

def get_symbols(grammar: dict) -> tuple[set, set]:
    """
    Extract the set of terminals and nonterminals from the grammar.
    """
    all_symbols = get_all_symbols(grammar)
    nonterminals = set(grammar.keys())
    terminals = all_symbols - nonterminals - { EPS }

    return terminals, nonterminals

def first_of_sequence(seq: list[str], first: dict, nullable: dict) -> set:
    """
    Compute FIRST(seq) for a sequence of symbols
    
    for seq = X1 X2 ... Xn:
        - add FIRST(X1) \\ {EPS} stopping at first non-nullable-symbol
        - if all Xi are nullable, add EPS to resutlt
    """
    result = set()

    for symbol in seq:
        result |= first[symbol] - { EPS }

        if not nullable[symbol]:
            break
    else:
        result.add(EPS)

    return result


def compute_first_follow(grammar: dict, all_symbols: set, start_symbol: str) -> tuple[dict, dict, dict]:
    """
    Compute the FIRST and FOLLOW sets for the given grammar.
    """
    # Initialize FIRST and FOLLOW sets, and nullable.
    nullable = { symbol: (symbol == EPS) for symbol in all_symbols }
    follow = { symbol: set() for symbol in all_symbols }
    first = { symbol: {symbol} for symbol in all_symbols }

    # Add EOF to the FOLLOW set of the start symbol.
    follow[start_symbol].add(EOF)

    _compute_sets(grammar, nullable, follow, first)

    return nullable, follow, first


def get_all_symbols(grammar: dict) -> set:
    """
    Get the set of all symbols (terminals and nonterminals) in the grammar, including EOF.
    """
    symbols = { EOF }
    for nonterminal in grammar:
        for rhs in grammar[nonterminal]:
            for symbol in rhs:
                symbols.add(symbol)
    return symbols


def add_to_set(target_set: set, source_set: set) -> bool:
    """
    Add all elements of source_set to target_set. Return `True` if target_set was changed.
    """
    initial_size = len(target_set)
    target_set.update(source_set)
    return len(target_set) > initial_size


def _compute_sets(grammar: dict, nullable: dict, follow: dict, first: dict):
    """
    Compute the nullable, FIRST, and FOLLOW sets for the given grammar.
    Algorithm taken from: "Modern Compiler Implementation in Java" by Andrew W. Appel, 2002, Algorithm 3.13.
    """
    changed = True

    while changed:
        changed = False

        # Production is: nonterminal -> rhs
        # for each production X → Y1 Y2 ... Yk
        for nonterminal in grammar:
            for rhs in grammar[nonterminal]:
                # if Y1 . . . Yk are all nullable (or if k = 0)
                if not nullable[nonterminal] and all(nullable[rhs_symbol] for rhs_symbol in rhs):
                    # nullable[X] ← true
                    nullable[nonterminal] = True
                    changed = True

                for i in range(len(rhs)):
                    # if Y1 ... Yi−1 are all nullable (or if i = 1)
                    if all(nullable[rhs_symbol] for rhs_symbol in rhs[:i]):
                        # FIRST[X] ← FIRST[X] ∪ FIRST[Yi]
                        changed |= add_to_set(first[nonterminal], first[rhs[i]])

                    # if Yi+1 ... Yk are all nullable (or if i = k)
                    if all(nullable[rhs_symbol] for rhs_symbol in rhs[i + 1:]):
                        # FOLLOW[Yi] ← FOLLOW[Yi] ∪ FOLLOW[X]
                        changed |= add_to_set(follow[rhs[i]], follow[nonterminal] - {EPS})

                    for j in range(i + 1, len(rhs)):
                        # if Yi+1 ... Yj−1 are all nullable (or if i + 1 = j)
                        if all(nullable[rhs_symbol] for rhs_symbol in rhs[i + 1:j]):
                            # FOLLOW[Yi] ← FOLLOW[Yi] ∪ FIRST[Yj]
                            changed |= add_to_set(follow[rhs[i]], first[rhs[j]] - {EPS})
