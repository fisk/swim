package org.fisk.swim.nemo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.fisk.swim.api.SwimHttpClients;

/** Model discovery only: never starts a Codex thread or an inference request. */
final class NemoModelCatalog {
  @FunctionalInterface
  interface Loader { List<Model> load(NemoClient.Configuration configuration) throws Exception; }
  record Effort(String value, String description) {}
  record Model(String id, String label, List<Effort> efforts, String defaultEffort) {
    Model { efforts = List.copyOf(efforts); }
  }

  static List<Model> load(NemoClient.Configuration config) throws Exception {
    if ("chatgpt".equals(config.provider())) {
      return codexModels();
    }
    if (List.of("openai", "responses", "openai-responses").contains(config.provider())) {
      return apiModels(config);
    }
    return configuredModels(config);
  }

  static List<Model> configuredModels(NemoClient.Configuration config) {
    var ids = new java.util.LinkedHashSet<String>();
    if (!config.model().isBlank()) ids.add(config.model());
    ids.addAll(config.modelOptions());
    // An offline config is not authoritative capability metadata for OpenAI.
    boolean openai = List.of("chatgpt", "openai", "responses", "openai-responses").contains(config.provider());
    var efforts = openai ? List.<Effort>of() : config.reasoningEffortOptions().stream()
        .map(value -> new Effort(value, "Configured reasoning level")).toList();
    return ids.stream().map(id -> new Model(id, id, efforts, "")).toList();
  }

  private static List<Model> apiModels(NemoClient.Configuration config) throws Exception {
    String base = config.baseUrl().replaceAll("/+$", "");
    if (base.endsWith("/responses")) base = base.substring(0, base.length() - 10);
    var request = HttpRequest.newBuilder(URI.create(base + "/models"))
        .timeout(Duration.ofSeconds(15)).GET();
    var headers = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    headers.putAll(config.headers());
    if (!config.apiKey().isBlank()) headers.putIfAbsent("Authorization", "Bearer " + config.apiKey());
    if (!config.organization().isBlank()) headers.putIfAbsent("OpenAI-Organization", config.organization());
    if (!config.project().isBlank()) headers.putIfAbsent("OpenAI-Project", config.project());
    headers.forEach(request::header);
    HttpResponse<String> response;
    try (var client = SwimHttpClients.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    if (response.statusCode() / 100 != 2) throw new IOException("Model discovery HTTP " + response.statusCode());
    var models = new ArrayList<Model>();
    for (var entry : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data")) {
      String id = string(entry.getAsJsonObject(), "id");
      if (!id.isBlank()) models.add(new Model(id, id, List.of(), ""));
    }
    if (models.isEmpty()) throw new IOException("Empty model catalog");
    return List.copyOf(models);
  }

  private static List<Model> codexModels() throws Exception {
    // Use the signed-in Codex account and its supported public protocol, rather
    // than depending on an undocumented ChatGPT HTTP endpoint or reading tokens.
    Process process = new ProcessBuilder("codex", "app-server", "-c", "model_provider=\"openai\"")
        .directory(Path.of(System.getProperty("user.home")).toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var future = executor.submit(() -> readCatalog(process));
      try {
        return future.get(20, TimeUnit.SECONDS);
      } finally {
        // The installed CLI may be a wrapper around a native child process.
        // Cancel the whole discovery process tree so a timeout cannot leave a
        // child holding the reader's pipe open and block executor shutdown.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        future.cancel(true);
      }
    }
  }

  static List<Model> readCatalog(Process process) throws Exception {
    try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
         var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
      var initialize = JsonParser.parseString("""
          {"clientInfo":{"name":"swim_nemo","title":"SWIM Nemo","version":"1.0"}}
          """).getAsJsonObject();
      rpc(writer, 1, "initialize", initialize);
      reply(reader, 1);
      writer.write("{\"method\":\"initialized\",\"params\":{}}\n");
      writer.flush();
      var authParams = new JsonObject();
      authParams.addProperty("refreshToken", false);
      rpc(writer, 2, "account/read", authParams);
      var account = reply(reader, 2).get("account");
      if (account == null || !account.isJsonObject()
          || !"chatgpt".equals(string(account.getAsJsonObject(), "type"))) {
        throw new IOException("Codex is not signed in with ChatGPT");
      }
      var models = new LinkedHashMap<String, Model>();
      String cursor = "";
      var seenCursors = new java.util.HashSet<String>();
      for (int page = 0; page < 100; page++) {
        var params = new JsonObject();
        params.addProperty("limit", 100);
        params.addProperty("includeHidden", false);
        if (!cursor.isBlank()) params.addProperty("cursor", cursor);
        int id = page + 3;
        rpc(writer, id, "model/list", params);
        var result = reply(reader, id);
        for (Model model : parseCodexPage(result)) models.put(model.id(), model);
        cursor = string(result, "nextCursor");
        if (cursor.isBlank()) {
          if (models.isEmpty()) throw new IOException("Empty model catalog");
          return List.copyOf(models.values());
        }
        if (!seenCursors.add(cursor)) throw new IOException("Repeated catalog cursor");
      }
      throw new IOException("Model catalog page limit exceeded");
    }
  }

  static List<Model> parseCodexPage(JsonObject page) throws IOException {
    if (!page.has("data") || !page.get("data").isJsonArray()) throw new IOException("Invalid model catalog");
    var models = new ArrayList<Model>();
    for (var value : page.getAsJsonArray("data")) {
      var entry = value.getAsJsonObject();
      if (entry.has("hidden") && entry.get("hidden").getAsBoolean()) continue;
      String id = string(entry, "model");
      if (id.isBlank()) id = string(entry, "id");
      if (id.isBlank()) continue;
      String label = string(entry, "displayName");
      var efforts = new ArrayList<Effort>();
      if (entry.has("supportedReasoningEfforts") && entry.get("supportedReasoningEfforts").isJsonArray()) {
        for (var effort : entry.getAsJsonArray("supportedReasoningEfforts")) {
          String name = string(effort.getAsJsonObject(), "reasoningEffort");
          if (!name.isBlank()) efforts.add(new Effort(name, string(effort.getAsJsonObject(), "description")));
        }
      }
      models.add(new Model(id, label.isBlank() ? id : label, efforts, string(entry, "defaultReasoningEffort")));
    }
    return List.copyOf(models);
  }

  private static void rpc(OutputStreamWriter writer, int id, String method, JsonObject params) throws IOException {
    var request = new JsonObject();
    request.addProperty("id", id);
    request.addProperty("method", method);
    request.add("params", params);
    writer.write(request + "\n");
    writer.flush();
  }

  private static JsonObject reply(BufferedReader reader, int id) throws IOException {
    for (String line; (line = reader.readLine()) != null;) {
      var message = JsonParser.parseString(line).getAsJsonObject();
      if (!message.has("id") || message.get("id").isJsonNull() || message.get("id").getAsInt() != id) continue;
      if (message.has("error")) throw new IOException("Codex model discovery failed");
      return message.getAsJsonObject("result");
    }
    throw new IOException("Codex model discovery disconnected");
  }

  private static String string(JsonObject object, String key) {
    return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
  }
}
