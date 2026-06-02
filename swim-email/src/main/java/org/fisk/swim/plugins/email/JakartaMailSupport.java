package org.fisk.swim.plugins.email;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.HeaderTerm;
import jakarta.mail.util.ByteArrayDataSource;

final class JakartaMailSupport {
    private static final Pattern RE_PREFIX = Pattern.compile("^(?:(?:re|fw|fwd)\\s*:\\s*)+", Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_MAX_MESSAGES_PER_SYNC = Integer.getInteger("swim.mail.max.messages", 250);

    private JakartaMailSupport() {
    }

    static MailSyncBatch fetchImap(EmailAccountConfig account) throws Exception {
        return fetch(account, effectiveImapStoreProtocol(account), defaultImapProperties(account), effectiveFolder(account),
                Integer.MAX_VALUE);
    }

    static MailSyncBatch fetchPop3(EmailAccountConfig account) throws Exception {
        return fetch(account, effectivePop3StoreProtocol(account), defaultPop3Properties(account), "INBOX",
                Integer.MAX_VALUE);
    }

    static MailSyncBatch fetchImapRange(EmailAccountConfig account, int endIndexInclusive) throws Exception {
        return fetch(account, effectiveImapStoreProtocol(account), defaultImapProperties(account), effectiveFolder(account),
                endIndexInclusive);
    }

    static MailSyncBatch fetchImapSinceUid(EmailAccountConfig account, long lastSeenUid) throws Exception {
        return fetchSinceUid(account, effectiveImapStoreProtocol(account), defaultImapProperties(account), effectiveFolder(account),
                lastSeenUid);
    }

    static MailSyncBatch fetchImapOlderThanUid(EmailAccountConfig account, long nextBackfillUidInclusive) throws Exception {
        return fetchOlderThanUid(account, effectiveImapStoreProtocol(account), defaultImapProperties(account), effectiveFolder(account),
                nextBackfillUidInclusive);
    }

    static MailSyncBatch fetchPop3Range(EmailAccountConfig account, int endIndexInclusive) throws Exception {
        return fetch(account, effectivePop3StoreProtocol(account), defaultPop3Properties(account), "INBOX",
                endIndexInclusive);
    }

    static String loadImapBody(EmailAccountConfig account, String folderName, String internetMessageId) throws Exception {
        return loadBody(account, effectiveImapStoreProtocol(account), defaultImapProperties(account), folderName,
                internetMessageId);
    }

    static String loadPop3Body(EmailAccountConfig account, String internetMessageId) throws Exception {
        return loadBody(account, effectivePop3StoreProtocol(account), defaultPop3Properties(account), "INBOX",
                internetMessageId);
    }

    private static MailSyncBatch fetch(
            EmailAccountConfig account,
            String storeProtocol,
            Properties properties,
            String folderName,
            int endIndexInclusive) throws Exception {
        if (account.host() == null || account.host().isBlank()) {
            return MailSyncBatch.failure("Missing host");
        }
        if (account.username() == null || account.username().isBlank()) {
            return MailSyncBatch.failure("Missing username");
        }
        String password;
        if (account.usesOAuth2()) {
            MicrosoftOAuth2Client.AcquireResult tokenResult = new MicrosoftOAuth2Client(EmailPaths.fromUserHome())
                    .acquireToken(account, storeProtocol);
            if (!tokenResult.hasToken()) {
                return MailSyncBatch.failure(tokenResult.statusMessage());
            }
            configureOAuth2(properties, storeProtocol);
            password = tokenResult.accessToken();
        } else {
            password = resolvePassword(account);
            if (password == null) {
                return MailSyncBatch.failure("Missing password env '" + account.passwordEnv() + "'");
            }
        }
        Session session = Session.getInstance(properties);
        Store store = session.getStore(storeProtocol);
        try {
            connectStore(store, account, password);
            Folder folder = store.getFolder(folderName);
            try {
                if (folder == null || !folder.exists()) {
                    return MailSyncBatch.failure("Folder '" + folderName + "' not found");
                }
                folder.open(Folder.READ_ONLY);
                int count = folder.getMessageCount();
                if (count == 0) {
                    return MailSyncBatch.success(List.of(), "0 messages");
                }
                int safeEnd = Math.min(count, Math.max(1, endIndexInclusive));
                int start = syncStartIndex(safeEnd, DEFAULT_MAX_MESSAGES_PER_SYNC);
                Message[] messages = folder.getMessages(start, safeEnd);
                FetchProfile fetchProfile = new FetchProfile();
                fetchProfile.add(FetchProfile.Item.ENVELOPE);
                fetchProfile.add(FetchProfile.Item.FLAGS);
                fetchProfile.add("Message-ID");
                fetchProfile.add("In-Reply-To");
                fetchProfile.add("References");
                if (folder instanceof UIDFolder) {
                    fetchProfile.add(UIDFolder.FetchProfileItem.UID);
                }
                folder.fetch(messages, fetchProfile);
                List<ImportedMailMessage> imported = new ArrayList<>();
                for (Message message : messages) {
                    long uid = folder instanceof UIDFolder uidFolder ? uidFolder.getUID(message) : 0L;
                    imported.add(toImportedMessage(account, folderName, message, uid));
                }
                imported.sort(Comparator.comparing(ImportedMailMessage::effectiveTimestamp).reversed());
                long highWatermarkUid = imported.stream().mapToLong(ImportedMailMessage::serverUid).max().orElse(0L);
                long nextBackfillUid = nextBackfillUid(imported);
                return MailSyncBatch.success(imported, syncStatus(imported.size(), count, start), count, start, safeEnd,
                        highWatermarkUid, nextBackfillUid);
            } finally {
                if (folder.isOpen()) {
                    folder.close(false);
                }
            }
        } finally {
            store.close();
        }
    }

    private static MailSyncBatch fetchSinceUid(
            EmailAccountConfig account,
            String storeProtocol,
            Properties properties,
            String folderName,
            long lastSeenUid) throws Exception {
        if (lastSeenUid <= 0L) {
            return fetch(account, storeProtocol, properties, folderName, Integer.MAX_VALUE);
        }
        if (account.host() == null || account.host().isBlank()) {
            return MailSyncBatch.failure("Missing host");
        }
        if (account.username() == null || account.username().isBlank()) {
            return MailSyncBatch.failure("Missing username");
        }
        String password;
        if (account.usesOAuth2()) {
            MicrosoftOAuth2Client.AcquireResult tokenResult = new MicrosoftOAuth2Client(EmailPaths.fromUserHome())
                    .acquireToken(account, storeProtocol);
            if (!tokenResult.hasToken()) {
                return MailSyncBatch.failure(tokenResult.statusMessage());
            }
            configureOAuth2(properties, storeProtocol);
            password = tokenResult.accessToken();
        } else {
            password = resolvePassword(account);
            if (password == null) {
                return MailSyncBatch.failure("Missing password env '" + account.passwordEnv() + "'");
            }
        }
        Session session = Session.getInstance(properties);
        Store store = session.getStore(storeProtocol);
        try {
            connectStore(store, account, password);
            Folder folder = store.getFolder(folderName);
            try {
                if (folder == null || !folder.exists()) {
                    return MailSyncBatch.failure("Folder '" + folderName + "' not found");
                }
                folder.open(Folder.READ_ONLY);
                if (!(folder instanceof UIDFolder uidFolder)) {
                    return fetch(account, storeProtocol, properties, folderName, Integer.MAX_VALUE);
                }
                int count = folder.getMessageCount();
                Message[] messages = uidFolder.getMessagesByUID(lastSeenUid + 1L, UIDFolder.LASTUID);
                if (messages == null || messages.length == 0) {
                    return MailSyncBatch.success(List.of(), incrementalSyncStatus(0), count, 0, 0, lastSeenUid, 0L);
                }
                FetchProfile fetchProfile = new FetchProfile();
                fetchProfile.add(FetchProfile.Item.ENVELOPE);
                fetchProfile.add(FetchProfile.Item.FLAGS);
                fetchProfile.add("Message-ID");
                fetchProfile.add("In-Reply-To");
                fetchProfile.add("References");
                fetchProfile.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, fetchProfile);
                List<ImportedMailMessage> imported = new ArrayList<>();
                long highWatermarkUid = lastSeenUid;
                for (Message message : messages) {
                    long uid = uidFolder.getUID(message);
                    highWatermarkUid = Math.max(highWatermarkUid, uid);
                    imported.add(toImportedMessage(account, folderName, message, uid));
                }
                imported.sort(Comparator.comparing(ImportedMailMessage::effectiveTimestamp).reversed());
                return MailSyncBatch.success(imported, incrementalSyncStatus(imported.size()), count, 0, 0, highWatermarkUid,
                        0L);
            } finally {
                if (folder.isOpen()) {
                    folder.close(false);
                }
            }
        } finally {
            store.close();
        }
    }

