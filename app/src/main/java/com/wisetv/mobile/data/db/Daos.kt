package com.wisetv.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Update
    suspend fun update(p: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET maxConnections = :max, expiresAt = :expires, status = :status WHERE id = :id")
    suspend fun setAuth(id: Long, max: Int, expires: Long, status: String)

    @Query("UPDATE playlists SET lastSync = :time WHERE id = :id")
    suspend fun setLastSync(id: Long, time: Long)

    @Query("UPDATE playlists SET m3uEpgUrl = :url WHERE id = :id")
    suspend fun setM3uEpg(id: Long, url: String)

    @Query("UPDATE playlists SET epgGen = :gen, lastEpgSync = :time WHERE id = :id")
    suspend fun setEpgGen(id: Long, gen: Long, time: Long)

    @Query("UPDATE playlists SET vodGen = :gen WHERE id = :id")
    suspend fun setVodGen(id: Long, gen: Long)
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId = :pid ORDER BY sortIndex")
    suspend fun allFor(pid: Long): List<ChannelEntity>

    @Query(
        """SELECT * FROM channels WHERE playlistId = :pid AND hidden = 0
           AND groupName NOT IN (SELECT categoryId FROM channel_groups WHERE playlistId = :pid AND kind = 'live' AND hidden = 1)
           ORDER BY sortIndex""",
    )
    fun observeVisible(pid: Long): Flow<List<ChannelEntity>>

    @Query(
        """SELECT * FROM channels WHERE playlistId = :pid AND hidden = 0
           AND groupName NOT IN (SELECT categoryId FROM channel_groups WHERE playlistId = :pid AND kind = 'live' AND hidden = 1)
           ORDER BY sortIndex""",
    )
    suspend fun visibleOnce(pid: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND groupName = :group AND hidden = 0 ORDER BY sortIndex")
    fun observeGroup(pid: Long, group: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND favorite = 1 ORDER BY favOrder, sortIndex")
    fun observeFavorites(pid: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND hidden = 1 ORDER BY sortIndex")
    fun observeHidden(pid: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND epgId = :epg ORDER BY hidden, sortIndex LIMIT 1")
    suspend fun firstByEpg(pid: Long, epg: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND url = :url LIMIT 1")
    suspend fun byUrl(pid: Long, url: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND hidden = 0 AND name LIKE '%' || :q || '%' ORDER BY sortIndex LIMIT 40")
    suspend fun search(pid: Long, q: String): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllBlocking(list: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :pid")
    fun deleteForPlaylistBlocking(pid: Long)

    @Query("DELETE FROM channels WHERE playlistId = :pid")
    suspend fun deleteForPlaylist(pid: Long)

    @Query("UPDATE channels SET favorite = :fav, favOrder = :order WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean, order: Int)

    @Query("SELECT COALESCE(MAX(favOrder), 0) FROM channels WHERE playlistId = :pid")
    suspend fun maxFavOrder(pid: Long): Int

    @Query("UPDATE channels SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE channels SET epgId = :epg WHERE id IN (:ids)")
    fun setEpgIdBlocking(ids: List<Long>, epg: String)

    @Query("SELECT groupName AS name, COUNT(*) AS cnt FROM channels WHERE playlistId = :pid GROUP BY groupName")
    suspend fun groupCounts(pid: Long): List<GroupCount>
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM channel_groups WHERE playlistId = :pid AND kind = :kind ORDER BY sortIndex")
    fun observe(pid: Long, kind: String): Flow<List<GroupEntity>>

    @Query("SELECT * FROM channel_groups WHERE playlistId = :pid AND kind = :kind AND hidden = 0 ORDER BY sortIndex")
    fun observeVisible(pid: Long, kind: String): Flow<List<GroupEntity>>

    @Query("SELECT * FROM channel_groups WHERE playlistId = :pid AND kind = :kind ORDER BY sortIndex")
    fun allBlocking(pid: Long, kind: String): List<GroupEntity>

    @Query("DELETE FROM channel_groups WHERE playlistId = :pid AND kind = :kind")
    fun deleteBlocking(pid: Long, kind: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllBlocking(list: List<GroupEntity>)

    @Query("UPDATE channel_groups SET hidden = :hidden WHERE playlistId = :pid AND kind = :kind AND categoryId = :cat")
    suspend fun setHidden(pid: Long, kind: String, cat: String, hidden: Boolean)

    @Query("UPDATE channel_groups SET hidden = :hidden WHERE playlistId = :pid AND kind = :kind")
    suspend fun setAllHidden(pid: Long, kind: String, hidden: Boolean)

    @Query("DELETE FROM channel_groups WHERE playlistId = :pid")
    suspend fun deleteForPlaylist(pid: Long)
}

@Dao
interface ProgramDao {
    @Insert
    fun insertAllBlocking(list: List<ProgramEntity>)

    @Query("SELECT * FROM programs WHERE playlistId = :pid AND gen = :gen AND epgKey = :key AND stop > :from AND start < :to ORDER BY start")
    suspend fun forChannel(pid: Long, gen: Long, key: String, from: Long, to: Long): List<ProgramEntity>

    @Query("SELECT * FROM programs WHERE playlistId = :pid AND gen = :gen AND epgKey = :key AND start <= :now AND stop > :now LIMIT 1")
    suspend fun nowOn(pid: Long, gen: Long, key: String, now: Long): ProgramEntity?

    @Query("SELECT * FROM programs WHERE playlistId = :pid AND gen = :gen AND title = :title COLLATE NOCASE AND stop > :now ORDER BY start LIMIT 300")
    suspend fun matchTitle(pid: Long, gen: Long, title: String, now: Long): List<ProgramEntity>

    @Query("SELECT * FROM programs WHERE playlistId = :pid AND gen = :gen AND epgKey = :key AND title = :title COLLATE NOCASE AND stop > :now ORDER BY start LIMIT 300")
    suspend fun matchTitleOn(pid: Long, gen: Long, key: String, title: String, now: Long): List<ProgramEntity>

    @Query("SELECT * FROM programs WHERE playlistId = :pid AND gen = :gen AND stop > :now AND title LIKE '%' || :q || '%' ORDER BY start LIMIT 60")
    suspend fun search(pid: Long, gen: Long, q: String, now: Long): List<ProgramEntity>

    @Query("DELETE FROM programs WHERE playlistId = :pid AND gen <> :gen")
    suspend fun deleteOtherGens(pid: Long, gen: Long)

    @Query("DELETE FROM programs WHERE playlistId = :pid AND gen = :gen")
    suspend fun deleteGen(pid: Long, gen: Long)

    @Query("DELETE FROM programs WHERE playlistId = :pid")
    suspend fun deleteForPlaylist(pid: Long)
}

@Dao
interface VodDao {
    @Insert
    fun insertAllBlocking(list: List<VodEntity>)

    @Query("SELECT * FROM vod WHERE playlistId = :pid AND gen = :gen AND categoryId = :cat ORDER BY id")
    suspend fun byCategory(pid: Long, gen: Long, cat: String): List<VodEntity>

    @Query("SELECT * FROM vod WHERE playlistId = :pid AND gen = :gen ORDER BY added DESC, id DESC LIMIT :limit")
    suspend fun recent(pid: Long, gen: Long, limit: Int): List<VodEntity>

    @Query("SELECT * FROM vod WHERE playlistId = :pid AND gen = :gen AND name LIKE '%' || :q || '%' ORDER BY name LIMIT 40")
    suspend fun search(pid: Long, gen: Long, q: String): List<VodEntity>

    @Query("SELECT * FROM vod WHERE playlistId = :pid AND streamId = :sid ORDER BY gen DESC LIMIT 1")
    suspend fun byStreamId(pid: Long, sid: String): VodEntity?

    @Query("SELECT * FROM vod WHERE playlistId = :pid AND gen = :gen AND streamId IN (:ids)")
    suspend fun byStreamIds(pid: Long, gen: Long, ids: List<String>): List<VodEntity>

    @Query("DELETE FROM vod WHERE playlistId = :pid AND gen <> :gen")
    suspend fun deleteOtherGens(pid: Long, gen: Long)

    @Query("DELETE FROM vod WHERE playlistId = :pid AND gen = :gen")
    suspend fun deleteGen(pid: Long, gen: Long)

    @Query("DELETE FROM vod WHERE playlistId = :pid")
    suspend fun deleteForPlaylist(pid: Long)
}

@Dao
interface SeriesDao {
    @Insert
    fun insertAllBlocking(list: List<SeriesEntity>)

    @Query("SELECT * FROM series WHERE playlistId = :pid AND gen = :gen AND categoryId = :cat ORDER BY id")
    suspend fun byCategory(pid: Long, gen: Long, cat: String): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE playlistId = :pid AND gen = :gen ORDER BY lastModified DESC, id DESC LIMIT :limit")
    suspend fun recent(pid: Long, gen: Long, limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE playlistId = :pid AND gen = :gen AND name LIKE '%' || :q || '%' ORDER BY name LIMIT 40")
    suspend fun search(pid: Long, gen: Long, q: String): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE playlistId = :pid AND seriesId = :sid ORDER BY gen DESC LIMIT 1")
    suspend fun bySeriesId(pid: Long, sid: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE playlistId = :pid AND gen = :gen AND seriesId IN (:ids)")
    suspend fun bySeriesIds(pid: Long, gen: Long, ids: List<String>): List<SeriesEntity>

    @Query("DELETE FROM series WHERE playlistId = :pid AND gen <> :gen")
    suspend fun deleteOtherGens(pid: Long, gen: Long)

    @Query("DELETE FROM series WHERE playlistId = :pid AND gen = :gen")
    suspend fun deleteGen(pid: Long, gen: Long)

    @Query("DELETE FROM series WHERE playlistId = :pid")
    suspend fun deleteForPlaylist(pid: Long)
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("SELECT `key` FROM favorites WHERE playlistId = :pid ORDER BY addedAt DESC")
    fun observeKeys(pid: Long): Flow<List<String>>

    @Query("SELECT `key` FROM favorites WHERE playlistId = :pid ORDER BY addedAt DESC")
    suspend fun keys(pid: Long): List<String>

    @Query("DELETE FROM favorites WHERE playlistId = :pid")
    suspend fun deleteForPlaylist(pid: Long)
}

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProgressEntity)

    @Query("SELECT * FROM progress WHERE `key` = :key")
    fun observe(key: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE `key` = :key")
    suspend fun get(key: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE playlistId = :pid AND seriesKey = :sid")
    fun observeSeries(pid: Long, sid: String): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE playlistId = :pid AND kind = 'movie' AND finished = 0 AND position > 60000 ORDER BY updatedAt DESC LIMIT 40")
    suspend fun continueMovies(pid: Long): List<ProgressEntity>

    @Query(
        """SELECT seriesKey FROM progress WHERE playlistId = :pid AND kind = 'episode' AND seriesKey IS NOT NULL
           GROUP BY seriesKey ORDER BY MAX(updatedAt) DESC LIMIT 40""",
    )
    suspend fun recentSeriesKeys(pid: Long): List<String>

    @Query("DELETE FROM progress WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM progress WHERE playlistId = :pid AND kind <> 'recording'")
    suspend fun deleteForPlaylist(pid: Long)
}
