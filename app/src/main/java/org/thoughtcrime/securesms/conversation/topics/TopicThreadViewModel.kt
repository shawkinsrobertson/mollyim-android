/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.topics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Backs [TopicThreadActivity]/[TopicThreadScreen]. See docs/topic-threads-design.md.
 *
 * Phase 1 note: sending a message here is a local-only insert
 * (MessageTable.insertTopicTextMessage), not a real network send -- there is
 * no wire protocol yet for topic messages to reach other participants (a
 * later phase). This screen exists to make the local data model testable.
 */
class TopicThreadViewModel(private val threadId: Long, private val topicId: Long) : ViewModel() {

  private val internalUiState = MutableStateFlow(TopicThreadUiState())
  val uiState: StateFlow<TopicThreadUiState> = internalUiState.asStateFlow()

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch {
      val (topicName, messages) = withContext(Dispatchers.IO) {
        val topic = SignalDatabase.topics.getTopic(topicId)
        val messages = SignalDatabase.messages.getTopicMessages(topicId).map { message ->
          val sender = Recipient.resolved(message.fromRecipientId)
          TopicMessageUiModel(
            id = message.id,
            senderName = if (sender.isSelf) null else sender.getShortDisplayName(AppDependencies.application),
            body = message.body,
            isOutgoing = sender.isSelf
          )
        }
        (topic?.name ?: "") to messages
      }

      internalUiState.update {
        it.copy(topicName = topicName, messages = messages, isLoading = false)
      }
    }
  }

  fun sendMessage(body: String) {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return

    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        SignalDatabase.messages.insertTopicTextMessage(threadId, topicId, trimmed)
      }
      refresh()
    }
  }

  fun renameTopic(newName: String) {
    val trimmed = newName.trim()
    if (trimmed.isEmpty()) return

    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        SignalDatabase.messages.renameTopic(threadId, topicId, trimmed)
      }
      refresh()
    }
  }

  fun deleteTopic() {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        SignalDatabase.messages.endTopic(threadId, topicId)
      }
      internalUiState.update { it.copy(isFinished = true) }
    }
  }
}

data class TopicThreadUiState(
  val topicName: String = "",
  val messages: List<TopicMessageUiModel> = emptyList(),
  val isLoading: Boolean = true,
  val isFinished: Boolean = false
)

data class TopicMessageUiModel(
  val id: Long,
  val senderName: String?,
  val body: String,
  val isOutgoing: Boolean
)
