package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class NemoModelCatalogTest {
  @Test
  void accountProtocolInitializesAndFollowsPaginationWithoutStartingTurns() throws Exception {
    var process = new FakeProcess("""
        {"id":1,"result":{}}
        {"id":2,"result":{"account":{"type":"chatgpt"}}}
        {"method":"notification","params":{}}
        {"id":3,"result":{"data":[{"model":"first"}],"nextCursor":"page-two"}}
        {"id":4,"result":{"data":[{"model":"second"}],"nextCursor":null}}
        """);
    var models = NemoModelCatalog.readCatalog(process);
    assertEquals(List.of("first", "second"), models.stream().map(NemoModelCatalog.Model::id).toList());
    String sent = process.requests.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(sent.contains("initialize"));
    assertTrue(sent.contains("initialized"));
    assertTrue(sent.contains("\"cursor\":\"page-two\""));
    assertFalse(sent.contains("thread/start"));
    assertFalse(sent.contains("turn/start"));
  }

  @Test
  void providerErrorDetailsAreNotExposed() {
    var failure = assertThrows(java.io.IOException.class, () -> NemoModelCatalog.readCatalog(
        new FakeProcess("{\"id\":1,\"error\":{\"message\":\"secret-test-marker\"}}\n")));
    assertFalse(failure.getMessage().contains("secret-test-marker"));
  }

  @Test
  void unsignedCatalogIsNotPresentedAsAccountAvailability() {
    assertThrows(java.io.IOException.class, () -> NemoModelCatalog.readCatalog(new FakeProcess("""
        {"id":1,"result":{}}
        {"id":2,"result":{"account":null}}
        """)));
  }

  private static final class FakeProcess extends Process {
    final java.io.ByteArrayOutputStream requests = new java.io.ByteArrayOutputStream();
    final java.io.InputStream replies;
    FakeProcess(String text) { replies = new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    @Override public java.io.OutputStream getOutputStream() { return requests; }
    @Override public java.io.InputStream getInputStream() { return replies; }
    @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
    @Override public int waitFor() { return 0; }
    @Override public int exitValue() { return 0; }
    @Override public void destroy() { }
  }
  @Test
  void discoversNewModelsAndEffortsWithoutAHardCodedEnum() throws Exception {
    var models = NemoModelCatalog.parseCodexPage(JsonParser.parseString("""
        {"data":[
          {"id":"future-id","model":"future-model","displayName":"Future model",
           "defaultReasoningEffort":"adaptive-new", "supportedReasoningEfforts":[
             {"reasoningEffort":"low","description":"Quick"},
             {"reasoningEffort":"adaptive-new","description":"A newly added effort"}]},
          {"model":"small","supportedReasoningEfforts":[{"reasoningEffort":"low"}]},
          {"model":"hidden-model","hidden":true}, {"model":"no-effort-metadata"}
        ],"nextCursor":null}
        """).getAsJsonObject());
    assertEquals(List.of("future-model", "small", "no-effort-metadata"), models.stream().map(NemoModelCatalog.Model::id).toList());
    assertEquals("Future model", models.getFirst().label());
    assertEquals("adaptive-new", models.getFirst().defaultEffort());
    assertEquals(List.of("low", "adaptive-new"), models.getFirst().efforts().stream().map(NemoModelCatalog.Effort::value).toList());
    assertEquals(1, models.get(1).efforts().size());
    assertTrue(models.getLast().efforts().isEmpty());
  }

  @Test
  void openAiFallbackDoesNotInventModelCapabilities() {
    var config = NemoClient.Configuration.builder().provider("chatgpt").model("current")
        .modelOptions(List.of("current", "new-model")).reasoningEffortOptions(List.of("high", "ultra")).build();
    var models = NemoModelCatalog.configuredModels(config);
    assertEquals(2, models.size());
    assertTrue(models.stream().allMatch(model -> model.efforts().isEmpty()));
  }

  @Test
  void malformedCatalogDoesNotSilentlyBecomeAnEmptyMenu() {
    assertThrows(java.io.IOException.class, () -> NemoModelCatalog.parseCodexPage(
        JsonParser.parseString("{}").getAsJsonObject()));
  }
}
