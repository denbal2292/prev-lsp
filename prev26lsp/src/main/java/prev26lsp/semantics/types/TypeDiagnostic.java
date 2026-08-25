package prev26lsp.semantics.types;

import prev26lsp.parser.Node;

public record TypeDiagnostic(Node node, String message) { }
