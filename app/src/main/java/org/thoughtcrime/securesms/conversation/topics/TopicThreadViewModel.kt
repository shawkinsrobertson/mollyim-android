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
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import kotlin.time.Duration.Companion.seconds

/**
 * Backs [TopicThreadActivity]. See docs/topic-threads-design.md.
 *
 * [sendMessage] routes through the real [OutgoingMessage]/[MessageSender.send] pipeline (the
 * same one the main conversation screen uses), tagging the message with the topic's wire
 * `topic_uuid` (§6.2) and quoting the topic's anchor message (§6.1's graceful-degradation
 * mechanism -- a stock-Signal/non-topic-aware recipient still sees a normal reply-quote bubble).
 * Rendering goes through the same MessageRecord -> ConversationMessage pipeline the main
 * conversation screen uses (see [TopicConversationRepository]), so bubbles/reactions/quotes/
 * theming already match.
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
        val topic = SignalDatabase.topics.getTopic(topicId) ?: return@withContext
        val anchor = topic.anchorMessageId?.let { runCatching { SignalDatabase.messages.getMessageRecord(it) }.getOrNull() }
        val quote = anchor?.let { QuoteModel(it.dateSent, it.fromRecipient.id, it.body, false, null, null, QuoteModel.Type.NORMAL, it.messageRanges) }

        val outgoing = OutgoingMessage.text(threadRecipient, trimmed, threadRecipient.expiresInSeconds.seconds.inWholeMilliseconds)
          .copy(topicId = topic.topicUuid, outgoingQuote = quote)

        MessageSender.send(AppDependencies.application, outgoing, threadId, MessageSender.SendType.SIGNAL, null, null)
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

  fun deleteTopic(isFullDelete: Boolean) {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        SignalDatabase.messages.endTopic(threadId, topicId, isFullDelete)
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
