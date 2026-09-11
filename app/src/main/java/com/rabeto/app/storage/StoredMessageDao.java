package com.rabeto.app.storage;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface StoredMessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(StoredMessage message);

    @Query("""
            SELECT * FROM stored_messages
            WHERE role = :role
              AND expiresAt > :now
              AND status IN (0, 1)
              AND (nextAttemptAt = 0 OR nextAttemptAt <= :now)
            ORDER BY createdAt ASC
            LIMIT :limit
            """)
    List<StoredMessage> findActive(
            int role,
            long now,
            int limit
    );

    @Query("""
            SELECT * FROM stored_messages
            WHERE messageId = :messageId
            ORDER BY role ASC
            LIMIT 1
            """)
    StoredMessage findById(String messageId);

    @Query("""
            SELECT * FROM stored_messages
            WHERE messageId = :messageId
              AND role = :role
            LIMIT 1
            """)
    StoredMessage findByIdAndRole(
            String messageId,
            int role
    );

    @Query("""
            SELECT * FROM stored_messages
            WHERE role = 2
              AND (senderId = :peerId OR recipientId = :peerId)
            ORDER BY createdAt DESC
            LIMIT :limit
            """)
    List<StoredMessage> findHistory(
            String peerId,
            int limit
    );

    /**
     * Phase A addition: a chat thread is both sides of the conversation.
     * findHistory only returned role 2 (inbox), so the UI could never render
     * the user's own sent messages. Query-only change, no schema migration.
     */
    @Query("""
            SELECT * FROM stored_messages
            WHERE role IN (0, 2)
              AND (senderId = :peerId OR recipientId = :peerId)
            ORDER BY createdAt ASC
            LIMIT :limit
            """)
    List<StoredMessage> findConversation(
            String peerId,
            int limit
    );

    /** Most recent message per peer, for the chat list. */
    @Query("""
            SELECT * FROM stored_messages
            WHERE role IN (0, 2)
            ORDER BY createdAt DESC
            LIMIT :limit
            """)
    List<StoredMessage> findRecent(int limit);

    @Query("""
            SELECT COUNT(*) FROM stored_messages
            WHERE role = 2
              AND senderId = :peerId
              AND status < 3
            """)
    int countUnread(String peerId);

    @Query("""
            UPDATE stored_messages
            SET status = 3
            WHERE role = 2
              AND senderId = :peerId
              AND status < 3
            """)
    int markThreadRead(String peerId);

    @Query("""
            UPDATE stored_messages
            SET status = :newStatus
            WHERE messageId = :messageId
              AND role = :role
              AND status = :expectedStatus
            """)
    int transitionStatus(
            String messageId,
            int role,
            int expectedStatus,
            int newStatus
    );

    @Query("""
            UPDATE stored_messages
            SET attempts = attempts + 1,
                lastAttemptAt = :now,
                nextAttemptAt = :nextAttemptAt
            WHERE messageId = :messageId
              AND role = :role
              AND status IN (0, 1)
              AND expiresAt > :now
            """)
    int recordAttempt(
            String messageId,
            int role,
            long now,
            long nextAttemptAt
    );

    @Query("""
            UPDATE stored_messages
            SET status = 4
            WHERE expiresAt <= :now
              AND status IN (0, 1)
            """)
    int markExpired(long now);

    @Query("""
            DELETE FROM stored_messages
            WHERE role = 1
              AND expiresAt <= :now
            """)
    int deleteExpiredRelay(long now);

    /** Relay cache ceiling: oldest relay rows go first when we are over budget. */
    @Query("""
            DELETE FROM stored_messages
            WHERE role = 1
              AND messageId IN (
                SELECT messageId FROM stored_messages
                WHERE role = 1
                ORDER BY createdAt ASC
                LIMIT :count
              )
            """)
    int trimOldestRelay(int count);

    @Query("SELECT COUNT(*) FROM stored_messages WHERE role = 1")
    int countRelay();

    @Query("""
            DELETE FROM stored_messages
            WHERE messageId = :messageId
            """)
    int deleteById(String messageId);

    @Query("""
            DELETE FROM stored_messages
            WHERE messageId = :messageId
              AND role = :role
            """)
    int deleteByIdAndRole(
            String messageId,
            int role
    );

    @Query("""
            SELECT COUNT(*) FROM stored_messages
            WHERE role = :role
              AND expiresAt > :now
              AND status IN (0, 1)
            """)
    int countActive(
            int role,
            long now
    );
}
