package org.fisk.swim.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.ArrayDeque;
import java.util.function.Supplier;
import org.fisk.swim.EventThread;
import org.fisk.swim.event.KeyStroke;
import org.fisk.swim.event.KeyType;
import org.fisk.swim.event.MouseAction;
import org.fisk.swim.event.MouseActionType;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.ui.Window;

/** POSIX ANSI terminal transport backed solely by SWIM's cell buffer. */
public final class AnsiTerminalBackend implements TerminalBackend {
  /**
   * A terminal is a byte stream, so SSH or another relayed terminal can split one key's escape
   * sequence over multiple reads. Wait briefly for its continuation rather than turning one
   * physical key into ESC followed by the sequence's printable bytes.
   */
  private static final long INPUT_CONTINUATION_WAIT_MILLIS = 120L;

  private static final String ENTER_ALTERNATE_SCREEN = "\u001b[?1049h";
  private static final String EXIT_ALTERNATE_SCREEN = "\u001b[?1049l";
  private static final String HIDE_CURSOR = "\u001b[?25l";
  private static final String SHOW_CURSOR = "\u001b[?25h";
  private static final String ENABLE_BRACKETED_PASTE = "\u001b[?2004h";
  private static final String DISABLE_BRACKETED_PASTE = "\u001b[?2004l";
  private static final String ENABLE_MOUSE = "\u001b[?1000h\u001b[?1006h";
  private static final String DISABLE_MOUSE = "\u001b[?1006l\u001b[?1000l";
  private static final String ENABLE_MODIFY_OTHER_KEYS = "\u001b[>4;2m";
  private static final String DISABLE_MODIFY_OTHER_KEYS = "\u001b[>4m";

  private final InputStream input;
  private final OutputStream output;
  // The production backend writes to System.out, which can block behind an
  // SSH/client relay.  Keep test and embedded streams synchronous so their
  // deterministic flush contract remains unchanged.
  private final boolean asynchronousOutput;
  private final Object outputQueueLock = new Object();
  private boolean outputWriteInFlight;
  private boolean redrawRequestedWhileWriting;
  private String pendingTerminalControl = "";
  private final Supplier<TerminalDimensions> dimensionsSupplier;
  private TerminalDimensions dimensions;
  private AnsiScreen screen;
  private AnsiScreenGraphics graphics;
  private final TerminalUtf8Decoder utf8Decoder = new TerminalUtf8Decoder();
  private boolean decodingUtf8;
  private boolean utf8Alt;
  private boolean pendingEscape;
  private final ArrayDeque<KeyStroke> pendingText = new ArrayDeque<>();
  private final StringBuilder inputSequence = new StringBuilder();
  private int sequencePrefix;
  private boolean sequenceOverflow;
  private boolean stringEscape;
  private int pendingByte = -1;
  private boolean started;

  public AnsiTerminalBackend(
      InputStream input, OutputStream output, Supplier<TerminalDimensions> dimensionsSupplier) {
    this(input, output, dimensionsSupplier, output == System.out);
  }

  AnsiTerminalBackend(
      InputStream input,
      OutputStream output,
      Supplier<TerminalDimensions> dimensionsSupplier,
      boolean asynchronousOutput) {
    this.input = input;
    this.output = Objects.requireNonNull(output, "output");
    this.asynchronousOutput = asynchronousOutput;
    this.dimensionsSupplier = Objects.requireNonNull(dimensionsSupplier, "dimensionsSupplier");
    this.dimensions = requireDimensions(dimensionsSupplier.get());
    this.screen = new AnsiScreen(dimensions.columns(), dimensions.rows());
    this.graphics = new AnsiScreenGraphics(screen);
  }

  @Override
  public void start() throws IOException {
    if (started) {
      return;
    }
    started = true;
    writeSynchronously(
        ENTER_ALTERNATE_SCREEN
            + HIDE_CURSOR
            + ENABLE_BRACKETED_PASTE
            + ENABLE_MOUSE
            + ENABLE_MODIFY_OTHER_KEYS);
  }

  @Override
  public void stop() throws IOException {
    if (!started) {
      return;
    }
    started = false;
    writeSynchronously(
        DISABLE_MODIFY_OTHER_KEYS
            + DISABLE_MOUSE
            + DISABLE_BRACKETED_PASTE
            + SHOW_CURSOR
            + EXIT_ALTERNATE_SCREEN
            + "\u001b[0m");
  }

