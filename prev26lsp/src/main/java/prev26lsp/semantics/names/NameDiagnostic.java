package prev26lsp.semantics.names;

import prev26lsp.model.Diagnostic;
import prev26lsp.parser.Node;

public record NameDiagnostic(Kind kind, Node node, String name) {

    public enum Kind {
        UNDEFINED,
        REDEFINITION,
        UNUSED
    }

    public String getMessage() {
        return switch (kind) {
            case UNDEFINED -> "'" + name + "' is not defined";
            case REDEFINITION -> "Redefinition of '" + name + "'";
            case UNUSED -> "'" + name + "' is not used";
        };
    }

    public Diagnostic.Severity getSeverity() {
        return switch (kind) {
            case UNDEFINED, REDEFINITION -> Diagnostic.Severity.ERROR;
            case UNUSED ->  Diagnostic.Severity.HINT;
        };
    }

    public boolean isUnnecessary() {
        return this.kind == Kind.UNUSED;
    }


}
