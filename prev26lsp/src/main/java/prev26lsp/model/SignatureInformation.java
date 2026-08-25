package prev26lsp.model;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.jsonrpc.messages.Tuple;

public class SignatureInformation {

    private final String label;
    private final List<String> paramLabels;
    private final int activeParameter;

    public SignatureInformation(String label, List<String> paramLabels, int activeParameter) {
        this.label = label;
        this.paramLabels = paramLabels;
        this.activeParameter = activeParameter;
    }

    public org.eclipse.lsp4j.SignatureHelp toLspSignatureHelp() {
        org.eclipse.lsp4j.SignatureInformation info = new org.eclipse.lsp4j.SignatureInformation(label);

        // Address each parameter by its [start, end) offset in the label rather than by
        // substring: duplicate labels (e.g. two `int`s) would otherwise all highlight the
        // first match. Scan left-to-right so each param resolves to a distinct span.
        List<ParameterInformation> params = new ArrayList<>(paramLabels.size());
        int from = 0;
        for (String paramLabel : paramLabels) {
            int start = label.indexOf(paramLabel, from);
            if (start < 0) {
                params.add(new ParameterInformation(paramLabel)); // fallback: string label
                continue;
            }
            int end = start + paramLabel.length();
            ParameterInformation p = new ParameterInformation();
            p.setLabel(Tuple.two(start, end));
            params.add(p);
            from = end;
        }
        info.setParameters(params);

        return new SignatureHelp(List.of(info), 0, activeParameter);
    }

    @Override
    public String toString() {
        return "SignatureInformation[label=" + label + ", paramLabels=" + paramLabels
                + ", activeParameter=" + activeParameter + "]";
    }
}