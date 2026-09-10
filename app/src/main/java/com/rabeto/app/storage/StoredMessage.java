package com.rabeto.app.storage;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.annotation.NonNull;

@Entity(
        tableName = "stored_messages",
        primaryKeys = {"messageId", "role"},
        indices = {
                @Index(value = {"role", "status", "expiresAt", "nextAttemptAt"}),
                @Index(value = {"role", "createdAt"}),
                @Index(value = {"recipientId", "createdAt"}),
                @Index(value = {"senderId", "createdAt"}),
                @Index(value = {"messageId"})
        }
)
public class StoredMessage {

    @NonNull
    public String messageId;
    public String senderId;
    public String recipientId;
    public String envelopeJson;

    public long createdAt;
    public long expiresAt;

    public int role;
    public int status;

    public int attempts;
    public long lastAttemptAt;
    public long nextAttemptAt;

    public StoredMessage(
            @NonNull String messageId,
            String senderId,
            String recipientId,
            String envelopeJson,
            long createdAt,
            long expiresAt,
            int role,
            int status) {

        this.messageId = messageId;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.envelopeJson = envelopeJson;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.role = role;
        this.status = status;

        this.attempts = 0;
        this.lastAttemptAt = 0L;
        this.nextAttemptAt = 0L;
    }
}
