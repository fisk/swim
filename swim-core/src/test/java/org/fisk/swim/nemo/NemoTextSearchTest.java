package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.Reader;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NemoTextSearchTest {
  @Test
  void matchesExistingLineAndSnippetSemanticsAcrossBufferBoundaries() throws Exception {
    for (String query : List.of("needle", "", "ababac", "x".repeat(900))) {
      for (int prefix : new int[] {0, 1, 170, 511, 512, 8190, 8192, 20000}) {
        for (int suffix : new int[] {0, 1, 20, 512, 9000}) {
          String text = "first\r\n\r\n" + "a".repeat(prefix) + query + "b".repeat(suffix)
              + "\rfinal " + query + "\n";
          var actual = new ArrayList<NemoTextSearch.Match>();
          new NemoTextSearch(query).scan(new StringReader(text), m -> { actual.add(m); return true; });
          assertEquals(reference(text, query), actual,
              "query length=" + query.length() + " prefix=" + prefix + " suffix=" + suffix);
        }
      }
    }
  }

  @Test
  void handlesCrLfSplitAcrossReadsAndDoesNotMatchAcrossLines() throws Exception {
    String text = "need\r\nle\rneedle\n\nneedle";
    var actual = new ArrayList<NemoTextSearch.Match>();
    try (Reader reader = new StringReader(text) {
      @Override public int read(char[] chars, int offset, int count) throws java.io.IOException {
        return super.read(chars, offset, Math.min(1, count));
      }
    }) {
      new NemoTextSearch("needle").scan(reader, m -> { actual.add(m); return true; });
    }
    assertEquals(reference(text, "needle"), actual);
  }

  @Test
  void stopsReadingWhenResultConsumerHasEnough() throws Exception {
    Reader reader = new Reader() {
      private boolean read;
      @Override public int read(char[] chars, int offset, int count) {
        assertFalse(read, "Search should stop before requesting another block");
        read = true;
        "needle\n".getChars(0, 7, chars, offset);
        return 7;
      }
      @Override public void close() {}
    };
    assertFalse(new NemoTextSearch("needle").scan(reader, match -> false));
  }

  @Test
  void searchingHugeLinesDoesNotAllocateProportionallyToTheirLength() throws Exception {
    var bean = ManagementFactory.getThreadMXBean();
    // Keep the optional allocation probe out of the application's module dependencies.
    Class<?> extendedBean = Class.forName("com.sun.management.ThreadMXBean");
    Assumptions.assumeTrue(extendedBean.isInstance(bean));
    Assumptions.assumeTrue((boolean) extendedBean.getMethod("isThreadAllocatedMemorySupported").invoke(bean));
    extendedBean.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
    var allocatedBytes = extendedBean.getMethod("getThreadAllocatedBytes", long.class);
    for (String query : List.of("needle", "absent")) {
      var matches = new ArrayList<NemoTextSearch.Match>();
      long tid = Thread.currentThread().threadId();
      long before = (long) allocatedBytes.invoke(bean, tid);
      new NemoTextSearch(query).scan(new HugeLineReader(64L * 1024 * 1024), m -> {
        matches.add(m);
        return true;
      });
      long bytes = (long) allocatedBytes.invoke(bean, tid) - before;
      assertTrue(bytes < 1024 * 1024, "Allocated " + bytes + " bytes for one long line");
      assertEquals(query.equals("needle") ? 1 : 0, matches.size());
      if (!matches.isEmpty()) {
        assertTrue(matches.getFirst().snippet().endsWith("needle"));
        assertTrue(matches.getFirst().snippet().length() <= 514);
      }
    }
  }

  private static final class HugeLineReader extends Reader {
    private final long length;
    private long position;
    HugeLineReader(long length) { this.length = length; }
    @Override public int read(char[] chars, int offset, int count) {
      if (position == length) return -1;
      int n = (int) Math.min(count, length - position);
      for (int i = 0; i < n; i++, position++) {
        chars[offset + i] = position < length - 6 ? 'a' : "needle".charAt((int) (position - length + 6));
      }
      return n;
    }
    @Override public void close() {}
  }

  private static List<NemoTextSearch.Match> reference(String text, String query) throws Exception {
    var matches = new ArrayList<NemoTextSearch.Match>();
    try (var reader = new BufferedReader(new StringReader(text))) {
      long lineNumber = 0;
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (!line.contains(query)) continue;
        String snippet = line;
        if (line.length() > 512) {
          int start = Math.max(0, line.indexOf(query) - 512 / 3);
          int end = Math.min(line.length(), start + 512);
          start = Math.max(0, end - 512);
          snippet = (start > 0 ? "…" : "") + line.substring(start, end) + (end < line.length() ? "…" : "");
        }
        matches.add(new NemoTextSearch.Match(lineNumber, snippet));
      }
    }
    return matches;
  }
}
