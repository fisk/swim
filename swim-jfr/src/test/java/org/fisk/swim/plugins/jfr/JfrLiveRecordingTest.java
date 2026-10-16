package org.fisk.swim.plugins.jfrmetrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;

class JfrLiveRecordingTest {
  @Test
  void enablesZgcDemandWhenThisVmSupportsIt() {
    try (Recording recording = JfrLiveRecording.createRecording()) {
      assertEquals("true", recording.getSettings().get("jdk.ZGCDemand#enabled"));
    }
  }
}
