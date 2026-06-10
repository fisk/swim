package org.fisk.swim.terminal;

import java.io.IOException;

@FunctionalInterface
public interface TerminalControlWriter {
    void writeControlSequence(String sequence) throws IOException;
}