  @Override
  public void clear() {
    screen.clear();
  }

  @Override
  public void refresh() throws IOException {
    if (!asynchronousOutput) {
      writeSynchronously(screen.flush());
      return;
    }
    synchronized (outputQueueLock) {
      if (outputWriteInFlight) {
        redrawRequestedWhileWriting = true;
        return;
      }
      String frame = screen.flush();
      if (frame.isEmpty()) {
        return;
      }
      outputWriteInFlight = true;
      writeAsynchronously(frame);
    }
  }

  @Override
  public TerminalDimensions dimensions() {
    return dimensions;
  }

  @Override
  public TerminalDimensions resizeIfNeeded() {
    TerminalDimensions next = requireDimensions(dimensionsSupplier.get());
    if (next.equals(dimensions)) {
      return null;
    }
    dimensions = next;
    screen.resize(next.columns(), next.rows());
    return next;
  }

  @Override
  public TerminalGraphics graphics() {
    return graphics;
  }

  @Override
  public KeyStroke pollInput() throws IOException {
    return pollOneInput();
  }

  private KeyStroke pollOneInput() throws IOException {
    if (!pendingText.isEmpty()) {
      return pendingText.removeFirst();
    }
    if (input == null) {
      return null;
    }
    if (sequencePrefix != 0) {
      return continueSequence();
    }
    if (!pendingEscape && pendingByte < 0 && input.available() == 0) {
      return null;
    }
    int first = pendingEscape ? 0x1b : pendingByte >= 0 ? pendingByte : input.read();
    pendingEscape = false;
    pendingByte = -1;
    if (first < 0) {
      return new KeyStroke(KeyType.EOF);
    }
    if (decodingUtf8 || first != 0x1b) {
      return readTextInput(first, false);
    }
    int second = readContinuationByte();
    if (second < 0) {
      return new KeyStroke(KeyType.Escape);
    }
    if (second == 0x1b) {
      // Escape followed by an arrow (or another Escape) is two keypresses.
      // Keep the second prefix for the next poll instead of leaking its tail
      // into the editor as printable input such as "[D".
      pendingEscape = true;
      return new KeyStroke(KeyType.Escape);
    }
    if (second == '[' || second == 'O' || second == ']' || second == 'P'
        || second == '_' || second == '^' || second == 'X'
        || second >= 0x20 && second <= 0x2f) {
      sequencePrefix = second;
      return continueSequence();
    }
    return readTextInput(second, true);
  }

  /** Once a prefix is known, a transport pause must not turn its tail into typing. */
  private KeyStroke continueSequence() throws IOException {
    while (true) {
      int value = readContinuationByte();
      if (value < 0) {
        return null;
      }
      boolean controlString = sequencePrefix == ']' || sequencePrefix == 'P'
          || sequencePrefix == '_' || sequencePrefix == '^' || sequencePrefix == 'X';
      if (controlString) {
        if ((sequencePrefix == ']' && value == 7) || (stringEscape && value == '\\')) {
          resetSequence();
          return new KeyStroke(KeyType.Unknown);
        }
        stringEscape = value == 0x1b;
        continue;
      }
      if (value == 0x1b || value == 0x18 || value == 0x1a) {
        resetSequence();
        pendingEscape = value == 0x1b;
        return new KeyStroke(KeyType.Unknown);
      }
      if (inputSequence.length() < 128) {
        inputSequence.append((char) value);
      } else {
        sequenceOverflow = true;
      }
      int minimumFinal = sequencePrefix >= 0x20 && sequencePrefix <= 0x2f ? 0x30 : 0x40;
      if (value >= minimumFinal && value <= 0x7e) {
        int prefix = sequencePrefix;
        String sequence = inputSequence.toString();
        boolean overflow = sequenceOverflow;
        resetSequence();
        if (overflow) {
          return new KeyStroke(KeyType.Unknown);
        }
        if (prefix == '[') {
          return decodeCsi(sequence);
        }
        return prefix == 'O' && sequence.length() == 1
            ? ss3Key((char) value) : new KeyStroke(KeyType.Unknown);
      }
    }
  }

  private void resetSequence() {
    sequencePrefix = 0;
    inputSequence.setLength(0);
    sequenceOverflow = false;
    stringEscape = false;
  }

