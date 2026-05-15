package org.fisk.swim.plugins.email;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

final class ExchangeEwsMailSyncAdapter implements MailSyncAdapter {
    private static final int MAX_ITEMS = 500;

    private final EwsTransport _transport;

    ExchangeEwsMailSyncAdapter() {
        this(new HttpEwsTransport());
    }

    ExchangeEwsMailSyncAdapter(EwsTransport transport) {
        _transport = transport;
    }

    @Override
    public MailSyncBatch fetch(EmailAccountConfig account) throws Exception {
        String validation = validate(account);
        if (validation != null) {
            return MailSyncBatch.failure(validation);
        }
        String folderId = distinguishedFolderId(account.folder());
        if (folderId == null) {
            return MailSyncBatch.failure("EWS currently supports distinguished folders only");
        }

        String findResponse = _transport.post(account, buildFindItemRequest(folderId));
        Document findDocument = parseXml(findResponse);
        String error = responseError(findDocument);
        if (error != null) {
            return MailSyncBatch.failure(error);
        }

        List<EwsItemRef> itemRefs = parseFindItems(findDocument);
        if (itemRefs.isEmpty()) {
            return MailSyncBatch.success(List.of(), "0 messages");
        }

        String getItemResponse = _transport.post(account, buildGetItemRequest(itemRefs));
        Document getItemDocument = parseXml(getItemResponse);
        error = responseError(getItemDocument);
        if (error != null) {
            return MailSyncBatch.failure(error);
        }

        List<ImportedMailMessage> messages = parseMessages(account, folderId, getItemDocument);
        return MailSyncBatch.success(messages, messages.size() + " messages");
    }

    private static String validate(EmailAccountConfig account) {
        if (account.ewsUrl() == null || account.ewsUrl().isBlank()) {
            return "Exchange account requires ewsUrl";
        }
        if (account.username() == null || account.username().isBlank()) {
            return "Exchange account requires username";
        }
        if (account.passwordEnv() == null || account.passwordEnv().isBlank()) {
            return "Exchange account requires passwordEnv";
        }
        String authType = account.normalizedAuthType();
        if (!"PASSWORD".equals(authType) && !"BASIC".equals(authType)) {
            return "Exchange EWS currently supports BASIC/PASSWORD auth only";
        }
        if (resolvePassword(account) == null) {
            return "Missing password env '" + account.passwordEnv() + "'";
        }
        return null;
    }

