/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.topics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.R
import org.signal.core.ui.R as CoreUiR

/**
 * See [TopicThreadActivity]/[TopicThreadViewModel] and
 * docs/topic-threads-design.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicThreadScreen(
  uiState: TopicThreadUiState,
  onBackClick: () -> Unit,
  onSendMessage: (String) -> Unit,
  onRenameConfirmed: (String) -> Unit,
  onDeleteConfirmed: () -> Unit
) {
  var showOverflowMenu by remember { mutableStateOf(false) }
  var showRenameDialog by remember { mutableStateOf(false) }
  var showDeleteDialog by remember { mutableStateOf(false) }
  var composeText by remember { mutableStateOf("") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(text = uiState.topicName) },
        navigationIcon = {
          IconButton(onClick = onBackClick) {
            Icon(
              painter = painterResource(CoreUiR.drawable.symbol_arrow_start_24),
              contentDescription = stringResource(R.string.DefaultTopAppBar__navigate_up_content_description)
            )
          }
        },
        actions = {
          IconButton(onClick = { showOverflowMenu = true }) {
            Icon(
              painter = painterResource(CoreUiR.drawable.symbol_more_vertical_24),
              contentDescription = null
            )
          }
          DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.ConversationTopics__rename_topic)) },
              onClick = {
                showOverflowMenu = false
                showRenameDialog = true
              }
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.ConversationTopics__delete_topic)) },
              onClick = {
                showOverflowMenu = false
                showDeleteDialog = true
              }
            )
          }
        }
      )
    }
  ) { paddingValues ->
    Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
      ) {
        items(uiState.messages, key = { it.id }) { message ->
          TopicMessageRow(message)
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = composeText,
          onValueChange = { composeText = it },
          modifier = Modifier.weight(1f),
          placeholder = { Text(stringResource(R.string.ConversationTopics__message_hint)) }
        )
        IconButton(
          onClick = {
            if (composeText.isNotBlank()) {
              onSendMessage(composeText)
              composeText = ""
            }
          }
        ) {
          Icon(
            painter = painterResource(R.drawable.symbol_send_24),
            contentDescription = stringResource(R.string.conversation_activity__send)
          )
        }
      }
    }
  }

  if (showRenameDialog) {
    var renameText by remember { mutableStateOf(uiState.topicName) }
    AlertDialog(
      onDismissRequest = { showRenameDialog = false },
      title = { Text(stringResource(R.string.ConversationTopics__rename_topic)) },
      text = {
        OutlinedTextField(
          value = renameText,
          onValueChange = { renameText = it },
          placeholder = { Text(stringResource(R.string.ConversationTopics__topic_name_hint)) }
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            showRenameDialog = false
            onRenameConfirmed(renameText)
          }
        ) {
          Text(stringResource(android.R.string.ok))
        }
      },
      dismissButton = {
        TextButton(onClick = { showRenameDialog = false }) {
          Text(stringResource(android.R.string.cancel))
        }
      }
    )
  }

  if (showDeleteDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteDialog = false },
      title = { Text(stringResource(R.string.ConversationTopics__delete_topic_question, uiState.topicName)) },
      text = { Text(stringResource(R.string.ConversationTopics__delete_topic_body)) },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteDialog = false
            onDeleteConfirmed()
          }
        ) {
          Text(stringResource(R.string.ConversationTopics__delete_for_everyone))
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            showDeleteDialog = false
            onDeleteConfirmed()
          }
        ) {
          Text(stringResource(R.string.ConversationTopics__delete_for_me))
        }
      }
    )
  }
}

@Composable
private fun TopicMessageRow(message: TopicMessageUiModel) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
  ) {
    if (message.senderName != null) {
      Text(
        text = message.senderName,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
      )
    }
    Text(
      text = message.body,
      style = MaterialTheme.typography.bodyLarge
    )
  }
}
