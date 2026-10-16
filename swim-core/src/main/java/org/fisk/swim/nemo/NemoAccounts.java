package org.fisk.swim.nemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.io.StringReader;
import java.io.StringWriter;
import com.google.gson.JsonParser;

/** Ordered accounts in nemo.conf contain connection settings, not tool permissions or history. */
final class NemoAccounts {
  static List<String> list(Path config) throws IOException {
    return List.copyOf(parse(Files.readString(config)).accounts().keySet());
  }

  static NemoClient.Configuration load(Path config, String name, NemoClient.Configuration base) {
    try {
      var selected = configuration(Files.readString(config), name);
      return base.withConnection(selected).withAccountProfile(selected.accountProfile());
    } catch (IOException e) {
      throw new IllegalArgumentException("Account configuration unavailable");
    }
  }

  private record Document(String shared, LinkedHashMap<String, String> accounts) {}

  static NemoClient.Configuration configuration(String raw, String requested) throws IOException {
    var document = parse(raw);
    String name = requested;
    if (name == null || name.isBlank()) {
      name = document.accounts().keySet().iterator().next();
    }
    String account = document.accounts().get(name);
    if (account == null) throw new IllegalArgumentException("Account configuration unavailable");
    var shared = NemoClient.loadFlatConfiguration(document.shared());
    return shared.withConnection(NemoClient.loadFlatConfiguration(account)).withAccountProfile(name);
  }

  private static Document parse(String raw) throws IOException {
    var accounts = new LinkedHashMap<String, String>();
    if (raw.stripLeading().startsWith("{")) {
      var root = JsonParser.parseString(raw).getAsJsonObject();
      if (root.has("accounts")) {
        for (var entry : root.remove("accounts").getAsJsonObject().entrySet()) {
          if (!valid(entry.getKey())) throw new IllegalArgumentException("Invalid account name");
          accounts.put(entry.getKey(), entry.getValue().getAsJsonObject().toString());
        }
        if (accounts.isEmpty()) throw new IllegalArgumentException("At least one account is required");
        root.keySet().removeIf(NemoAccounts::connectionKey);
      } else accounts.put("default", raw);
      return new Document(root.toString(), accounts);
    }
    var ordered = new LinkedHashMap<String, String>();
    var properties = new Properties() {
      @Override public synchronized Object put(Object key, Object value) {
        ordered.put(key.toString(), value.toString());
        return super.put(key, value);
      }
    };
    properties.load(new StringReader(raw));
    var profiles = new LinkedHashMap<String, Properties>();
    var shared = new Properties();
    for (var entry : ordered.entrySet()) {
      if (entry.getKey().startsWith("account.")) {
        String[] parts = entry.getKey().split("\\.", 3);
        if (parts.length != 3 || !valid(parts[1]) || parts[2].isBlank()) throw new IllegalArgumentException("Invalid account setting");
        profiles.computeIfAbsent(parts[1], ignored -> new Properties()).setProperty(parts[2], entry.getValue());
      } else shared.setProperty(entry.getKey(), entry.getValue());
    }
    if (profiles.isEmpty()) accounts.put("default", raw);
    else {
      shared.keySet().removeIf(key -> connectionKey(key.toString()));
      for (var entry : profiles.entrySet()) accounts.put(entry.getKey(), serialize(entry.getValue()));
    }
    return new Document(serialize(shared), accounts);
  }

  private static boolean connectionKey(String key) {
    return java.util.Set.of("provider", "vendor", "api_key", "api_key_env", "api_key_command",
        "apiKey", "apiKeyEnv", "apiKeyCommand", "base_url", "baseUrl", "responses_url", "responsesUrl",
        "organization", "project", "model", "model_options", "modelOptions", "models",
        "reasoning_effort", "reasoningEffort", "reasoning_effort_options", "reasoningEffortOptions",
        "context_window_tokens", "contextWindowTokens", "max_output_tokens", "maxOutputTokens",
        "temperature", "top_p", "topP", "headers", "queryParameters", "query_parameters",
        "customParameters", "custom_parameters").contains(key)
        || key.startsWith("header.") || key.startsWith("query.") || key.startsWith("parameter.");
  }

  private static String serialize(Properties properties) throws IOException {
    var writer = new StringWriter();
    properties.store(writer, null);
    return writer.toString();
  }

  private static boolean valid(String name) {
    return name != null && name.matches("[A-Za-z0-9_-]{1,64}");
  }
}
