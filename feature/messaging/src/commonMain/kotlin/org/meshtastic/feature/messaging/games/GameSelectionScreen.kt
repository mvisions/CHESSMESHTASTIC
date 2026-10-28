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

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.fleet_game_description
import org.meshtastic.core.resources.fleet_game_title
import org.meshtastic.core.resources.games_chess
import org.meshtastic.core.resources.games_chess_art
import org.meshtastic.core.resources.games_chess_description
import org.meshtastic.core.resources.games_choose_opponent
import org.meshtastic.core.resources.games_choose_title
import org.meshtastic.core.resources.games_fleet_art
import org.meshtastic.core.resources.games_menu
import org.meshtastic.core.resources.games_mines_art
import org.meshtastic.core.resources.games_vip_background
import org.meshtastic.core.resources.mines_game_description
import org.meshtastic.core.resources.mines_game_title
import org.meshtastic.core.ui.component.MainAppBar

@Composable
fun GameSelectionScreen(
    opponentName: String,
    onSelectChess: () -> Unit,
    onSelectFleet: () -> Unit,
    onSelectMines: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.games_menu),
                ourNode = null,
                showNodeChip = false,
                canNavigateUp = true,
                onNavigateUp = onNavigateBack,
                onClickChip = {},
                actions = {},
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(Res.drawable.games_vip_background),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            )
            GameSelectionContent(
                opponentName = opponentName,
                isWide = maxWidth >= GAME_CHOICES_ROW_BREAKPOINT,
                onSelectChess = onSelectChess,
                onSelectFleet = onSelectFleet,
                onSelectMines = onSelectMines,
            )
        }
    }
}

@Composable
private fun GameSelectionContent(
    opponentName: String,
    isWide: Boolean,
    onSelectChess: () -> Unit,
    onSelectFleet: () -> Unit,
    onSelectMines: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = stringResource(Res.string.games_choose_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.games_choose_opponent, opponentName),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        GameChoiceCards(isWide, onSelectChess, onSelectFleet, onSelectMines)
    }
}

@Composable
private fun GameChoiceCards(
    isWide: Boolean,
    onSelectChess: () -> Unit,
    onSelectFleet: () -> Unit,
    onSelectMines: () -> Unit,
) {
    val choices =
        listOf(
            GameChoiceOption(
                title = stringResource(Res.string.games_chess),
                description = stringResource(Res.string.games_chess_description),
                onClick = onSelectChess,
                artwork = Res.drawable.games_chess_art,
            ),
            GameChoiceOption(
                title = stringResource(Res.string.mines_game_title),
                description = stringResource(Res.string.mines_game_description),
                onClick = onSelectMines,
                artwork = Res.drawable.games_mines_art,
            ),
            GameChoiceOption(
                title = stringResource(Res.string.fleet_game_title),
                description = stringResource(Res.string.fleet_game_description),
                onClick = onSelectFleet,
                artwork = Res.drawable.games_fleet_art,
            ),
        )
    if (isWide) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            choices.forEach { choice -> GameChoiceCard(choice, Modifier.weight(1f)) }
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            choices.forEach { choice -> GameChoiceCard(choice) }
        }
    }
}

@Composable
private fun GameChoiceCard(option: GameChoiceOption, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClick = option.onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(option.artwork),
                contentDescription = null,
                modifier = Modifier.size(80.dp, 64.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(option.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    option.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class GameChoiceOption(
    val title: String,
    val description: String,
    val onClick: () -> Unit,
    val artwork: DrawableResource,
)

private val GAME_CHOICES_ROW_BREAKPOINT = 600.dp
