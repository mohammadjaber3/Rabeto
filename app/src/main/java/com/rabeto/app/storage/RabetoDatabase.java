package com.rabeto.app.storage;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                StoredMessage.class
        },
        version = 1,
        exportSchema = false
)
public abstract class RabetoDatabase extends RoomDatabase {

    public abstract StoredMessageDao storedMessageDao();
}