  private static KeyStroke decodeByte(int value, boolean alt) {
    if (value == '\r' || value == '\n') {
      return new KeyStroke(KeyType.Enter, false, alt);
    }
    if (value == '\t') {
      return new KeyStroke(KeyType.Tab, false, alt);
    }
    if (value == 0x7f || value == 0x08) {
      return new KeyStroke(KeyType.Backspace, false, alt);
    }
    if (value >= 1 && value <= 26) {
      return new KeyStroke((char) ('a' + value - 1), true, alt);
    }
    if (value >= 28 && value <= 31) {
      return new KeyStroke((char) ('\\' + value - 28), true, alt);
    }
    if (value == 0) {
      return new KeyStroke(' ', true, alt);
    }
    return new KeyStroke((char) value, false, alt);
  }

  /**
   * Terminal input is a byte stream, while editor text events are Unicode characters. Keep an
   * incomplete UTF-8 sequence until all of its bytes arrive instead of turning each byte into a
   * separate Latin-1 character.
   */
  private KeyStroke readTextInput(int value, boolean alt) throws IOException {
    KeyStroke stroke = decodeTextByte(value, alt);
    while (stroke == null) {
      int continuation = readContinuationByte();
      if (continuation < 0) {
        return null;
      }
      stroke = decodeTextByte(continuation, alt);
    }
    return stroke;
  }

