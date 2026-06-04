package org.fisk.swim.plugins.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.fisk.swim.slack.SlackDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class SlackHttpClientTest {
    @TempDir
    Path tempDir;

    @Test
    void normalizeTextRewritesMentionsLinksAndEntities() {
        String normalized = SlackHttpClient.normalizeText(
                "Hi <@U2> &amp; please see <https://example.com|docs> in <#C1|general> <!channel>",
                Map.of("U2", "Bob"));

        assertEquals("Hi @Bob & please see docs (https://example.com) in #general @channel", normalized);
    }

    @Test
    void clientLoadsChannelsMessagesThreadsAndSends() throws Exception {
        SlackPaths paths = new SlackPaths(tempDir, tempDir.resolve("slack"), tempDir.resolve("slack/workspaces.json"));
        Files.createDirectories(paths.slackHome());
        Files.writeString(paths.workspacesPath(), """
                {
                  "workspaces": [
                    {
                      "id": "work",
                      "label": "Work Slack",
                      "token": "xoxb-test"
                    }
                  ]
                }
                """);
        FakeTransport transport = new FakeTransport();
        SlackHttpClient client = new SlackHttpClient(paths, transport);

        assertEquals("Work Slack", client.snapshot().workspaces().getFirst().label());
        assertEquals("#general", client.loadChannels("work").getFirst().displayName());

        var page = client.loadMessages("work", "C1", "", 0, 10);
        assertEquals(2, page.messages().size());
        assertEquals("Hello @Bob & docs (https://example.com)", page.messages().getFirst().text());

        var thread = client.loadThread("work", "C1", "200.000200");
        assertEquals(2, thread.messages().size());
        assertEquals("me", thread.messages().getFirst().userDisplayName());

        var send = client.sendMessage(new SlackDraft("work", "C1", "", "Ship it"));
        assertTrue(send.success());
        assertEquals("chat.postMessage", transport.lastMethod);
        assertEquals("Ship it", transport.lastPostBody.get("text").getAsString());
    }

    private static final class FakeTransport implements SlackHttpClient.SlackTransport {
        private String lastMethod;
        private JsonObject lastPostBody;

        @Override
        public JsonObject invoke(String token, String method, String httpMethod, Map<String, String> query, JsonObject jsonBody) {
            lastMethod = method;
            if ("POST".equals(httpMethod)) {
                lastPostBody = jsonBody;
            }
            return switch (method) {
            case "auth.test" -> auth();
            case "users.list" -> users();
            case "conversations.list" -> conversations();
            case "conversations.history" -> history();
            case "conversations.replies" -> replies();
            case "chat.postMessage" -> ok();
            default -> ok();
            };
        }

        private static JsonObject auth() {
            JsonObject object = ok();
            object.addProperty("team", "Work");
            object.addProperty("user", "me");
            object.addProperty("user_id", "U1");
            return object;
        }

        private static JsonObject users() {
            JsonObject object = ok();
            JsonArray members = new JsonArray();
            members.add(user("U1", "me"));
            members.add(user("U2", "Bob"));
            object.add("members", members);
            object.add("response_metadata", new JsonObject());
            return object;
        }

        private static JsonObject conversations() {
            JsonObject object = ok();
            JsonArray channels = new JsonArray();
            JsonObject general = new JsonObject();
            general.addProperty("id", "C1");
            general.addProperty("name", "general");
            general.addProperty("is_private", false);
            general.addProperty("is_im", false);
            general.addProperty("is_mpim", false);
            general.addProperty("updated", 200L);
            channels.add(general);
            object.add("channels", channels);
            object.add("response_metadata", new JsonObject());
            return object;
        }

        private static JsonObject history() {
            JsonObject object = ok();
            JsonArray messages = new JsonArray();
            messages.add(message("200.000200", "U1", "Hello <@U2> &amp; <https://example.com|docs>", 1));
            messages.add(message("100.000100", "U2", "Earlier message", 0));
            object.add("messages", messages);
            object.add("response_metadata", new JsonObject());
            return object;
        }

        private static JsonObject replies() {
            JsonObject object = ok();
            JsonArray messages = new JsonArray();
            messages.add(message("200.000200", "U1", "Hello <@U2> &amp; <https://example.com|docs>", 1));
            messages.add(message("201.000201", "U2", "Looks good", 0));
            object.add("messages", messages);
            object.add("response_metadata", new JsonObject());
            return object;
        }

        private static JsonObject ok() {
            JsonObject object = new JsonObject();
            object.addProperty("ok", true);
            return object;
        }

        private static JsonObject user(String id, String displayName) {
            JsonObject user = new JsonObject();
            user.addProperty("id", id);
            JsonObject profile = new JsonObject();
            profile.addProperty("display_name", displayName);
            user.add("profile", profile);
            return user;
        }

        private static JsonObject message(String ts, String user, String text, int replyCount) {
            JsonObject message = new JsonObject();
            message.addProperty("ts", ts);
            message.addProperty("thread_ts", ts);
            message.addProperty("user", user);
            message.addProperty("text", text);
            message.addProperty("reply_count", replyCount);
            return message;
        }
    }
}
