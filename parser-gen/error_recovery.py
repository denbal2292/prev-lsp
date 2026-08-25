from dataclasses import dataclass, field

from constants import ABANDON, EPS, NO_ENTRY
from ll_table import ParseTable, get_productions_by_lhs

@dataclass(frozen=True)
class RecoveryConfig:
    collapse_blocked: set[str]
    sync_extra: set[str]
    sync_follow_exclude: dict = field(default_factory=dict)
    enable_nullable_collapse: bool = True
    enable_sync: bool = True


def add_error_recovery(parse_table: ParseTable, nullable: dict, first: dict, follow: dict, config: RecoveryConfig) -> None:
    table = parse_table.table
    productions = parse_table.productions
    nonterminals = parse_table.nonterminals

    col_of = { sym: i for i, sym in enumerate(parse_table.symbols) }
    terminal_cols = { col_of[t] for t in parse_table.terminals }
    prods_of = get_productions_by_lhs(productions, nonterminals)

    def apply_nullable_default(row, nt):
        if not nullable[nt] or nt in config.collapse_blocked:
            return

        eps_prods = [p_idx for p_idx in prods_of[nt] if productions[p_idx][1] == [EPS]]
        assert len(eps_prods) == 1, f"Nullable nonterminal {nt!r} has {len(eps_prods)} ε-productions, expected exactly 1"

        # Only fill empty in empty terminal cells
        prod_idx = eps_prods[0]
        for col in terminal_cols:
            if table[row][col] == NO_ENTRY:
                table[row][col] = prod_idx

    
    def apply_sync(row, nt):
        excluded = config.sync_follow_exclude.get(nt, set())
        sync_set = ((follow.get(nt, set()) | config.sync_extra) - excluded) - { EPS }

        for sym in sync_set:
            col = col_of[sym]
            if col in terminal_cols and table[row][col] == NO_ENTRY:
                table[row][col] = ABANDON

    for row, nt in enumerate(nonterminals):        
        if config.enable_nullable_collapse:
            apply_nullable_default(row, nt)

        if config.enable_sync:
            apply_sync(row, nt)
