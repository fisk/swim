package org.fisk.swim.plugins.git;

final class GitRebaseEntry {
    enum Action {
        PICK("pick"),
        SQUASH("squash"),
        FIXUP("fixup"),
        DROP("drop");

        private final String _token;

        Action(String token) {
            _token = token;
        }

        String token() {
            return _token;
        }
    }

    private final String _objectId;
    private final String _shortId;
    private final String _summary;
    private final String _author;
    private Action _action;

    GitRebaseEntry(String objectId, String shortId, String summary, String author) {
        _objectId = objectId;
        _shortId = shortId;
        _summary = summary;
        _author = author;
        _action = Action.PICK;
    }

    String objectId() {
        return _objectId;
    }

    String shortId() {
        return _shortId;
    }

    String summary() {
        return _summary;
    }

    String author() {
        return _author;
    }

    Action action() {
        return _action;
    }

    void setAction(Action action) {
        _action = action;
    }

    String displayLabel() {
        return _action.token() + " " + _shortId + " " + _summary + " [" + _author + "]";
    }

    String todoLine() {
        return _action.token() + " " + _objectId + " " + _summary;
    }
}
