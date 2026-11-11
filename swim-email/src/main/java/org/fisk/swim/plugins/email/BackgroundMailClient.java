package org.fisk.swim.plugins.email;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fisk.swim.api.SwimPluginWorkers;
import org.fisk.swim.mail.*;

/** Publishes the mail workspace immediately while opening/migrating the cache in a worker. */
final class BackgroundMailClient implements MailClient {
    private final CompletableFuture<MailClient> ready = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Path dataPath;

    BackgroundMailClient(Path dataPath, Callable<MailClient> open, SwimPluginWorkers workers) {
        this.dataPath = dataPath;
        workers.start(() -> {
            try {
                MailClient client = open.call();
                if (closed.get()) {
                    client.close();
                } else if (!ready.complete(client)) {
                    client.close();
                }
            } catch (Exception failure) {
                ready.completeExceptionally(failure);
            }
        });
    }

    private MailClient client() {
        return ready.join();
    }

    @Override
    public boolean backgroundReads() {
        return true;
    }
    @Override
    public MailSnapshot snapshot() {
        return client().snapshot();
    }
    @Override
    public MailSnapshot sidebarSnapshot() {
        return client().sidebarSnapshot();
    }
    @Override
    public MailSnapshot snapshotWithoutUnreadCounts() {
        return client().snapshotWithoutUnreadCounts();
    }
    @Override
    public MailThreadPage loadThreads(String query, int offset, int limit, MailThreadFilter filter) {
        return client().loadThreads(query, offset, limit, filter);
    }
    @Override
    public MailMessageDetail loadMessage(long threadId) {
        return client().loadMessage(threadId);
    }
    @Override
    public MailMessageDetail loadMessageById(long messageId) {
        return client().loadMessageById(messageId);
    }
    @Override
    public List<MailMessageSummary> loadThreadMessages(long threadId) {
        return client().loadThreadMessages(threadId);
    }
    @Override
    public Map<Long, List<MailMessageSummary>> loadThreadMessages(List<Long> ids) {
        return client().loadThreadMessages(ids);
    }
    @Override
    public List<String> loadTagNames() {
        return client().loadTagNames();
    }
    @Override
    public Map<String, Integer> loadAccountUnreadCounts() {
        return client().loadAccountUnreadCounts();
    }
    @Override
    public Map<String, Integer> loadTagUnreadCounts() {
        return client().loadTagUnreadCounts();
    }
    @Override
    public Map<String, Integer> loadAccountUnsortedUnreadCounts() {
        return client().loadAccountUnsortedUnreadCounts();
    }
    @Override
    public int loadUnsortedUnreadCount() {
        return client().loadUnsortedUnreadCount();
    }
    @Override
    public void markMessageRead(long id) {
        client().markMessageRead(id);
    }
    @Override
    public void refresh() {
        client().refresh();
    }
    @Override
    public MailSendResult sendDraft(MailDraft draft) {
        return client().sendDraft(draft);
    }
    @Override
    public Path getDataPath() {
        return dataPath;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (ready.isDone() && !ready.isCompletedExceptionally()) {
            client().close();
        } else {
            ready.completeExceptionally(new java.util.concurrent.CancellationException("Mail closed during startup"));
        }
    }
}
