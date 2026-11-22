package com.wisetv.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        GroupEntity::class,
        ProgramEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        FavoriteEntity::class,
        ProgressEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun groups(): GroupDao
    abstract fun programs(): ProgramDao
    abstract fun vod(): VodDao
    abstract fun series(): SeriesDao
    abstract fun favorites(): FavoriteDao
    abstract fun progress(): ProgressDao

    companion object {
        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "wisetv.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
