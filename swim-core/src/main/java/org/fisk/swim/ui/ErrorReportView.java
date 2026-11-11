package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.ErrorReports;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;

/** Host-visible reports may contain private integration data and are never exposed to Nemo. */
public final class ErrorReportView extends TextPanelView {
    private final ListEventResponder actions = new ListEventResponder();
    private List<ErrorReports.Report> reports = List.of();
    private int selected;

    ErrorReportView(List<ErrorReports.Report> reports) {
        super(Rect.create(0, 0, 0, 0), "Error report", "");
        actions.addEventResponder("y", "Report", "copy report", this::copyReport);
        actions.addEventResponder("[", "Report", "previous error", () -> select(-1));
        actions.addEventResponder("]", "Report", "next error", () -> select(1));
        setReports(reports);
    }

    void setReports(List<ErrorReports.Report> reports) {
        this.reports = List.copyOf(reports);
        selected = reports.size() - 1;
        refreshText();
    }

    String reportText() {
        return reports.isEmpty() ? "No errors recorded." : reports.get(selected).text();
    }

    private void refreshText() {
        setText("y: copy report   [/]: previous/next   q: close\nj/k or PgUp/PgDn: scroll\n"
                + "Error " + (selected + 1) + " of " + reports.size() + "\n\n" + reportText());
    }

    private void select(int direction) {
        if (blocked()) {
            return;
        }
        selected = Math.clamp(selected + direction, 0, Math.max(0, reports.size() - 1));
        refreshText();
    }

    private void copyReport() {
        if (blocked()) {
            return;
        }
        boolean copied = ExternalResourceSupport.copyPrivateText(reportText());
        var window = Window.getInstance();
        if (window != null && window.getCommandView() != null) {
            window.getCommandView().setMessage(copied ? "Error report copied to clipboard"
                    : "Clipboard unavailable; error details are also in the Swim log");
        }
    }

    private boolean blocked() {
        var window = Window.getInstance();
        return window != null && window.blockEditorDriveAction("error reports", "error details are private and unavailable to Nemo");
    }

    @Override
    protected void close() {
        if (!blocked() && Window.getInstance() != null) {
            Window.getInstance().closeErrorReport(this);
        }
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        Response response = actions.processEvent(events);
        ownAction = response == Response.YES;
        return response == Response.NO ? super.processEvent(events) : response;
    }

    @Override
    public void respond() {
        if (blocked()) {
            return;
        }
        // ListEventResponder only dispatches its last matched binding.
        if (ownAction) {
            actions.respond();
        } else {
            super.respond();
        }
    }

    private boolean ownAction;

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        var hints = new ArrayList<>(actions.keyBindingHints());
        hints.addAll(super.keyBindingHints());
        return List.copyOf(hints);
    }
}
