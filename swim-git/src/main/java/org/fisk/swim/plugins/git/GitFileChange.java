package org.fisk.swim.plugins.git;

import java.nio.file.Path;

record GitFileChange(GitSection section, String relativePath, Path absolutePath, String statusCode) {
    String displayLabel() {
        return statusCode() + " " + relativePath();
    }
}
