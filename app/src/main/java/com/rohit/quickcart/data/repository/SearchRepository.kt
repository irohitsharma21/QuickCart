package com.rohit.quickcart.data.repository

import com.rohit.quickcart.data.local.RecentSearchDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface SearchRepository {
    fun observeRecentSearches(): Flow<List<String>>
    suspend fun saveSearch(query: String)
    suspend fun clearRecentSearches()
}

@Singleton
class RoomSearchRepository @Inject constructor(
    private val dao: RecentSearchDao,
) : SearchRepository {

    override fun observeRecentSearches(): Flow<List<String>> = dao.observeRecent(SHOW)

    override suspend fun saveSearch(query: String) {
        val normalized = query.trim().lowercase()
        if (normalized.length < 2) return
        // Saving the same word again just moves it to the top (upsert on the primary key).
        dao.save(normalized, System.currentTimeMillis(), KEEP)
    }

    override suspend fun clearRecentSearches() = dao.clear()

    private companion object {
        const val SHOW = 6
        const val KEEP = 10
    }
}
