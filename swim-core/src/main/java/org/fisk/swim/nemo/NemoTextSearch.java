package org.fisk.swim.nemo;

import java.io.IOException;
import java.io.Reader;
import java.util.function.Predicate;

/** Literal, line-oriented search using space bounded by the query and snippet sizes. */
final class NemoTextSearch {
  private static final int SNIPPET_CHARS = 512;
  private final String query;
  private final int[] fallback;
  private final char[] buffer = new char[8192];
  private final char[] history;
  private long length;
  private int historyPosition;
  private int matched;
  private long firstMatch;
  private String frozenSnippet;

  record Match(long lineNumber, String snippet) {}

  NemoTextSearch(String query) {
    this.query = query;
    fallback = new int[query.length()];
    for (int i = 1, prefix = 0; i < query.length(); i++) {
      while (prefix > 0 && query.charAt(i) != query.charAt(prefix)) {
        prefix = fallback[prefix - 1];
      }
      if (query.charAt(i) == query.charAt(prefix)) {
        prefix++;
      }
      fallback[i] = prefix;
    }
    // Retain the start of even a query longer than the displayed snippet.
    history = new char[Math.addExact(query.length(), 2 * SNIPPET_CHARS)];
  }

  /** Returns false if the visitor stopped the search. CR, LF and CRLF match readLine semantics. */
  boolean scan(Reader reader, Predicate<Match> visitor) throws IOException {
    resetLine();
    long lineNumber = 1;
    boolean skipLf = false;
    int count;
    while ((count = reader.read(buffer)) != -1) {
      for (int i = 0; i < count; i++) {
        char c = buffer[i];
        if (skipLf && c == '\n') {
          skipLf = false;
          continue;
        }
        skipLf = c == '\r';
        if (c == '\r' || c == '\n') {
          if (firstMatch >= 0 && !visitor.test(new Match(lineNumber, snippet()))) {
            return false;
          }
          lineNumber++;
          resetLine();
        } else {
          accept(c);
        }
      }
    }
    return length == 0 || firstMatch < 0 || visitor.test(new Match(lineNumber, snippet()));
  }

  private void resetLine() {
    length = 0;
    historyPosition = 0;
    matched = 0;
    firstMatch = query.isEmpty() ? 0 : -1;
    frozenSnippet = null;
  }

  private void accept(char c) {
    if (frozenSnippet != null) {
      length++;
      return;
    }
    history[historyPosition] = c;
    if (++historyPosition == history.length) {
      historyPosition = 0;
    }
    length++;
    if (firstMatch < 0) {
      while (matched > 0 && c != query.charAt(matched)) {
        matched = fallback[matched - 1];
      }
      if (c == query.charAt(matched)) {
        matched++;
      }
      if (matched == query.length()) {
        firstMatch = length - query.length();
      }
    }
    if (firstMatch >= 0 && frozenSnippet == null && length >= snippetEnd()) {
      frozenSnippet = slice(snippetEnd() - SNIPPET_CHARS, snippetEnd());
    }
  }

  private long snippetEnd() {
    return Math.max(0, firstMatch - SNIPPET_CHARS / 3) + SNIPPET_CHARS;
  }

  private String snippet() {
    if (length <= SNIPPET_CHARS) {
      return slice(0, length);
    }
    long end = Math.min(length, snippetEnd());
    long start = Math.max(0, end - SNIPPET_CHARS);
    String text = frozenSnippet == null ? slice(start, end) : frozenSnippet;
    return (start > 0 ? "…" : "") + text + (end < length ? "…" : "");
  }

  private String slice(long start, long end) {
    var text = new StringBuilder((int) (end - start));
    for (long i = start; i < end; i++) {
      text.append(history[(int) (i % history.length)]);
    }
    return text.toString();
  }
}
