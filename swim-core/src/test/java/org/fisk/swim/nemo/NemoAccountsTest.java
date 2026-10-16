package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NemoAccountsTest {
  @TempDir Path directory;

  @Test void connectionsDoNotInheritCredentialsOrChangePermissions() throws Exception {
    Path config = directory.resolve("nemo.conf");
    Files.writeString(config, """
        account.personal.provider=chatgpt
        account.personal.api_key=original-secret
        account.personal.model=original-model
        account.research.provider=responses
        account.research.api_key=research-secret
        account.research.base_url=https://api.example.invalid/v1
        account.research.model=research-model
        account.research.tool.permission_mode=full-access
        """);
    var base = NemoClient.Configuration.builder().provider("chatgpt").apiKey("original-secret")
        .headers(Map.of("ChatGPT-Account-Id", "old-account", "Authorization", "Bearer old-secret"))
        .toolPermissionMode("read-only").build();
    var selected = NemoAccounts.load(config, "research", base);
    assertEquals("responses", selected.provider());
    assertEquals("research-secret", selected.apiKey());
    assertEquals("Bearer research-secret", selected.headers().get("Authorization"));
    assertFalse(selected.headers().containsKey("ChatGPT-Account-Id"));
    assertEquals(base.toolPermissionMode(), selected.toolPermissionMode());
    assertEquals("research-model", selected.model());
    var restored = NemoAccounts.load(config, "personal", selected);
    assertEquals("original-secret", restored.apiKey());
    assertEquals("chatgpt", restored.provider());
    assertEquals(java.util.List.of("personal", "research"), NemoAccounts.list(config));
    assertEquals("personal", NemoClient.loadConfiguration(config).accountProfile());
  }

  @Test void declarationOrderNotAlphabeticalDeterminesDefaultForPropertiesAndJson() throws Exception {
    for (String raw : java.util.List.of(
        "account.zeta.provider=responses\naccount.zeta.api_key=z\naccount.alpha.provider=responses\naccount.alpha.api_key=a\n",
        "{\"accounts\":{\"zeta\":{\"provider\":\"responses\",\"api_key\":\"z\"},\"alpha\":{\"provider\":\"responses\",\"api_key\":\"a\"}}}")) {
      var config = NemoAccounts.configuration(raw, null);
      assertEquals("zeta", config.accountProfile());
      assertEquals("z", config.apiKey());
      assertEquals("a", NemoAccounts.configuration(raw, "alpha").apiKey());
      assertThrows(IllegalArgumentException.class, () -> NemoAccounts.configuration(raw, "missing"));
      assertThrows(IllegalArgumentException.class, () -> NemoAccounts.configuration(raw, "default"));
    }
  }

  @Test void missingAndOutsideProfilesNeverFallBack() {
    var base = NemoClient.Configuration.builder().apiKey("default-secret").build();
    for (String name : new String[] {"missing", "../nemo", "/tmp/key", ""}) {
      assertThrows(IllegalArgumentException.class, () -> NemoAccounts.load(directory.resolve("nemo.conf"), name, base));
    }
  }
}
