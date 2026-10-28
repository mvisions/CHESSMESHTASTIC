/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.messaging.games

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import org.meshtastic.core.datastore.di.CorePreferencesDataStore

@Single
class MinesGameStore(private val dataStore: CorePreferencesDataStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("mines-games-v1")
    private val seenKey = stringPreferencesKey("mines-message-watermarks-v1")

    suspend fun load(): Map<String, MinesSession> =
        dataStore.data.first()[key]?.let { json.decodeFromString<Map<String, MinesSession>>(it) }.orEmpty()

    suspend fun save(session: MinesSession): Map<String, MinesSession> {
        var savedSessions = emptyMap<String, MinesSession>()
        dataStore.edit { preferences ->
            val sessions = preferences[key]?.let { json.decodeFromString<Map<String, MinesSession>>(it) }.orEmpty()
            val updatedSessions = sessions - session.gameId + (session.gameId to session)
            val terminalIds =
                updatedSessions.values
                    .filter { it.phase == MinesPhase.DECLINED || it.phase == MinesPhase.FINISHED }
                    .map(MinesSession::gameId)
            val prunedTerminalIds = terminalIds.dropLast(MAX_RETAINED_TERMINAL_GAMES)
            savedSessions = updatedSessions - prunedTerminalIds.toSet()
            preferences[key] = json.encodeToString(savedSessions)
        }
        return savedSessions
    }

    suspend fun watermarks(): Map<String, Long> =
        dataStore.data.first()[seenKey]?.let { json.decodeFromString<Map<String, Long>>(it) }.orEmpty()

    suspend fun markSeen(contact: String, uuid: Long) {
        dataStore.edit { preferences ->
            val watermarks = preferences[seenKey]?.let { json.decodeFromString<Map<String, Long>>(it) }.orEmpty()
            preferences[seenKey] = json.encodeToString(watermarks + (contact to maxOf(uuid, watermarks[contact] ?: 0L)))
        }
    }

    private companion object {
        const val MAX_RETAINED_TERMINAL_GAMES = 50
    }
}
