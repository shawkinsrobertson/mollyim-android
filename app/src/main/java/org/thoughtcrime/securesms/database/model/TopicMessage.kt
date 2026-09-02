package org.thoughtcrime.securesms.database.model

import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * A minimal, self-contained representation of a message inside a topic
 * thread -- see MessageTable.getTopicMessages(). Deliberately not a full
 * [MessageRecord]: the topic thread screen renders a simple list rather than
 * routing through the general-purpose conversation adapter/deserialization
 * pipeline. See docs/topic-threads-design.md.
 */
data class TopicMessage(
  val id: Long,
  val fromRecipientId: RecipientId,
  val body: String,
  val dateSent: Long
)
