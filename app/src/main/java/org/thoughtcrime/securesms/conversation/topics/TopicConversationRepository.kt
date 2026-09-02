/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.topics

import android.content.Context
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.v2.data.MessageDataFetcher
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Loads a topic's messages through the exact same MessageRecord -> [MessageDataFetcher] ->
 * [ConversationMessage] pipeline the main conversation screen uses (see
 * [org.thoughtcrime.securesms.conversation.v2.data.ConversationDataSource] for the main-thread
 * equivalent this mirrors), so topic threads get the same bubble/attachment/reaction/quote
 * rendering as any other conversation.
 *
 * Isolation note (see docs/topic-threads-design.md and the "full parity" PR discussion): this
 * class *reads* shared model/data classes (`MessageDataFetcher`, `ConversationMessage`) but does
 * not modify any of them, and nothing outside `conversation.topics` references this class.
 */
class TopicConversationRepository(private val context: Context) {

  /**
   * All of [topicId]'s messages as [ConversationMessage]s, newest first -- matching
   * [MessageTable.getConversation]'s ordering, for the same reverseLayout-LinearLayoutManager
   * trick the main conversation screen uses.
   */
  fun loadMessages(topicId: Long, threadRecipient: Recipient): List<ConversationMessage> {
    val records: MutableList<MessageRecord> = ArrayList()

    MessageTable.mmsReaderFor(SignalDatabase.messages.getTopicConversation(topicId)).use { reader ->
      reader.forEach { record -> records.add(record) }
    }

    val extraData = MessageDataFetcher.fetch(records, threadRecipient)
    val updatedRecords = MessageDataFetcher.updateModelsWithData(records, extraData)

    return updatedRecords.map { record ->
      ConversationMessage.ConversationMessageFactory.createWithUnresolvedData(
        context,
        record,
        record.getDisplayBody(context),
        extraData.mentionsById[record.id],
        extraData.hasBeenQuoted.contains(record.id),
        threadRecipient,
        extraData.memberLabels
      )
    }
  }
}
