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
import androidx.datastore.preferences.core.intPreferencesKey
import org.koin.core.annotation.Single
import org.meshtastic.core.datastore.di.CorePreferencesDataStore
import kotlin.random.Random

@Single
class FleetBoardStore(private val dataStore: CorePreferencesDataStore) {
    suspend fun getOrCreate(gameId: String): FleetBoard {
        val key = intPreferencesKey(SEED_KEY_PREFIX + gameId)
        val preferences = dataStore.edit { current ->
            if (current[key] == null) current[key] = Random.nextInt()
        }
        val seed = requireNotNull(preferences[key]) { "A fleet-board seed must be persisted before loading its board." }
        return FleetBoard.random(Random(seed))
    }

    private companion object {
        const val SEED_KEY_PREFIX = "fleet-game-seed-"
    }
}
