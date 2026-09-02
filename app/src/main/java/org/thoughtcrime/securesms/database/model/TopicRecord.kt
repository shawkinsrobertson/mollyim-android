package org.thoughtcrime.securesms.database.model

/**
 * Represents a topic thread: a named sub-conversation living inside a chat.
 * See docs/topic-threads-design.md.
 *
 * [topicUuid] is the stable, cross-device identity for this topic -- local
 * [id] is only meaningful within this database. [anchorMessageId] is the
 * "topic started" notice message (see MessageTable.insertTopicUpdateMessage)
 * that every message inside the topic is quoted back to; it's null only in
 * the brief window between creating the topic row and inserting that notice.
 */
data class TopicRecord(
  val id: Long,
  val topicUuid: String,
  val threadId: Long,
  val name: String,
  val order: Int,
  val anchorMessageId: Long?,
  val createdTimestamp: Long,
  val deletedTimestamp: Long?
) {
  val isDeleted: Boolean
    get() = deletedTimestamp != null
}
