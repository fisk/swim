package org.fisk.swim.plugins.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ExchangeEwsMailSyncAdapterTest {
    @Test
    void exchangeAdapterParsesFindItemAndGetItemResponses() throws Exception {
        String key = "SWIM_TEST_EXCHANGE_PASSWORD";
        String original = System.getProperty(key);
        System.setProperty(key, "secret");
        try {
        var adapter = new ExchangeEwsMailSyncAdapter(new FakeTransport(
                """
                        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                          <s:Body>
                            <m:FindItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                                xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                              <m:ResponseMessages>
                                <m:FindItemResponseMessage ResponseClass="Success">
                                  <m:ResponseCode>NoError</m:ResponseCode>
                                  <m:RootFolder IncludesLastItemInRange="true" TotalItemsInView="2">
                                    <t:Items>
                                      <t:Message>
                                        <t:ItemId Id="AAMk1" ChangeKey="CQAAABYAA" />
                                      </t:Message>
                                      <t:Message>
                                        <t:ItemId Id="AAMk2" ChangeKey="CQAAABYAB" />
                                      </t:Message>
                                    </t:Items>
                                  </m:RootFolder>
                                </m:FindItemResponseMessage>
                              </m:ResponseMessages>
                            </m:FindItemResponse>
                          </s:Body>
                        </s:Envelope>
                        """,
                """
                        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                          <s:Body>
                            <m:GetItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                              <m:ResponseMessages>
                                <m:GetItemResponseMessage ResponseClass="Success">
                                  <m:ResponseCode>NoError</m:ResponseCode>
                                  <m:Items>
                                    <t:Message>
                                      <t:ItemId Id="AAMk1" ChangeKey="CQAAABYAA" />
                                      <t:InternetMessageId>&lt;message-1@example.com&gt;</t:InternetMessageId>
                                      <t:Subject>Quarterly review</t:Subject>
                                      <t:DateTimeSent>2026-05-13T08:30:00Z</t:DateTimeSent>
                                      <t:DateTimeReceived>2026-05-13T08:31:00Z</t:DateTimeReceived>
                                      <t:IsRead>false</t:IsRead>
                                      <t:Body BodyType="Text">Please review the attached report.</t:Body>
                                      <t:From>
                                        <t:Mailbox>
                                          <t:Name>Boss</t:Name>
                                          <t:EmailAddress>boss@example.com</t:EmailAddress>
                                        </t:Mailbox>
                                      </t:From>
                                      <t:ToRecipients>
                                        <t:Mailbox>
                                          <t:Name>Me</t:Name>
                                          <t:EmailAddress>me@example.com</t:EmailAddress>
                                        </t:Mailbox>
                                      </t:ToRecipients>
                                    </t:Message>
                                    <t:Message>
                                      <t:ItemId Id="AAMk2" ChangeKey="CQAAABYAB" />
                                      <t:InternetMessageId>&lt;message-2@example.com&gt;</t:InternetMessageId>
                                      <t:InReplyTo>&lt;message-1@example.com&gt;</t:InReplyTo>
                                      <t:Subject>Re: Quarterly review</t:Subject>
                                      <t:DateTimeSent>2026-05-13T09:00:00Z</t:DateTimeSent>
                                      <t:DateTimeReceived>2026-05-13T09:00:05Z</t:DateTimeReceived>
                                      <t:IsRead>true</t:IsRead>
                                      <t:Body BodyType="Text">Looks good to me.</t:Body>
                                      <t:From>
                                        <t:Mailbox>
                                          <t:Name>Me</t:Name>
                                          <t:EmailAddress>me@example.com</t:EmailAddress>
                                        </t:Mailbox>
                                      </t:From>
                                      <t:ToRecipients>
                                        <t:Mailbox>
                                          <t:Name>Boss</t:Name>
                                          <t:EmailAddress>boss@example.com</t:EmailAddress>
                                        </t:Mailbox>
                                      </t:ToRecipients>
                                    </t:Message>
                                  </m:Items>
                                </m:GetItemResponseMessage>
                              </m:ResponseMessages>
                            </m:GetItemResponse>
                          </s:Body>
                        </s:Envelope>
                        """));

        MailSyncBatch batch = adapter.fetch(new EmailAccountConfig(
                "exchange",
                "Exchange",
                "EXCHANGE",
                null,
                null,
                null,
                null,
                null,
                "user",
                "SWIM_TEST_EXCHANGE_PASSWORD",
                "INBOX",
                "https://mail.example.com/EWS/Exchange.asmx",
                "DOMAIN",
                "BASIC",
                null,
                null,
                null));

        assertTrue(batch.success());
        assertEquals(2, batch.messages().size());
        assertEquals("2 messages", batch.statusMessage());
        assertEquals("subject:quarterly review|boss@example.com|me", batch.messages().get(0).threadKey());
        assertEquals(batch.messages().get(0).threadKey(), batch.messages().get(1).threadKey());
        assertTrue(batch.messages().get(0).unread());
        assertFalse(batch.messages().get(1).unread());
        } finally {
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
    }

    @Test
    void exchangeAdapterRejectsUnsupportedAuthType() throws Exception {
        var adapter = new ExchangeEwsMailSyncAdapter((account, body) -> {
            throw new AssertionError("transport should not be called");
        });

        MailSyncBatch batch = adapter.fetch(new EmailAccountConfig(
                "exchange",
                "Exchange",
                "EXCHANGE",
                null,
                null,
                null,
                null,
                null,
                "DOMAIN\\user",
                "SWIM_TEST_EXCHANGE_PASSWORD",
                "INBOX",
                "https://mail.example.com/EWS/Exchange.asmx",
                "DOMAIN",
                "NTLM",
                null,
                null,
                null));

        assertFalse(batch.success());
        assertTrue(batch.statusMessage().contains("BASIC/PASSWORD auth only"));
    }

    @Test
    void exchangeAdapterFetchesAdditionalPagesPastInitialLimit() throws Exception {
        String key = "SWIM_TEST_EXCHANGE_PASSWORD";
        String original = System.getProperty(key);
        System.setProperty(key, "secret");
        try {
            var transport = new RecordingTransport(
                    """
                            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                              <s:Body>
                                <m:FindItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                                    xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                                  <m:ResponseMessages>
                                    <m:FindItemResponseMessage ResponseClass="Success">
                                      <m:ResponseCode>NoError</m:ResponseCode>
                                      <m:RootFolder IncludesLastItemInRange="false" TotalItemsInView="3">
                                        <t:Items>
                                          <t:Message><t:ItemId Id="AAMk1" ChangeKey="CK1" /></t:Message>
                                          <t:Message><t:ItemId Id="AAMk2" ChangeKey="CK2" /></t:Message>
                                        </t:Items>
                                      </m:RootFolder>
                                    </m:FindItemResponseMessage>
                                  </m:ResponseMessages>
                                </m:FindItemResponse>
                              </s:Body>
                            </s:Envelope>
                            """,
                    getItemResponse(
                            messageXml("AAMk1", "CK1", "&lt;message-1@example.com&gt;", "", "Thread 1"),
                            messageXml("AAMk2", "CK2", "&lt;message-2@example.com&gt;", "", "Thread 2")),
                    """
                            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                              <s:Body>
                                <m:FindItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                                    xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                                  <m:ResponseMessages>
                                    <m:FindItemResponseMessage ResponseClass="Success">
                                      <m:ResponseCode>NoError</m:ResponseCode>
                                      <m:RootFolder IncludesLastItemInRange="true" TotalItemsInView="3">
                                        <t:Items>
                                          <t:Message><t:ItemId Id="AAMk3" ChangeKey="CK3" /></t:Message>
                                        </t:Items>
                                      </m:RootFolder>
                                    </m:FindItemResponseMessage>
                                  </m:ResponseMessages>
                                </m:FindItemResponse>
                              </s:Body>
                            </s:Envelope>
                            """,
                    getItemResponse(messageXml("AAMk3", "CK3", "&lt;message-3@example.com&gt;", "", "Thread 3")));

            var adapter = new ExchangeEwsMailSyncAdapter(transport);

            MailSyncBatch batch = adapter.fetch(new EmailAccountConfig(
                    "exchange",
                    "Exchange",
                    "EXCHANGE",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "user",
                    "SWIM_TEST_EXCHANGE_PASSWORD",
                    "INBOX",
                    "https://mail.example.com/EWS/Exchange.asmx",
                    "DOMAIN",
                    "BASIC",
                    null,
                    null,
                    null));

            assertTrue(batch.success());
            assertEquals(3, batch.messages().size());
            assertTrue(transport.requests().get(2).contains("Offset=\"2\""));
        } finally {
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
    }

    private static class FakeTransport implements ExchangeEwsMailSyncAdapter.EwsTransport {
        private final List<String> _responses;
        private int _index;

        private FakeTransport(String... responses) {
            _responses = List.of(responses);
        }

        @Override
        public String post(EmailAccountConfig account, String body) {
            return _responses.get(_index++);
        }
    }

    private static final class RecordingTransport extends FakeTransport {
        private final List<String> _requests = new ArrayList<>();

        private RecordingTransport(String... responses) {
            super(responses);
        }

        @Override
        public String post(EmailAccountConfig account, String body) {
            _requests.add(body);
            return super.post(account, body);
        }

        private List<String> requests() {
            return _requests;
        }
    }

    private static String getItemResponse(String... messages) {
        return """
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                  <s:Body>
                    <m:GetItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                       xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                      <m:ResponseMessages>
                        <m:GetItemResponseMessage ResponseClass="Success">
                          <m:ResponseCode>NoError</m:ResponseCode>
                          <m:Items>
                            %s
                          </m:Items>
                        </m:GetItemResponseMessage>
                      </m:ResponseMessages>
                    </m:GetItemResponse>
                  </s:Body>
                </s:Envelope>
                """.formatted(String.join("\n", messages));
    }

    private static String messageXml(String id, String changeKey, String internetMessageId, String inReplyTo, String subject) {
        String replyXml = inReplyTo == null || inReplyTo.isBlank() ? "" : "<t:InReplyTo>" + inReplyTo + "</t:InReplyTo>";
        return """
                <t:Message>
                  <t:ItemId Id="%s" ChangeKey="%s" />
                  <t:InternetMessageId>%s</t:InternetMessageId>
                  %s
                  <t:Subject>%s</t:Subject>
                  <t:DateTimeSent>2026-05-13T08:30:00Z</t:DateTimeSent>
                  <t:DateTimeReceived>2026-05-13T08:31:00Z</t:DateTimeReceived>
                  <t:IsRead>false</t:IsRead>
                  <t:Body BodyType="Text">Please review.</t:Body>
                  <t:From>
                    <t:Mailbox>
                      <t:Name>Boss</t:Name>
                      <t:EmailAddress>boss@example.com</t:EmailAddress>
                    </t:Mailbox>
                  </t:From>
                  <t:ToRecipients>
                    <t:Mailbox>
                      <t:Name>Me</t:Name>
                      <t:EmailAddress>me@example.com</t:EmailAddress>
                    </t:Mailbox>
                  </t:ToRecipients>
                </t:Message>
                """.formatted(id, changeKey, internetMessageId, replyXml, subject);
    }
}
