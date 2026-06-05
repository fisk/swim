package org.fisk.swim.ui;

import java.nio.file.Path;

record EditorLocation(Path path, int position) {
    String display() {
        if (path == null) {
            return "*scratch*:" + position;
        }
        return path + ":" + position;
    }
}
