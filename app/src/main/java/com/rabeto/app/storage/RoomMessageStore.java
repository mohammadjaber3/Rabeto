package com.rabeto.app.storage;

import android.content.Context;
import android.util.Log;

import androidx.room.Room;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Room-backed MessageStore.
 *
 * Kept as-is from Phase 0 (it was already sound: single writer thread, every
 * DAO call wrapped, callbacks never swallowed) with the Phase A queries added
 * at the bottom.
 */
public final class RoomMessageStore implements MessageStore {

    private static final String TAG = "RabetoMessageStore";
    private static final String DB_NAME = "rabeto_messages.db";

    private final RabetoDatabase database;
    private final StoredMessageDao dao;
    private final ExecutorService executor;

    public RoomMessageStore(Context context) {
        Context appContext = context.getApplicationContext();

        database = Room.databaseBuilder(
                appContext,
                RabetoDatabase.class,
                DB_NAME
        )
                .addMigrations(RabetoDatabase.ALL_MIGRATIONS)
                .build();

        dao = database.storedMessageDao();
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void put(final StoredMessage message, final ResultCallback<Boolean> callback) {
        executor.execute(() -> {
            boolean ok = false;
            try {
                if (message != null
                        && message.messageId != null
                        && !message.messageId.isEmpty()) {
                    ok = dao.insert(message) != -1L;
                }
            } catch (Throwable t) {
                Log.e(TAG, "put failed", t);
            }
            if (callback != null) callback.onResult(ok);
        });
    }

    @Override
    public void find(final String messageId, final int role,
                     final ResultCallback<StoredMessage> callback) {
        executor.execute(() -> {
            StoredMessage result = null;
            try {
                if (messageId != null && !messageId.isEmpty()) {
                    result = dao.findByIdAndRole(messageId, role);
                }
            } catch (Throwable t) {
                Log.e(TAG, "find failed", t);
            }
            if (callback != null) callback.onResult(result);
        });
    }

    @Override
    public void active(final int role, final long now, final int limit,
                       final ResultCallback<List<StoredMessage>> callback) {
        executor.execute(() -> {
            List<StoredMessage> result = null;
            try {
                result = dao.findActive(role, now, Math.max(1, Math.min(limit, 500)));
            } catch (Throwable t) {
                Log.e(TAG, "active failed", t);
            }
            if (callback != null) callback.onResult(result);
        });
    }

    @Override
    public void history(final String peerId, final int limit,
                        final ResultCallback<List<StoredMessage>> callback) {
        executor.execute(() -> {
            List<StoredMessage> result = null;
            try {
                if (peerId != null && !peerId.isEmpty()) {
                    result = dao.findHistory(peerId, Math.max(1, Math.min(limit, 1000)));
                }
            } catch (Throwable t) {
                Log.e(TAG, "history failed", t);
            }
            if (callback != null) callback.onResult(result);
        });
    }

    @Override
    public void transitionStatus(final String messageId, final int role,
                                 final int expectedStatus, final int newStatus,
                                 final ResultCallback<Boolean> callback) {
        executor.execute(() -> {
            boolean ok = false;
            try {
                ok = dao.transitionStatus(messageId, role, expectedStatus, newStatus) == 1;
            } catch (Throwable t) {
                Log.e(TAG, "transitionStatus failed", t);
            }
            if (callback != null) callback.onResult(ok);
        });
    }

    @Override
    public void recordAttempt(final String messageId, final int role, final long now,
                              final ResultCallback<Boolean> callback) {
        executor.execute(() -> {
            boolean ok = false;
            try {
                StoredMessage current = dao.findByIdAndRole(messageId, role);
                if (current != null) {
                    long delay;
                    if (current.attempts <= 0) {
                        delay = 5_000L;
                    } else {
                        long multiplier = 1L << Math.min(current.attempts, 6);
                        delay = Math.min(300_000L, 5_000L * multiplier);
                    }
                    ok = dao.recordAttempt(messageId, role, now, now + delay) == 1;
                }
            } catch (Throwable t) {
                Log.e(TAG, "recordAttempt failed", t);
            }
            if (callback != null) callback.onResult(ok);
        });
    }

    @Override
    public void markExpired(final long now, final ResultCallback<Integer> callback) {
        executor.execute(() -> {
            int result = 0;
            try {
                result = dao.markExpired(now);
            } catch (Throwable t) {
                Log.e(TAG, "markExpired failed", t);
            }
            if (callback != null) callback.onResult(result);
        });
    }

    @Override
    public void deleteExpiredRelay(final long now, final ResultCallback<Integer> callback) {
        executor.execute(() -> {
            int result = 0;
            try {
                result = dao.deleteExpiredRelay(now);
            } catch (Throwable t) {
                Log.e(TAG, "deleteExpiredRelay failed", t);
            }
            if (callback != null) callback.onResult(result);
        });
    }

    @Override
    public void delete(final String messageId, final int role,
                       final ResultCallback<Boolean> callback) {
        executor.execute(() -> {
            boolean ok = false;
            try {
                ok = dao.deleteByIdAndRole(messageId, role) == 1;
            } catch (Throwable t) {
                Log.e(TAG, "delete failed", t);
            }
            if (callback != null) callback.onResult(ok);
        });
    }

    @Override
    public void countActive(final int role, final long now,
                            final ResultCallback<Integer> callback) {
        executor.execute(() -> {
            int result = 0;
            try {
                result = dao.countActive(role, now);
            } catch (Throwable t) {
                Log.e(TAG, "countActive failed", t);
            }
            if (callback != null) callback.onResult(result);
        });
    }

    // ------------------------------------------------------------ Phase A additions

    @Override
    public void conversation(final String peerId, final int limit,
                             final ResultCallback<List<StoredMessage>> callback) {
        executor.execute(() -> {
            List<StoredMessage> result = null;
            try {
                if (peerId != null && !peerId.isEmpty()) {
                    result = dao.findConversation(peerId, Math.max(1, Math.min(limit, 2000)));
                }
            } catch (Throwable t) {
                Log.e(TAG, "conversation failed", t);
            }
            if (callback != null) callback.onResult(result);
        });
    }

    @Override
    public void recent(final int limit, final ResultCallback<List<StoredMessage>> callback) {
        executor.execute(() -> {
            List<StoredMessage> result = null;
            try {
                result = dao.findRecent(Math.max(1, Math.min(limit, 2000)));
            } catch (Throwable t) {
                Log.e(TAG, "recent failed", t);
            }
            if (callback != null) callback.onResult(result);
        });
    }

    @Override
    public void unread(final String peerId, final ResultCallback<Integer> callback) {
        executor.execute(() -> {
            int result = 0;
            try {
                if (peerId != null && !peerId.isEmpty()) result = dao.countUnread(peerId);
            } catch (Throwable t) {
                Log.e(TAG, "unread failed", t);
            }
            if (callback != null) callback.onResult(result);
        });
    }

    @Override
    public void markThreadRead(final String peerId, final ResultCallback<Integer> callback) {
        executor.execute(() -> {
            int result = 0;
            try {
                if (peerId != null && !peerId.isEmpty()) result = dao.markThreadRead(peerId);
            } catch (Throwable t) {
                Log.e(TAG, "markThreadRead failed", t);
            }
            if (callback != null) callback.onResult(result);
        });
    }

    @Override
    public void trimRelay(final int maxRows, final ResultCallback<Integer> callback) {
        executor.execute(() -> {
            int removed = 0;
            try {
                int total = dao.countRelay();
                int over = total - Math.max(64, maxRows);
                if (over > 0) removed = dao.trimOldestRelay(over);
            } catch (Throwable t) {
                Log.e(TAG, "trimRelay failed", t);
            }
            if (callback != null) callback.onResult(removed);
        });
    }

    @Override
    public void close() {
        try {
            executor.shutdown();
        } catch (Throwable t) {
            Log.e(TAG, "executor shutdown failed", t);
        }
        try {
            database.close();
        } catch (Throwable t) {
            Log.e(TAG, "database close failed", t);
        }
    }
}
