package org.fisk.swim.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fisk.swim.EventThread;
import org.fisk.swim.copy.Copy;
import org.fisk.swim.event.KeyStrokeEvent;
import org.fisk.swim.event.RecordedKey;
import org.fisk.swim.event.RunnableEvent;

final class EditorState {
    private static final int MAX_JUMPS = 100;

    private final Map<Character, EditorLocation> _marks = new LinkedHashMap<>();
    private final List<EditorLocation> _jumpList = new ArrayList<>();
    private int _jumpIndex = -1;
    private Character _selectedRegister;
    private boolean _recordingMacro;
    private char _recordingMacroRegister;
    private final List<RecordedKey> _currentMacro = new ArrayList<>();
    private int _macroSkipObservedKeys;
    private boolean _repeatRecording;
    private final List<RecordedKey> _currentRepeat = new ArrayList<>();
    private List<RecordedKey> _lastRepeat = List.of();
    private int _repeatSkipObservedKeys;
    private boolean _playingKeys;

    void selectRegister(char register) {
        if (isTextRegister(register)) {
            _selectedRegister = register;
        }
    }

    Character consumeSelectedRegister() {
        Character register = _selectedRegister;
        _selectedRegister = null;
        return register;
    }

    Character peekSelectedRegister() {
        return _selectedRegister;
    }

    boolean startMacroRecording(char register) {
        if (_playingKeys || _recordingMacro) {
            return false;
        }
        char normalized = Character.toLowerCase(register);
        if (normalized < 'a' || normalized > 'z') {
            return false;
        }
        _recordingMacro = true;
        _recordingMacroRegister = normalized;
        _currentMacro.clear();
        _macroSkipObservedKeys = 1;
        return true;
    }

    boolean stopMacroRecording() {
        if (!_recordingMacro) {
            return false;
        }
        _recordingMacro = false;
        Copy.getInstance().setMacro(_recordingMacroRegister, _currentMacro);
        _currentMacro.clear();
        _macroSkipObservedKeys = 0;
        return true;
    }

    boolean isRecordingMacro() {
        return _recordingMacro;
    }

    boolean playMacro(char register, int count) {
        return enqueueKeys(Copy.getInstance().getMacro(register), count);
    }

    boolean playLastMacro(int count) {
        return enqueueKeys(Copy.getInstance().getLastMacro(), count);
    }

    void beginRepeatRecording(List<RecordedKey> initialKeys) {
        _repeatRecording = true;
        _currentRepeat.clear();
        if (initialKeys != null) {
            _currentRepeat.addAll(initialKeys);
            _repeatSkipObservedKeys = initialKeys.size();
        } else {
            _repeatSkipObservedKeys = 0;
        }
    }

    void skipObservedRepeatKeys(int count) {
        _repeatSkipObservedKeys += Math.max(0, count);
    }

    void appendRepeatKey(RecordedKey key) {
        if (_repeatRecording && key != null) {
            _currentRepeat.add(key);
        }
    }

    void commitRepeatRecording() {
        if (!_repeatRecording) {
            return;
        }
        _repeatRecording = false;
        if (!_currentRepeat.isEmpty()) {
            _lastRepeat = List.copyOf(_currentRepeat);
        }
        _currentRepeat.clear();
        _repeatSkipObservedKeys = 0;
    }

    void cancelRepeatRecording() {
        _repeatRecording = false;
        _currentRepeat.clear();
        _repeatSkipObservedKeys = 0;
    }

    boolean repeatLastEdit(int count) {
        return enqueueKeys(_lastRepeat, count);
    }

    void observeKeyStroke(com.googlecode.lanterna.input.KeyStroke keyStroke) {
        if (_playingKeys || keyStroke == null) {
            return;
        }
        var recorded = RecordedKey.fromKeyStroke(keyStroke);
        if (_recordingMacro) {
            if (_macroSkipObservedKeys > 0) {
                _macroSkipObservedKeys--;
            } else {
                _currentMacro.add(recorded);
            }
        }
        if (_repeatRecording) {
            if (_repeatSkipObservedKeys > 0) {
                _repeatSkipObservedKeys--;
            } else {
                _currentRepeat.add(recorded);
            }
        }
    }

    void setMark(char mark, Path path, int position) {
        char normalized = Character.toLowerCase(mark);
        if (normalized < 'a' || normalized > 'z') {
            return;
        }
        _marks.put(normalized, new EditorLocation(path, position));
    }

    EditorLocation getMark(char mark) {
        char normalized = Character.toLowerCase(mark);
        return normalized < 'a' || normalized > 'z' ? null : _marks.get(normalized);
    }

    Map<Character, EditorLocation> markSnapshot() {
        return Map.copyOf(_marks);
    }

    void recordJump(EditorLocation location) {
        if (location == null) {
            return;
        }
        if (_jumpIndex >= 0 && _jumpIndex < _jumpList.size() && sameLocation(_jumpList.get(_jumpIndex), location)) {
            return;
        }
        if (_jumpIndex < _jumpList.size() - 1) {
            _jumpList.subList(_jumpIndex + 1, _jumpList.size()).clear();
        }
        if (!_jumpList.isEmpty() && sameLocation(_jumpList.getLast(), location)) {
            _jumpIndex = _jumpList.size() - 1;
            return;
        }
        _jumpList.add(location);
        if (_jumpList.size() > MAX_JUMPS) {
            _jumpList.removeFirst();
        }
        _jumpIndex = _jumpList.size() - 1;
    }

    EditorLocation jumpBack() {
        if (_jumpIndex <= 0) {
            return null;
        }
        _jumpIndex--;
        return _jumpList.get(_jumpIndex);
    }

    EditorLocation jumpForward() {
        if (_jumpIndex < 0 || _jumpIndex >= _jumpList.size() - 1) {
            return null;
        }
        _jumpIndex++;
        return _jumpList.get(_jumpIndex);
    }

    List<EditorLocation> jumpSnapshot() {
        return List.copyOf(_jumpList);
    }

    int jumpIndex() {
        return _jumpIndex;
    }

    private boolean enqueueKeys(List<RecordedKey> keys, int count) {
        if (_playingKeys || keys == null || keys.isEmpty() || count <= 0) {
            return false;
        }
        _playingKeys = true;
        var eventThread = EventThread.getInstance();
        for (int i = 0; i < count; i++) {
            for (var key : keys) {
                eventThread.enqueue(new KeyStrokeEvent(key.toKeyStroke()));
            }
        }
        eventThread.enqueue(new RunnableEvent(() -> _playingKeys = false));
        return true;
    }

    private static boolean sameLocation(EditorLocation left, EditorLocation right) {
        return left != null
                && right != null
                && java.util.Objects.equals(left.path(), right.path())
                && left.position() == right.position();
    }

    private static boolean isTextRegister(char register) {
        return register >= 'a' && register <= 'z'
                || register >= 'A' && register <= 'Z'
                || register >= '0' && register <= '9'
                || register == '"'
                || register == '-'
                || register == '_'
                || register == '+'
                || register == '*';
    }
}