    private static MailSyncBatch fetchOlderThanUid(
            EmailAccountConfig account,
            String storeProtocol,
            Properties properties,
            String folderName,
            long nextBackfillUidInclusive) throws Exception {
        if (nextBackfillUidInclusive <= 0L) {
            return MailSyncBatch.success(List.of(), "");
        }
        if (account.host() == null || account.host().isBlank()) {
            return MailSyncBatch.failure("Missing host");
        }
        if (account.username() == null || account.username().isBlank()) {
            return MailSyncBatch.failure("Missing username");
        }
        String password = resolveSecret(account, storeProtocol, properties);
        Session session = Session.getInstance(properties);
        Store store = session.getStore(storeProtocol);
        try {
            connectStore(store, account, password);
            Folder folder = store.getFolder(folderName);
            try {
                if (folder == null || !folder.exists()) {
                    return MailSyncBatch.failure("Folder '" + folderName + "' not found");
                }
                folder.open(Folder.READ_ONLY);
                if (!(folder instanceof UIDFolder uidFolder)) {
                    return MailSyncBatch.success(List.of(), "");
                }
                long startUid = Math.max(1L, nextBackfillUidInclusive - DEFAULT_MAX_MESSAGES_PER_SYNC + 1L);
                int count = folder.getMessageCount();
                Message[] messages = uidFolder.getMessagesByUID(startUid, nextBackfillUidInclusive);
                if (messages == null || messages.length == 0) {
                    return MailSyncBatch.success(List.of(), backgroundSyncStatus(0, count), count, 0, 0, 0L, 0L);
                }
                FetchProfile fetchProfile = new FetchProfile();
                fetchProfile.add(FetchProfile.Item.ENVELOPE);
                fetchProfile.add(FetchProfile.Item.FLAGS);
                fetchProfile.add("Message-ID");
                fetchProfile.add("In-Reply-To");
                fetchProfile.add("References");
                fetchProfile.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, fetchProfile);
                List<ImportedMailMessage> imported = new ArrayList<>();
                for (Message message : messages) {
                    long uid = uidFolder.getUID(message);
                    if (uid <= 0L || uid > nextBackfillUidInclusive) {
                        continue;
                    }
                    imported.add(toImportedMessage(account, folderName, message, uid));
                }
                imported.sort(Comparator.comparing(ImportedMailMessage::effectiveTimestamp).reversed());
                long nextBackfillUid = nextBackfillUid(imported);
                return MailSyncBatch.success(imported, backgroundSyncStatus(imported.size(), count), count, 0, 0, 0L,
                        nextBackfillUid);
            } finally {
                if (folder.isOpen()) {
                    folder.close(false);
                }
            }
        } finally {
            store.close();
        }
    }

    private static String loadBody(
            EmailAccountConfig account,
            String storeProtocol,
            Properties properties,
            String folderName,
            String internetMessageId) throws Exception {
        if (internetMessageId == null || internetMessageId.isBlank()) {
            return "";
        }
        if (account.host() == null || account.host().isBlank() || account.username() == null || account.username().isBlank()) {
            return "";
        }

        String password = resolveSecret(account, storeProtocol, properties);
        Session session = Session.getInstance(properties);
        Store store = session.getStore(storeProtocol);
        try {
            connectStore(store, account, password);
            Folder folder = store.getFolder(folderName == null || folderName.isBlank() ? "INBOX" : folderName);
            try {
                if (folder == null || !folder.exists()) {
                    return "";
                }
                folder.open(Folder.READ_ONLY);
                Message[] matches = folder.search(new HeaderTerm("Message-ID", internetMessageId));
                if (matches == null || matches.length == 0) {
                    return "";
                }
                return extractText(matches[matches.length - 1]);
            } finally {
                if (folder.isOpen()) {
                    folder.close(false);
                }
            }
        } finally {
            store.close();
        }
    }

    private static Properties defaultImapProperties(EmailAccountConfig account) {
        Properties properties = baseProperties();
        String storeProtocol = effectiveImapStoreProtocol(account);
        properties.setProperty("mail.store.protocol", storeProtocol);
        properties.setProperty("mail." + storeProtocol + ".partialfetch", "false");
        configureTransportSecurity(properties, "mail." + storeProtocol, account.port(), 993, 143);
        return properties;
    }

    private static Properties defaultPop3Properties(EmailAccountConfig account) {
        Properties properties = baseProperties();
        String storeProtocol = effectivePop3StoreProtocol(account);
        properties.setProperty("mail.store.protocol", storeProtocol);
        configureTransportSecurity(properties, "mail." + storeProtocol, account.port(), 995, 110);
        return properties;
    }

    private static Properties baseProperties() {
        Properties properties = new Properties();
        properties.setProperty("mail.connectiontimeout", "10000");
        properties.setProperty("mail.timeout", "20000");
        properties.setProperty("mail.writetimeout", "20000");
        return properties;
    }

    private static void configureTransportSecurity(
            Properties properties,
            String prefix,
            Integer port,
            int sslPort,
            int startTlsPort) {
        if (port != null && port == startTlsPort) {
            properties.setProperty(prefix + ".starttls.enable", "true");
            properties.setProperty(prefix + ".ssl.enable", "false");
            return;
        }
        properties.setProperty(prefix + ".ssl.enable", String.valueOf(port == null || port == sslPort));
        properties.setProperty(prefix + ".starttls.enable", String.valueOf(port != null && port == startTlsPort));
    }

    private static void configureOAuth2(Properties properties, String protocol) {
        String prefix = "mail." + protocol.toLowerCase(java.util.Locale.ROOT);
        properties.setProperty(prefix + ".auth.mechanisms", "XOAUTH2");
        properties.setProperty(prefix + ".sasl.enable", "true");
        properties.setProperty(prefix + ".sasl.mechanisms", "XOAUTH2");
        properties.setProperty(prefix + ".auth.login.disable", "true");
        properties.setProperty(prefix + ".auth.plain.disable", "true");
    }

    private static String resolvePassword(EmailAccountConfig account) {
        return SecretResolver.resolve(account.passwordEnv());
    }

    private static String resolveSecret(EmailAccountConfig account, String storeProtocol, Properties properties)
            throws IOException {
        if (account.usesOAuth2()) {
            MicrosoftOAuth2Client.AcquireResult tokenResult = new MicrosoftOAuth2Client(EmailPaths.fromUserHome())
                    .acquireToken(account, storeProtocol);
            if (!tokenResult.hasToken()) {
                throw new IOException(tokenResult.statusMessage());
            }
            configureOAuth2(properties, storeProtocol);
            return tokenResult.accessToken();
        }
        String password = resolvePassword(account);
        if (password == null) {
            throw new IOException("Missing password env '" + account.passwordEnv() + "'");
        }
        return password;
    }

    private static void connectStore(Store store, EmailAccountConfig account, String password) throws MessagingException {
        if (account.port() == null) {
            store.connect(account.host(), account.username(), password);
        } else {
            store.connect(account.host(), account.port(), account.username(), password);
        }
    }

    private static String effectiveFolder(EmailAccountConfig account) {
        return account.folder() == null || account.folder().isBlank() ? "INBOX" : account.folder().trim();
    }

    private static String effectiveImapStoreProtocol(EmailAccountConfig account) {
        return account.port() != null && account.port() == 143 ? "imap" : "imaps";
    }

    private static String effectivePop3StoreProtocol(EmailAccountConfig account) {
        return account.port() != null && account.port() == 110 ? "pop3" : "pop3s";
    }

    private static ImportedMailMessage toImportedMessage(
            EmailAccountConfig account,
            String folderName,
            Message message,
            long serverUid) throws MessagingException, IOException {
        Address fromAddress = firstAddress(message.getFrom());
        String fromName = displayName(fromAddress);
        String fromEmail = email(fromAddress);
        List<ImportedMailRecipient> recipients = collectRecipients(message);
        String toSummary = summarizeRecipients(recipients);
        String subject = message.getSubject();
        String bodyText = "";
        String snippetSource = normalizeWhitespace(subject);
        String snippet = snippetSource.length() > 180 ? snippetSource.substring(0, 180) : snippetSource;
        return new ImportedMailMessage(
                account.normalizedId(),
                folderName,
                firstHeader(message, "Message-ID"),
                threadKey(message, subject, fromEmail, toSummary),
                subject,
                fromName,
                fromEmail,
                toSummary,
                recipients,
                toInstant(message.getSentDate()),
                toInstant(message.getReceivedDate()),
                snippet,
                bodyText,
                !message.isSet(Flags.Flag.SEEN),
                serverUid);
    }

    private static String threadKey(Message message, String subject, String fromEmail, String toSummary)
            throws MessagingException {
        String inReplyTo = normalizeHeader(firstHeader(message, "In-Reply-To"));
        if (!inReplyTo.isBlank()) {
            return "reply:" + inReplyTo;
        }
        String references = normalizeHeader(firstHeader(message, "References"));
        if (!references.isBlank()) {
            int split = references.lastIndexOf(' ');
            return "ref:" + (split >= 0 ? references.substring(split + 1) : references);
        }
        String messageId = normalizeHeader(firstHeader(message, "Message-ID"));
        return messageId.isBlank() ? "" : "message-id:" + messageId;
    }

    private static String normalizeSubject(String subject) {
        String value = subject == null ? "" : subject.strip();
        value = RE_PREFIX.matcher(value).replaceFirst("");
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    static int syncStartIndex(int totalMessages, int maxMessagesPerSync) {
        if (totalMessages <= 0) {
            return 1;
        }
        if (maxMessagesPerSync <= 0 || totalMessages <= maxMessagesPerSync) {
            return 1;
        }
        return totalMessages - maxMessagesPerSync + 1;
    }

    static String syncStatus(int importedMessages, int totalMessages, int startIndex) {
        if (importedMessages <= 0) {
            return "0 messages";
        }
        if (startIndex <= 1) {
            return importedMessages + " messages";
        }
        int skipped = Math.max(0, startIndex - 1);
        return "Fetched latest " + importedMessages + " of " + totalMessages + " messages (" + skipped
                + " older messages skipped)";
    }

    static String backgroundSyncStatus(int loadedMessages, int totalMessages) {
        if (totalMessages <= 0) {
            return loadedMessages + " messages";
        }
        return "Cached " + loadedMessages + " of " + totalMessages + " messages";
    }

    static String incrementalSyncStatus(int importedMessages) {
        if (importedMessages <= 0) {
            return "Up to date";
        }
        if (importedMessages == 1) {
            return "1 new message";
        }
        return importedMessages + " new messages";
    }

    private static long nextBackfillUid(List<ImportedMailMessage> imported) {
        long oldestFetchedUid = imported.stream()
                .mapToLong(ImportedMailMessage::serverUid)
                .filter(uid -> uid > 0L)
                .min()
                .orElse(0L);
        return oldestFetchedUid > 1L ? oldestFetchedUid - 1L : 0L;
    }

    private static String extractText(Part part) throws MessagingException, IOException {
        if (part == null) {
            return "";
        }
        if (part.isMimeType("text/plain")) {
            return readContentAsString(part.getContent());
        }
        if (part.isMimeType("text/html")) {
            return htmlToText(readContentAsString(part.getContent()));
        }
        if (part.isMimeType("multipart/*")) {
            return extractText(parseMultipart(part));
        }
        if (part.isMimeType("message/rfc822")) {
            Object nested = part.getContent();
            if (nested instanceof Part nestedPart) {
                return extractText(nestedPart);
            }
        }
        return readContentAsString(part.getContent());
    }

    private static String extractText(Multipart multipart) throws MessagingException, IOException {
        if (multipart == null) {
            return "";
        }
        String plainText = "";
        String htmlText = "";
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/plain")) {
                String text = extractText(bodyPart);
                if (!text.isBlank()) {
                    plainText = appendPartText(plainText, text);
                }
                continue;
            }
            if (bodyPart.isMimeType("text/html")) {
                String text = extractText(bodyPart);
                if (!text.isBlank()) {
                    htmlText = appendPartText(htmlText, text);
                }
                continue;
            }
            if (bodyPart.isMimeType("multipart/*") || bodyPart.isMimeType("message/rfc822")) {
                String text = extractText(bodyPart);
                if (!text.isBlank()) {
                    plainText = appendPartText(plainText, text);
                }
            }
        }
        return !plainText.isBlank() ? plainText : htmlText;
    }

    private static Multipart parseMultipart(Part part) throws MessagingException, IOException {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            return multipart;
        }
        if (content instanceof InputStream inputStream) {
            return new MimeMultipart(new ByteArrayDataSource(inputStream, part.getContentType()));
        }
        throw new MessagingException("Unsupported multipart content: " + content);
    }

    private static String readContentAsString(Object content) throws IOException {
        if (content == null) {
            return "";
        }
        if (content instanceof String str) {
            return str;
        }
        if (content instanceof InputStream inputStream) {
            try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    text.append(buffer, 0, read);
                }
                return text.toString();
            }
        }
        return content.toString();
    }

    private static String toInstant(Date date) {
        return date == null ? null : Instant.ofEpochMilli(date.getTime()).toString();
    }

    private static String firstHeader(Message message, String name) throws MessagingException {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? "" : values[0];
    }

    private static String normalizeHeader(String value) {
        return normalizeWhitespace(value == null ? "" : value);
    }

    private static Address firstAddress(Address[] addresses) {
        return addresses == null || addresses.length == 0 ? null : addresses[0];
    }

    private static String summarizeAddresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (Address address : addresses) {
            String display = displayName(address);
            String email = email(address);
            if (display != null && !display.isBlank()) {
                values.add(display);
            } else if (email != null && !email.isBlank()) {
                values.add(email);
            }
        }
        return String.join(", ", values);
    }

    private static List<ImportedMailRecipient> collectRecipients(Message message) throws MessagingException {
        var recipients = new ArrayList<ImportedMailRecipient>();
        appendRecipients(recipients, "TO", message.getRecipients(Message.RecipientType.TO));
        appendRecipients(recipients, "CC", message.getRecipients(Message.RecipientType.CC));
        return List.copyOf(recipients);
    }

    private static void appendRecipients(List<ImportedMailRecipient> recipients, String type, Address[] addresses) {
        if (addresses == null) {
            return;
        }
        for (Address address : addresses) {
            recipients.add(new ImportedMailRecipient(type, displayName(address), email(address)));
        }
    }

    private static String summarizeRecipients(List<ImportedMailRecipient> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return "";
        }
        var values = new ArrayList<String>();
        for (ImportedMailRecipient recipient : recipients) {
            if (recipient.name() != null && !recipient.name().isBlank()) {
                values.add(recipient.name());
            } else if (recipient.email() != null && !recipient.email().isBlank()) {
                values.add(recipient.email());
            }
        }
        return String.join(", ", values);
    }

    private static String displayName(Address address) {
        if (address instanceof InternetAddress internetAddress) {
            return internetAddress.getPersonal();
        }
        return address == null ? "" : address.toString();
    }

    private static String email(Address address) {
        if (address instanceof InternetAddress internetAddress) {
            return internetAddress.getAddress();
        }
        return address == null ? "" : address.toString();
    }

    private static String normalizeWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String appendPartText(String current, String next) {
        if (current == null || current.isBlank()) {
            return next;
        }
        if (next == null || next.isBlank()) {
            return current;
        }
        return current + "\n\n" + next;
    }

    private static String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n\n")
                .replaceAll("(?i)</div>", "\n")
                .replaceAll("(?i)<li>", "- ")
                .replaceAll("(?is)<style.*?</style>", "")
                .replaceAll("(?is)<script.*?</script>", "")
                .replaceAll("(?s)<[^>]+>", "");
        text = text.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"");
        return text;
    }
}
