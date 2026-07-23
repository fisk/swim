package org.fisk.swim.plugins.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class GitConflictResolverState {
    private sealed interface Segment permits TextSegment, ConflictSegment {
    }

    private record TextSegment(List<String> lines) implements Segment {
    }

    private record ConflictSegment(GitConflictBlock block) implements Segment {
    }

    static final class GitConflictBlock {
        private final List<String> _base;
        private final List<String> _ours;
        private final List<String> _theirs;
        private List<String> _result;

        private GitConflictBlock(List<String> base, List<String> ours, List<String> theirs) {
            _base = List.copyOf(base);
            _ours = List.copyOf(ours);
            _theirs = List.copyOf(theirs);
            _result = new ArrayList<>(ours);
        }

        List<String> base() {
            return _base;
        }

        List<String> ours() {
            return _ours;
        }

        List<String> theirs() {
            return _theirs;
        }

        List<String> result() {
            return List.copyOf(_result);
        }

        void chooseOurs() {
            _result = new ArrayList<>(_ours);
        }

        void chooseTheirs() {
            _result = new ArrayList<>(_theirs);
        }

        void chooseBoth() {
            _result = new ArrayList<>(_ours);
            _result.addAll(_theirs);
        }
    }

    private final Path _path;
    private final List<Segment> _segments;
    private final List<GitConflictBlock> _blocks;
    private final boolean _endsWithNewline;
    private int _selection;

    private GitConflictResolverState(Path path, List<Segment> segments, List<GitConflictBlock> blocks,
            boolean endsWithNewline) {
        _path = path;
        _segments = List.copyOf(segments);
        _blocks = List.copyOf(blocks);
        _endsWithNewline = endsWithNewline;
    }

    static GitConflictResolverState parse(Path path) throws IOException {
        String text = Files.readString(path);
        boolean endsWithNewline = text.endsWith("\n");
        var parsedLines = new ArrayList<>(List.of(text.split("\\R", -1)));
        if (endsWithNewline && !parsedLines.isEmpty() && parsedLines.getLast().isEmpty()) {
            parsedLines.removeLast();
        }
        List<String> lines = List.copyOf(parsedLines);
        var segments = new ArrayList<Segment>();
        var blocks = new ArrayList<GitConflictBlock>();
        var plain = new ArrayList<String>();

        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (!line.startsWith("<<<<<<< ")) {
                plain.add(line);
                index++;
                continue;
            }
            if (!plain.isEmpty()) {
                segments.add(new TextSegment(List.copyOf(plain)));
                plain.clear();
            }
            index++;
            var ours = new ArrayList<String>();
            while (index < lines.size()
                    && !lines.get(index).startsWith("||||||| ")
                    && !"=======".equals(lines.get(index))) {
                ours.add(lines.get(index++));
            }
            var base = new ArrayList<String>();
            if (index < lines.size() && lines.get(index).startsWith("||||||| ")) {
                index++;
                while (index < lines.size() && !"=======".equals(lines.get(index))) {
                    base.add(lines.get(index++));
                }
            }
            if (index < lines.size() && "=======".equals(lines.get(index))) {
                index++;
            }
            var theirs = new ArrayList<String>();
            while (index < lines.size() && !lines.get(index).startsWith(">>>>>>> ")) {
                theirs.add(lines.get(index++));
            }
            if (index < lines.size() && lines.get(index).startsWith(">>>>>>> ")) {
                index++;
            }
            var block = new GitConflictBlock(base, ours, theirs);
            blocks.add(block);
            segments.add(new ConflictSegment(block));
        }
        if (!plain.isEmpty()) {
            segments.add(new TextSegment(List.copyOf(plain)));
        }
        return new GitConflictResolverState(path, segments, blocks, endsWithNewline);
    }

    Path path() {
        return _path;
    }

    int selection() {
        return _selection;
    }

    int blockCount() {
        return _blocks.size();
    }

    GitConflictBlock selectedBlock() {
        return _blocks.get(_selection);
    }

    void nextBlock() {
        if (_blocks.isEmpty()) {
            return;
        }
        _selection = (_selection + 1) % _blocks.size();
    }

    void previousBlock() {
        if (_blocks.isEmpty()) {
            return;
        }
        _selection = Math.floorMod(_selection - 1, _blocks.size());
    }

    void chooseOurs() {
        selectedBlock().chooseOurs();
    }

    void chooseTheirs() {
        selectedBlock().chooseTheirs();
    }

    void chooseBoth() {
        selectedBlock().chooseBoth();
    }

    String oursDocument() {
        return renderDocument(GitConflictBlock::ours);
    }

    String theirsDocument() {
        return renderDocument(GitConflictBlock::theirs);
    }

    String proposedDocument() {
        return renderDocument(GitConflictBlock::result);
    }

    void writeResolvedFile() throws IOException {
        Files.writeString(_path, proposedDocument());
    }

    private String renderDocument(java.util.function.Function<GitConflictBlock, List<String>> conflictContents) {
        var output = new ArrayList<String>();
        for (Segment segment : _segments) {
            if (segment instanceof TextSegment textSegment) {
                output.addAll(textSegment.lines());
            } else if (segment instanceof ConflictSegment conflictSegment) {
                output.addAll(conflictContents.apply(conflictSegment.block()));
            }
        }
        String text = String.join("\n", output);
        if (_endsWithNewline) {
            text += "\n";
        }
        return text;
    }
}
