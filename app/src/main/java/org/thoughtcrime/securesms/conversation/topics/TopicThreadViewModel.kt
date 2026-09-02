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
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Backs [TopicThreadActivity]. See docs/topic-threads-design.md.
 *
 * Phase note: [sendMessage] is still a local-only insert
 * (MessageTable.insertTopicTextMessage), not a real network send -- that's the next slice
 * (reusing OutgoingMessage/MessageSender the same way the main conversation screen does; see the
 * "full parity" design discussion). Rendering, however, now goes through the same
 * MessageRecord -> ConversationMessage pipeline the main conversation screen uses (see
 * [TopicConversationRepository]), so bubbles/reactions/quotes/theming already match.
 */
class TopicThreadViewModel(private val threadId: Long, private val topicId: Long) : ViewModel() {

  private val repository = TopicConversationRepository(AppDependencies.application)

  private val internalUiState = MutableStateFlow(TopicThreadUiState())
  val uiState: StateFlow<TopicThreadUiState> = internalUiState.asStateFlow()

  val threadRecipient: Recipient by lazy { SignalDatabase.threads.getRecipientForThreadId(threadId)!! }

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch {
      val (topicName, messages) = withContext(Dispatchers.IO) {
        val topic = SignalDatabase.topics.getTopic(topicId)
        val messages = repository.loadMessages(topicId, threadRecipient)
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
  val messages: List<ConversationMessage> = emptyList(),
  val isLoading: Boolean = true,
  val isFinished: Boolean = false
)
