package com.wisetv.mobile.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object PlaylistType {
    const val XTREAM = "xtream"
    const val M3U = "m3u"
}

object GroupKind {
    const val LIVE = "live"
    const val MOVIE = "movie"
    const val SERIES = "series"
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    /** Xtream: server base URL. M3U: playlist URL. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Extra / override EPG URLs entered by the user (newline separated). */
    val epgUrl: String = "",
    /** EPG URLs advertised in the M3U header. */
    val m3uEpgUrl: String = "",
    val userAgent: String = "",
    val maxConnections: Int = 1,
    val expiresAt: Long = 0,
    val status: String = "",
    val lastSync: Long = 0,
    val lastEpgSync: Long = 0,
    val epgGen: Long = 0,
    val vodGen: Long = 0,
    val epgShiftMinutes: Int = 0,
)

@Entity(
    tableName = "channels",
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "groupName"]),
        Index(value = ["playlistId", "epgId"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    /** Stable key from the provider (Xtream stream id, or the URL for M3U). */
    val streamKey: String,
    val name: String,
    val number: Int,
    val logo: String? = null,
    val groupName: String,
    /** Lower-cased XMLTV channel id. */
    val epgId: String = "",
    val url: String,
    val userAgent: String? = null,
    val favorite: Boolean = false,
    val favOrder: Int = 0,
    val hidden: Boolean = false,
    val sortIndex: Int = 0,
)

@Entity(tableName = "channel_groups", primaryKeys = ["playlistId", "kind", "categoryId"])
data class GroupEntity(
    val playlistId: Long,
    val kind: String,
    val categoryId: String,
    val name: String,
    val hidden: Boolean = false,
    val sortIndex: Int = 0,
)

data class GroupCount(val name: String, val cnt: Int)

@Entity(
    tableName = "programs",
    indices = [Index(value = ["playlistId", "gen", "epgKey", "start"])],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val gen: Long,
    val epgKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    val episode: String? = null,
    val icon: String? = null,
    val isNew: Boolean = false,
)

@Entity(
    tableName = "vod",
    indices = [Index(value = ["playlistId", "gen", "categoryId"]), Index(value = ["playlistId", "streamId"])],
)
data class VodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val gen: Long,
    val streamId: String,
    val name: String,
    val poster: String? = null,
    val rating: String? = null,
    val categoryId: String,
    val ext: String = "mp4",
    val added: Long = 0,
    val directUrl: String? = null,
    val year: String? = null,
)

@Entity(
    tableName = "series",
    indices = [Index(value = ["playlistId", "gen", "categoryId"]), Index(value = ["playlistId", "seriesId"])],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val gen: Long,
    val seriesId: String,
    val name: String,
    val cover: String? = null,
    val plot: String? = null,
    val rating: String? = null,
    val genre: String? = null,
    val categoryId: String,
    val lastModified: Long = 0,
    val year: String? = null,
)

/** Favorite movie or series, keyed "movie:pid:streamId" / "series:pid:seriesId". */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val key: String,
    val playlistId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "progress", indices = [Index(value = ["playlistId", "seriesKey"])])
data class ProgressEntity(
    @PrimaryKey val key: String,
    val playlistId: Long,
    /** "movie" or "episode" */
    val kind: String,
    val refId: String,
    val title: String,
    val poster: String? = null,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val seriesKey: String? = null,
    val season: Int? = null,
    val episodeNum: Int? = null,
    val finished: Boolean = false,
)
