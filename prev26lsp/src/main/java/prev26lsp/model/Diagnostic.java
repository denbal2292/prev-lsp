package prev26lsp.model;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import prev26lsp.document.LineIndex;
import prev26lsp.semantics.names.NameDiagnostic;

import java.util.List;

public class Diagnostic {

    public enum Severity {
        ERROR,
        WARNING,
        INFORMATION,
        HINT
    }

    private final String source;
    private final String message;
    private final int start;
    private final int length;
    private final Severity severity;
    private final boolean unnecessary;

    public Diagnostic(String source, String message, int start, int length, Severity severity, boolean unnecessary) {
        this.source = source;
        this.message = message;
        this.start = start;
        this.length = length;
        this.severity = severity;
        this.unnecessary = unnecessary;
    }

    public static Diagnostic fromNameDiagnostic(NameDiagnostic nameDiag, int start, int length) {
        return new Diagnostic("prev26name", nameDiag.getMessage(), start, length, nameDiag.getSeverity(), nameDiag.isUnnecessary());
    }

    public String message() {
        return message;
    }

    public int start() {
        return start;
    }

    public int length() {
        return length;
    }

    public org.eclipse.lsp4j.Diagnostic toLspDiagnostic(LineIndex lineIndex) {
        org.eclipse.lsp4j.Diagnostic diagnostic = new org.eclipse.lsp4j.Diagnostic();

        diagnostic.setSeverity(this.getLspSeverity());
        diagnostic.setRange(lineIndex.convertToRange(start, length).toLspRange());
        diagnostic.setMessage(message);
        diagnostic.setSource(source);

        if (this.unnecessary) {
            diagnostic.setTags(List.of(DiagnosticTag.Unnecessary));
        }

        return diagnostic;
    }


    private org.eclipse.lsp4j.DiagnosticSeverity getLspSeverity() {
        return switch (severity) {
            case ERROR -> DiagnosticSeverity.Error;
            case WARNING -> DiagnosticSeverity.Warning;
            case INFORMATION -> DiagnosticSeverity.Information;
            case HINT -> DiagnosticSeverity.Hint;
        };
    }

}
