package com.rabeto.app.storage;

import java.util.List;

public interface MessageStore {

    int ROLE_OUTBOX = 0;
    int ROLE_RELAY = 1;
    int ROLE_INBOX = 2;

    int STATUS_QUEUED = 0;
    int STATUS_RELAYED = 1;
    int STATUS_DELIVERED = 2;
    int STATUS_READ = 3;
    int STATUS_EXPIRED = 4;
    int STATUS_FAILED = 5;

    interface ResultCallback<T> {
        void onResult(T result);
    }

    void put(
            StoredMessage message,
            ResultCallback<Boolean> callback
    );

    void find(
            String messageId,
            int role,
            ResultCallback<StoredMessage> callback
    );

    void active(
            int role,
            long now,
            int limit,
            ResultCallback<List<StoredMessage>> callback
    );

    void history(
            String peerId,
            int limit,
            ResultCallback<List<StoredMessage>> callback
    );

    void transitionStatus(
            String messageId,
            int role,
            int expectedStatus,
            int newStatus,
            ResultCallback<Boolean> callback
    );

    void recordAttempt(
            String messageId,
            int role,
            long now,
            ResultCallback<Boolean> callback
    );

    void markExpired(
            long now,
            ResultCallback<Integer> callback
    );

    void deleteExpiredRelay(
            long now,
            ResultCallback<Integer> callback
    );

    void delete(
            String messageId,
            int role,
            ResultCallback<Boolean> callback
    );

    void countActive(
            int role,
            long now,
            ResultCallback<Integer> callback
    );

    void close();
}
