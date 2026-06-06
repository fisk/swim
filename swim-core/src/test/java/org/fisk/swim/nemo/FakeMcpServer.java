package org.fisk.swim.nemo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class FakeMcpServer {
    private FakeMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                if (!request.has("id")) {
                    continue;
                }
                String method = request.get("method").getAsString();
                JsonElement id = request.get("id");
                JsonObject response = switch (method) {
                case "initialize" -> response(id, initializeResult());
                case "tools/list" -> response(id, toolsResult());
                case "tools/call" -> response(id, callResult(request.getAsJsonObject("params")));
                default -> error(id, -32601, "Unknown method: " + method);
                };
                System.out.println(response);
                System.out.flush();
            }
        }
    }

    private static JsonObject initializeResult() {
        var result = new JsonObject();
        result.addProperty("protocolVersion", "2025-06-18");
        var capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);
        var serverInfo = new JsonObject();
        serverInfo.addProperty("name", "fake-mcp");
        serverInfo.addProperty("version", "1.0");
        result.add("serverInfo", serverInfo);
        return result;
    }

    private static JsonObject toolsResult() {
        var result = new JsonObject();
        var tools = new JsonArray();
        var tool = new JsonObject();
        tool.addProperty("name", "echo");
        tool.addProperty("title", "Echo");
        tool.addProperty("description", "Echo a message.");
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        var properties = new JsonObject();
        var message = new JsonObject();
        message.addProperty("type", "string");
        message.addProperty("description", "Message to echo.");
        properties.add("message", message);
        schema.add("properties", properties);
        var required = new JsonArray();
        required.add("message");
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        tool.add("inputSchema", schema);
        tools.add(tool);
        result.add("tools", tools);
        return result;
    }

    private static JsonObject callResult(JsonObject params) {
        String name = params.get("name").getAsString();
        JsonObject arguments = params.getAsJsonObject("arguments");
        var result = new JsonObject();
        var content = new JsonArray();
        var text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", name + ": " + arguments.get("message").getAsString());
        content.add(text);
        result.add("content", content);
        result.addProperty("isError", false);
        return result;
    }

    private static JsonObject response(JsonElement id, JsonObject result) {
        var response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id.deepCopy());
        response.add("result", result);
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        var response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id.deepCopy());
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }
}
