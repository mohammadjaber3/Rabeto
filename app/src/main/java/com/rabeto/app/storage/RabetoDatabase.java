package com.rabeto.app.storage;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * Rabeto local database.
 *
 * The migration below is deliberately hand-written rather than
 * fallbackToDestructiveMigration(). Rabeto has no server, so the phone IS the
 * only copy of a user's history. Destructive migration in a product like this
 * means "we deleted your chats during an update", which is unacceptable even
 * once. Every future schema change gets a numbered migration here.
 */
@Database(
        entities = {
                StoredMessage.class
        },
        version = 2,
        exportSchema = true
)
public abstract class RabetoDatabase extends RoomDatabase {

    public abstract StoredMessageDao storedMessageDao();

    /** v1 -> v2: local readable body, needed before forward secrecy lands. */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE stored_messages ADD COLUMN plainText TEXT");
        }
    };

    public static final Migration[] ALL_MIGRATIONS = new Migration[]{
            MIGRATION_1_2
    };
}