  private int readContinuationByte() throws IOException {
    long deadline = System.nanoTime() + INPUT_CONTINUATION_WAIT_MILLIS * 1_000_000L;
    while (input.available() == 0) {
      if (System.nanoTime() >= deadline) {
        return -1;
      }
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return -1;
      }
    }
    return input.read();
  }

  private KeyStroke decodeTextByte(int value, boolean alt) {
    if (value < 0) {
      return new KeyStroke(KeyType.EOF);
    }
    if (!decodingUtf8 && value < 0x80) {
      return decodeByte(value, alt);
    }
    if (decodingUtf8 && (value & 0xc0) != 0x80) {
      utf8Decoder.flush();
      decodingUtf8 = false;
      pendingByte = value;
      return new KeyStroke('\ufffd', false, utf8Alt);
    }
    if (!decodingUtf8) {
      decodingUtf8 = true;
      utf8Alt = alt;
    }
    String decoded = utf8Decoder.decode(new byte[] {(byte) value}, 1);
    if (decoded.isEmpty()) {
      return null;
    }
    decodingUtf8 = false;
    // The editor's text model uses UTF-16 units. Preserve every decoded unit,
    // including both halves of supplementary characters, in order.
    for (int i = 0; i < decoded.length(); i++) {
      pendingText.addLast(new KeyStroke(decoded.charAt(i), false, utf8Alt));
    }
    return pendingText.removeFirst();
  }

  private static KeyStroke ss3Key(char key) {
    return switch (key) {
      case 'P' -> new KeyStroke(KeyType.F1);
      case 'Q' -> new KeyStroke(KeyType.F2);
      case 'R' -> new KeyStroke(KeyType.F3);
      case 'S' -> new KeyStroke(KeyType.F4);
      default -> cursorKey(key, false, false, false);
    };
  }

  private KeyStroke decodeCsi(String sequence) {
    char finalCharacter = sequence.charAt(sequence.length() - 1);
    if (finalCharacter < '@' || finalCharacter > '~') {
      return new KeyStroke(KeyType.Unknown);
    }
    String parameters = sequence.substring(0, sequence.length() - 1);
    if (parameters.startsWith("<") && (finalCharacter == 'M' || finalCharacter == 'm')) {
      return decodeSgrMouse(sequence.substring(1));
    }
    if (!parameters.matches("[0-9;]*")) {
      return new KeyStroke(KeyType.Unknown);
    }
    if (finalCharacter == '~') {
      return tildeKey(parameters);
    }
    if (finalCharacter == 'u') {
      return kittyKey(parameters);
    }
    int modifier = modifier(parameters);
    return cursorKey(
        finalCharacter,
        modifier == 5 || modifier == 6 || modifier == 7 || modifier == 8,
        modifier == 3 || modifier == 4 || modifier == 7 || modifier == 8,
        modifier == 2 || modifier == 4 || modifier == 6 || modifier == 8);
  }

  private static KeyStroke cursorKey(char key, boolean ctrl, boolean alt, boolean shift) {
    return switch (key) {
      case 'A' -> new KeyStroke(KeyType.ArrowUp, ctrl, alt, shift);
      case 'B' -> new KeyStroke(KeyType.ArrowDown, ctrl, alt, shift);
      case 'C' -> new KeyStroke(KeyType.ArrowRight, ctrl, alt, shift);
      case 'D' -> new KeyStroke(KeyType.ArrowLeft, ctrl, alt, shift);
      case 'H' -> new KeyStroke(KeyType.Home, ctrl, alt, shift);
      case 'F' -> new KeyStroke(KeyType.End, ctrl, alt, shift);
      case 'Z' -> new KeyStroke(KeyType.ReverseTab, ctrl, alt, true);
      default -> new KeyStroke(KeyType.Unknown);
    };
  }

  private KeyStroke tildeKey(String parameters) {
    String[] parts = parameters.split(";", -1);
    int code = integer(parts, 0);
    int modifier = integer(parts, 1);
    boolean ctrl = modifier == 5 || modifier == 6 || modifier == 7 || modifier == 8;
    boolean alt = modifier == 3 || modifier == 4 || modifier == 7 || modifier == 8;
    boolean shift = modifier == 2 || modifier == 4 || modifier == 6 || modifier == 8;
    if (code == 27 && parts.length > 2) {
      if (integer(parts, 2) == 13) {
        return new KeyStroke(KeyType.Enter, ctrl, alt, shift);
      }
      return characterKey(integer(parts, 2), ctrl, alt, shift);
    }
    KeyType type =
        switch (code) {
          case 1, 7 -> KeyType.Home;
          case 2 -> KeyType.Insert;
          case 3 -> KeyType.Delete;
          case 4, 8 -> KeyType.End;
          case 5 -> KeyType.PageUp;
          case 6 -> KeyType.PageDown;
          case 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 23, 24, 25, 26, 28, 29, 31, 32, 33 ->
              functionKey(code);
          case 200 -> KeyType.F18;
          case 201 -> KeyType.F19;
          default -> KeyType.Unknown;
        };
    return new KeyStroke(type, ctrl, alt, shift);
  }

  private KeyStroke kittyKey(String parameters) {
    String[] parts = parameters.split(";", -1);
    int code = integer(parts, 0);
    int modifier = integer(parts, 1);
    boolean ctrl = modifier == 5 || modifier == 6 || modifier == 7 || modifier == 8;
    boolean alt = modifier == 3 || modifier == 4 || modifier == 7 || modifier == 8;
    boolean shift = modifier == 2 || modifier == 4 || modifier == 6 || modifier == 8;
    if (code == 13) {
      return new KeyStroke(KeyType.Enter, ctrl, alt, shift);
    }
    return characterKey(code, ctrl, alt, shift);
  }

  private KeyStroke characterKey(int codePoint, boolean ctrl, boolean alt, boolean shift) {
    if (!Character.isValidCodePoint(codePoint) || codePoint >= 0xd800 && codePoint <= 0xdfff) {
      return new KeyStroke(KeyType.Unknown, ctrl, alt, shift);
    }
    char[] units = Character.toChars(codePoint);
    if (units.length == 2) {
      pendingText.addLast(new KeyStroke(units[1], ctrl, alt, shift));
    }
    return new KeyStroke(units[0], ctrl, alt, shift);
  }

  private static KeyType functionKey(int code) {
    return KeyType.values()[KeyType.F1.ordinal() + functionOffset(code)];
  }

  private static int functionOffset(int code) {
    return switch (code) {
      case 11 -> 0;
      case 12 -> 1;
      case 13 -> 2;
      case 14 -> 3;
      case 15 -> 4;
      case 17 -> 5;
      case 18 -> 6;
      case 19 -> 7;
      case 20 -> 8;
      case 21 -> 9;
      case 23 -> 10;
      case 24 -> 11;
      case 25 -> 12;
      case 26 -> 13;
      case 28 -> 14;
      case 29 -> 15;
      case 31 -> 16;
      case 32 -> 17;
      case 33 -> 18;
      default -> 0;
    };
  }

  private static int modifier(String parameters) {
    String[] parts = parameters.split(";", -1);
    return integer(parts, parts.length - 1);
  }

  private static int integer(String[] parts, int index) {
    if (index < 0 || index >= parts.length || parts[index].isEmpty()) {
      return 0;
    }
    try {
      return Integer.parseInt(parts[index]);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private KeyStroke decodeSgrMouse(String sequence) {
    if (sequence.isEmpty()) {
      return new KeyStroke(KeyType.Unknown);
    }
    char terminator = sequence.charAt(sequence.length() - 1);
    if (terminator != 'M' && terminator != 'm') {
      return new KeyStroke(KeyType.Unknown);
    }
    String[] parts = sequence.substring(0, sequence.length() - 1).split(";", -1);
    if (parts.length != 3) {
      return new KeyStroke(KeyType.Unknown);
    }
    try {
      int code = Integer.parseInt(parts[0]);
      int column = Integer.parseInt(parts[1]) - 1;
      int row = Integer.parseInt(parts[2]) - 1;
      if (column < 0 || row < 0) {
        return new KeyStroke(KeyType.Unknown);
      }
      return new MouseAction(
          mouseActionType(code, terminator == 'm'),
          mouseButton(code),
          new MouseAction.Position(column, row));
    } catch (NumberFormatException e) {
      return new KeyStroke(KeyType.Unknown);
    }
  }

  private static MouseActionType mouseActionType(int code, boolean release) {
    if (release || (code & 3) == 3 && (code & 32) == 0) {
      return MouseActionType.CLICK_RELEASE;
    }
    if ((code & 64) != 0) {
      return (code & 3) == 0 ? MouseActionType.SCROLL_UP : MouseActionType.SCROLL_DOWN;
    }
    if ((code & 32) != 0) {
      return (code & 3) == 3 ? MouseActionType.MOVE : MouseActionType.DRAG;
    }
    return MouseActionType.CLICK_DOWN;
  }

  private static int mouseButton(int code) {
    if ((code & 64) != 0) {
      return (code & 3) == 0 ? 4 : 5;
    }
    int button = code & 3;
    return button == 3 ? 0 : button + 1;
  }

  @Override
  public void setCursorPosition(int column, int row) {
    try {
      writeTerminalControl("\u001b[" + (row + 1) + ';' + (column + 1) + 'H');
    } catch (IOException e) {
      throw new IllegalStateException("Unable to position terminal cursor", e);
    }
  }

  @Override
  public void setCursorVisible(boolean visible) {
    try {
      writeTerminalControl(visible ? SHOW_CURSOR : HIDE_CURSOR);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to update terminal cursor", e);
    }
  }

  @Override
  public void setCursorShape(TerminalCursorShape shape) {
    try {
      writeTerminalControl((shape == null ? TerminalCursorShape.DEFAULT : shape).escapeSequence());
    } catch (IOException e) {
      throw new IllegalStateException("Unable to update terminal cursor shape", e);
    }
  }

  private void writeTerminalControl(String text) throws IOException {
    if (!asynchronousOutput) {
      writeSynchronously(text);
      return;
    }
    synchronized (outputQueueLock) {
      if (outputWriteInFlight) {
        pendingTerminalControl += text;
        return;
      }
      outputWriteInFlight = true;
      writeAsynchronously(text);
    }
  }

  private void writeAsynchronously(String initialOutput) {
    Thread.ofVirtual()
        .name("swim-terminal-output")
        .start(
            () -> {
              String outputText = initialOutput;
              boolean requestRedraw = false;
              try {
                while (outputText != null) {
                  writeSynchronously(outputText);
                  synchronized (outputQueueLock) {
                    if (!pendingTerminalControl.isEmpty()) {
                      outputText = pendingTerminalControl;
                      pendingTerminalControl = "";
                    } else {
                      outputWriteInFlight = false;
                      requestRedraw = redrawRequestedWhileWriting;
                      redrawRequestedWhileWriting = false;
                      outputText = null;
                    }
                  }
                }
              } catch (IOException ignored) {
                synchronized (outputQueueLock) {
                  outputWriteInFlight = false;
                  pendingTerminalControl = "";
                  redrawRequestedWhileWriting = false;
                }
              }
              if (requestRedraw) {
                EventThread.getInstance()
                    .enqueue(
                        new RunnableEvent(
                            () -> {
                              var window = Window.getInstance();
                              if (window != null) {
                                window.update(false);
                              }
                            }));
              }
            });
  }

  private void writeSynchronously(String text) throws IOException {
    if (text.isEmpty()) {
      return;
    }
    synchronized (output) {
      output.write(text.getBytes(StandardCharsets.UTF_8));
      output.flush();
    }
  }

  private static TerminalDimensions requireDimensions(TerminalDimensions dimensions) {
    return Objects.requireNonNull(dimensions, "dimensionsSupplier returned null");
  }
}