    private static String buildFindItemRequest(String folderId) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope
                    xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                    xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                    xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <soap:Header>
                    <t:RequestServerVersion Version="Exchange2016" />
                  </soap:Header>
                  <soap:Body>
                    <m:FindItem Traversal="Shallow">
                      <m:ItemShape>
                        <t:BaseShape>IdOnly</t:BaseShape>
                      </m:ItemShape>
                      <m:IndexedPageItemView MaxEntriesReturned="%d" Offset="0" BasePoint="Beginning" />
                      <m:ParentFolderIds>
                        <t:DistinguishedFolderId Id="%s" />
                      </m:ParentFolderIds>
                    </m:FindItem>
                  </soap:Body>
                </soap:Envelope>
                """.formatted(MAX_ITEMS, xmlEscape(folderId));
    }

    private static String buildGetItemRequest(List<EwsItemRef> itemRefs) {
        StringBuilder ids = new StringBuilder();
        for (EwsItemRef itemRef : itemRefs) {
            ids.append("""
                    <t:ItemId Id="%s" ChangeKey="%s" />
                    """.formatted(xmlEscape(itemRef.id()), xmlEscape(itemRef.changeKey())));
        }
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope
                    xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                    xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                    xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <soap:Header>
                    <t:RequestServerVersion Version="Exchange2016" />
                  </soap:Header>
                  <soap:Body>
                    <m:GetItem>
                      <m:ItemShape>
                        <t:BaseShape>IdOnly</t:BaseShape>
                        <t:BodyType>Text</t:BodyType>
                        <t:AdditionalProperties>
                          <t:FieldURI FieldURI="item:Subject" />
                          <t:FieldURI FieldURI="item:DateTimeReceived" />
                          <t:FieldURI FieldURI="item:DateTimeSent" />
                          <t:FieldURI FieldURI="item:Body" />
                          <t:FieldURI FieldURI="message:From" />
                          <t:FieldURI FieldURI="message:ToRecipients" />
                          <t:FieldURI FieldURI="message:IsRead" />
                          <t:FieldURI FieldURI="message:InternetMessageId" />
                          <t:FieldURI FieldURI="item:InReplyTo" />
                        </t:AdditionalProperties>
                      </m:ItemShape>
                      <m:ItemIds>
                        %s
                      </m:ItemIds>
                    </m:GetItem>
                  </soap:Body>
                </soap:Envelope>
                """.formatted(ids);
    }

    private static List<EwsItemRef> parseFindItems(Document document) throws Exception {
        NodeList itemIdNodes = nodes(document, "//*[local-name()='Items']/*[local-name()='Message']/*[local-name()='ItemId']");
        List<EwsItemRef> itemRefs = new ArrayList<>();
        for (int i = 0; i < itemIdNodes.getLength(); i++) {
            Element itemId = (Element) itemIdNodes.item(i);
            String id = itemId.getAttribute("Id");
            String changeKey = itemId.getAttribute("ChangeKey");
            if (!id.isBlank()) {
                itemRefs.add(new EwsItemRef(id, changeKey == null ? "" : changeKey));
            }
        }
        return itemRefs;
    }

    private static List<ImportedMailMessage> parseMessages(
            EmailAccountConfig account,
            String folderId,
            Document document) throws Exception {
        NodeList messageNodes = nodes(document, "//*[local-name()='Items']/*[local-name()='Message']");
        List<ImportedMailMessage> messages = new ArrayList<>();
        Map<String, String> threadIdsByMessage = new LinkedHashMap<>();
        for (int i = 0; i < messageNodes.getLength(); i++) {
            Element message = (Element) messageNodes.item(i);
            String internetMessageId = childText(message, "InternetMessageId");
            String inReplyTo = childText(message, "InReplyTo");
            String subject = childText(message, "Subject");
            String body = normalizeWhitespace(childText(message, "Body"));
            String snippet = body.length() > 180 ? body.substring(0, 180) : body;
            String fromName = childText(message, "From/Mailbox/Name");
            String fromEmail = childText(message, "From/Mailbox/EmailAddress");
            String toSummary = recipientsText(message);
            String dateSent = normalizeDate(childText(message, "DateTimeSent"));
            String dateReceived = normalizeDate(childText(message, "DateTimeReceived"));
            boolean unread = !Boolean.parseBoolean(childText(message, "IsRead"));
            String threadKey = threadKey(subject, internetMessageId, inReplyTo, fromEmail, toSummary, threadIdsByMessage);
            messages.add(new ImportedMailMessage(
                    account.normalizedId(),
                    folderId.toUpperCase(Locale.ROOT),
                    internetMessageId,
                    threadKey,
                    subject,
                    fromName,
                    fromEmail,
                    toSummary,
                    dateSent,
                    dateReceived,
                    snippet,
                    body.isBlank() ? snippet : body,
                    unread));
        }
        return messages;
    }

    private static String threadKey(
            String subject,
            String internetMessageId,
            String inReplyTo,
            String fromEmail,
            String toSummary,
            Map<String, String> threadIdsByMessage) {
        String normalizedId = normalizeWhitespace(internetMessageId);
        String normalizedReply = normalizeWhitespace(inReplyTo);
        if (!normalizedReply.isBlank()) {
            String existing = threadIdsByMessage.get(normalizedReply);
            if (existing != null) {
                if (!normalizedId.isBlank()) {
                    threadIdsByMessage.put(normalizedId, existing);
                }
                return existing;
            }
        }
        String normalizedSubject = normalizeSubject(subject);
        String participants = normalizeWhitespace((fromEmail == null ? "" : fromEmail)
                + "|" + (toSummary == null ? "" : toSummary)).toLowerCase(Locale.ROOT);
        String generated = "subject:" + normalizedSubject + "|" + participants;
        if (!normalizedId.isBlank()) {
            threadIdsByMessage.put(normalizedId, generated);
        }
        return generated;
    }

    private static String recipientsText(Element message) throws Exception {
        NodeList recipients = nodes(message, "./*[local-name()='ToRecipients']/*[local-name()='Mailbox']");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < recipients.getLength(); i++) {
            Element recipient = (Element) recipients.item(i);
            String name = childText(recipient, "Name");
            String email = childText(recipient, "EmailAddress");
            values.add(!name.isBlank() ? name : email);
        }
        return String.join(", ", values);
    }

    private static String normalizeDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toString();
        } catch (DateTimeParseException e) {
            return value;
        }
    }

    private static String responseError(Document document) throws Exception {
        String code = text(document, "//*[local-name()='ResponseCode'][1]");
        if (code == null || code.isBlank() || "NoError".equals(code)) {
            return null;
        }
        String messageText = text(document, "//*[local-name()='MessageText'][1]");
        if (messageText == null || messageText.isBlank()) {
            return "EWS error: " + code;
        }
        return "EWS error: " + code + " - " + messageText;
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static NodeList nodes(Object context, String expression) throws Exception {
        return (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate(expression, context, XPathConstants.NODESET);
    }

    private static String text(Object context, String expression) throws Exception {
        Node node = (Node) XPathFactory.newInstance().newXPath().evaluate(expression, context, XPathConstants.NODE);
        return node == null ? "" : normalizeWhitespace(node.getTextContent());
    }

    private static String childText(Element element, String path) throws Exception {
        return text(element, "./*[local-name()='" + path.replace("/", "']/*[local-name()='") + "'][1]");
    }

    private static String normalizeSubject(String subject) {
        String value = subject == null ? "" : subject.strip();
        value = value.replaceFirst("^(?i:(re|fw|fwd)\\s*:\\s*)+", "");
        return value.toLowerCase(Locale.ROOT);
    }

    private static String resolvePassword(EmailAccountConfig account) {
        return SecretResolver.resolve(account.passwordEnv());
    }

    private static String distinguishedFolderId(String folder) {
        if (folder == null || folder.isBlank() || "INBOX".equalsIgnoreCase(folder)) {
            return "inbox";
        }
        return switch (folder.trim().toLowerCase(Locale.ROOT)) {
        case "sent", "sentitems" -> "sentitems";
        case "drafts" -> "drafts";
        case "deleted", "deleteditems", "trash" -> "deleteditems";
        case "junk", "junkemail" -> "junkemail";
        default -> null;
        };
    }

    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String normalizeWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    interface EwsTransport {
        String post(EmailAccountConfig account, String body) throws Exception;
    }

    private record EwsItemRef(String id, String changeKey) {
    }

    private static final class HttpEwsTransport implements EwsTransport {
        private final HttpClient _client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public String post(EmailAccountConfig account, String body) throws Exception {
            String password = resolvePassword(account);
            String auth = Base64.getEncoder()
                    .encodeToString((account.username() + ":" + password).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(account.ewsUrl()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("Accept", "text/xml")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = _client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return """
                        <error>
                          <ResponseCode>Http%s</ResponseCode>
                          <MessageText>%s</MessageText>
                        </error>
                        """.formatted(response.statusCode(), normalizeWhitespace(response.body()));
            }
            return response.body();
        }
    }
}
