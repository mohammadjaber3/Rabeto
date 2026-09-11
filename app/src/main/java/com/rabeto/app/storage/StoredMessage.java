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

    /**
     * The exact wire envelope. For inbox and outbox rows this stays ciphertext,
     * so a retransmission is byte-identical and the signature still verifies.
     */
    public String envelopeJson;

    /**
     * Schema v2. Local-only readable copy of the message body.
     *
     * Why a separate column instead of decrypting history on demand: once
     * Phase B introduces forward secrecy, the session key that decrypted this
     * message is deliberately destroyed. Anything not stored in readable form
     * at receive time is gone forever. Relay rows keep this null: we carry other
     * people's traffic, we never read it.
     */
    public String plainText;

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

        this.plainText = null;
        this.attempts = 0;
        this.lastAttemptAt = 0L;
        this.nextAttemptAt = 0L;
    }
}
