package org.fisk.swim.nemo;

final class NemoEditorControlSkill {
    private static final String CONTENT = String.join("\n",
            "# Editor Control",
            "",
            "Call start_editor_control before screen_snapshot or drive_editor. Editor control is a single-owner session that starts only after host approval and ends with finish_editor_control.",
            "Use screen_snapshot after the control session starts unless the immediately previous tool result already contains the needed after snapshot.",
            "Prefer small, bounded drive_editor inputs and inspect the after snapshot before continuing.",
            "drive_editor input is literal text plus tokens: <ESC>, <ENTER>, <TAB>, <BACKSPACE>, <UP>, <DOWN>, <LEFT>, <RIGHT>, <PAGE-UP>, <PAGE-DOWN>, <SPACE>, <LT>, <GT>, and <CTRL-x>.",
            "Use drive_editor for editor : commands exactly as a user would type them, for example :vsplit<ENTER>, :e src/Main.java<ENTER>, or :w<ENTER>. Driven editor actions are opt-in and run through the editor sandbox: workspace-local files, splits, focus, buffer navigation, quickfix/location navigation, project search, search prompts, edits, and workspace-local saves are allowed when permissions allow them.",
            "Do not use drive_editor for shell input, opening Nemo, switching to mail/Slack/Todo, rebuild/reload, debugger/git UI commands, approving prompts, or external-workspace actions; those actions are blocked or require normal Nemo tools or host action.",
            "screen_snapshot never exposes mail or private non-buffer workspaces. If a blocked/private view is visible, stop and ask the host to switch away before any editor inspection.",
            "Host approval overlays are invisible to screen_snapshot and cannot be controlled with drive_editor. If an editor-control tool says host approval is waiting or denied, stop driving and explain what approval or host action is needed.",
            "Only the Nemo session that called start_editor_control can use screen_snapshot or drive_editor until it calls finish_editor_control or the request ends. Other workers must wait or ask the host.",
            "When you are done inspecting or controlling the editor, call finish_editor_control before your final report so the lock is released and the invoking Nemo chat is visible again.",
            "When editing real files, prefer read_file/apply_patch/write_file for planned code changes. Use drive_editor for UI-only validation, reproducing editor interactions, or manipulating unsaved buffer state.");

    private NemoEditorControlSkill() {
    }

    static NemoSkillDocument document() {
        return new NemoSkillDocument("built-in/editor-control", CONTENT);
    }
}
