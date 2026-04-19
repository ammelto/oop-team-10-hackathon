package com.example.oop.wearables.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [VitalEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class WearableDatabase : RoomDatabase() {

    abstract fun vitalDao(): VitalDao

    companion object {
        private const val DB_NAME = "wearables.db"

        @Volatile
        private var instance: WearableDatabase? = null

        fun get(context: Context): WearableDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WearableDatabase::class.java,
                    DB_NAME,
                )
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
