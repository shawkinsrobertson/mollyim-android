package org.thoughtcrime.securesms.database

import android.content.Context
import android.database.Cursor
import androidx.core.content.contentValuesOf
import org.signal.core.util.SqlUtil
import org.signal.core.util.insertInto
import org.signal.core.util.logging.Log
import org.signal.core.util.readToList
import org.signal.core.util.readToMap
import org.signal.core.util.readToSingleInt
import org.signal.core.util.readToSingleObject
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.signal.core.util.requireLongOrNull
import org.signal.core.util.requireNonNullString
import org.signal.core.util.select
import org.signal.core.util.update
import org.signal.core.util.withinTransaction
import org.thoughtcrime.securesms.database.model.TopicRecord
import java.util.UUID

/**
 * Database table for topic threads: named sub-conversations that live inside
 * an existing chat (1:1 or group). See docs/topic-threads-design.md.
 *
 * Phase 1 scope: local data model only, single device -- topics are not yet
 * synced to other devices or participants. [TopicRecord.topicUuid] is
 * generated now (rather than added in a later migration) because it's the
 * stable identity a future phase will need to address a topic across the
 * wire and in backups.
 */
class TopicTable(context: Context?, databaseHelper: SignalDatabase?) : DatabaseTable(context, databaseHelper), ThreadIdDatabaseReference {

  companion object {
    private val TAG = Log.tag(TopicTable::class.java)

    const val TABLE_NAME = "topic"
    const val ID = "_id"
    const val TOPIC_UUID = "topic_uuid"
    const val THREAD_ID = "thread_id"
    const val NAME = "name"
    const val TOPIC_ORDER = "topic_order"
    const val ANCHOR_MESSAGE_ID = "anchor_message_id"
    const val CREATED_TIMESTAMP = "created_timestamp"
    const val DELETED_TIMESTAMP = "deleted_timestamp"

    /** Hard cap on active topics per chat. Client-side only -- see docs/topic-threads-design.md §9. */
    const val MAX_ACTIVE_TOPICS_PER_THREAD = 6

    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $TOPIC_UUID TEXT NOT NULL UNIQUE,
        $THREAD_ID INTEGER NOT NULL REFERENCES ${ThreadTable.TABLE_NAME} (${ThreadTable.ID}) ON DELETE CASCADE,
        $NAME TEXT NOT NULL,
        $TOPIC_ORDER INTEGER NOT NULL DEFAULT 0,
        $ANCHOR_MESSAGE_ID INTEGER DEFAULT NULL REFERENCES ${MessageTable.TABLE_NAME} (${MessageTable.ID}) ON DELETE SET NULL,
        $CREATED_TIMESTAMP INTEGER NOT NULL,
        $DELETED_TIMESTAMP INTEGER DEFAULT NULL
      )
    """

    @JvmField
    val CREATE_INDEXES = arrayOf(
      "CREATE INDEX topic_thread_id_index ON $TABLE_NAME ($THREAD_ID)",
      "CREATE UNIQUE INDEX topic_thread_active_index ON $TABLE_NAME ($THREAD_ID, $ID) WHERE $DELETED_TIMESTAMP IS NULL"
    )

    private const val ACTIVE_WHERE = "$DELETED_TIMESTAMP IS NULL"
  }

  /**
   * Join table recording which pre-existing messages (still living only in
   * the parent timeline) were selected to start a topic -- see
   * docs/topic-threads-design.md §3/§4.3. This is what lets the parent
   * timeline render a "part of topic X" label on them. A separate table
   * from `message.topic_id` because that column means "this row is a
   * topic-owned copy," a distinct relationship from "this original was one
   * of the messages a topic was started from."
   */
  object TopicSourceMessageTable {
    const val TABLE_NAME = "topic_source_message"
    const val TOPIC_ID = "topic_id"
    const val MESSAGE_ID = "message_id"

    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $TOPIC_ID INTEGER NOT NULL REFERENCES ${TopicTable.TABLE_NAME} (${TopicTable.ID}) ON DELETE CASCADE,
        $MESSAGE_ID INTEGER NOT NULL REFERENCES ${MessageTable.TABLE_NAME} (${MessageTable.ID}) ON DELETE CASCADE,
        PRIMARY KEY ($TOPIC_ID, $MESSAGE_ID)
      )
    """

    @JvmField
    val CREATE_INDEXES = arrayOf(
      "CREATE INDEX topic_source_message_message_id_index ON $TABLE_NAME ($MESSAGE_ID)"
    )
  }

  /**
   * Thrown by [createTopic] when the chat already has [MAX_ACTIVE_TOPICS_PER_THREAD] active topics.
   */
  class TooManyTopicsException : Exception()

  override fun remapThread(fromId: Long, toId: Long) {
    val count = writableDatabase
      .update(TABLE_NAME)
      .values(THREAD_ID to toId)
      .where("$THREAD_ID = ?", fromId)
      .run()
    Log.d(TAG, "Remapped thread $fromId to $toId for $count topic(s).")
  }

  /**
   * Creates a new topic in [threadId]. Throws [TooManyTopicsException] if the
   * chat already has [MAX_ACTIVE_TOPICS_PER_THREAD] active topics.
   *
   * The returned record's [TopicRecord.anchorMessageId] is null -- the caller
   * is expected to insert the "topic started" notice message next (see
   * MessageTable.insertTopicUpdateMessage, which needs the topic's id/uuid to
   * exist first) and then call [setAnchorMessage].
   */
  fun createTopic(threadId: Long, name: String, createdTimestamp: Long = System.currentTimeMillis()): TopicRecord {
    return writableDatabase.withinTransaction { db ->
      val activeCount = db
        .select("COUNT(*)")
        .from(TABLE_NAME)
        .where("$THREAD_ID = ? AND $ACTIVE_WHERE", threadId)
        .run()
        .readToSingleInt()

      if (activeCount >= MAX_ACTIVE_TOPICS_PER_THREAD) {
        throw TooManyTopicsException()
      }

      val topicUuid = UUID.randomUUID().toString()
      val id = db.insertInto(TABLE_NAME)
        .values(
          TOPIC_UUID to topicUuid,
          THREAD_ID to threadId,
          NAME to name,
          TOPIC_ORDER to activeCount,
          CREATED_TIMESTAMP to createdTimestamp
        )
        .run()

      TopicRecord(
        id = id,
        topicUuid = topicUuid,
        threadId = threadId,
        name = name,
        order = activeCount,
        anchorMessageId = null,
        createdTimestamp = createdTimestamp,
        deletedTimestamp = null
      )
    }
  }

  /** Sets a topic's anchor message once it's been inserted. See [createTopic]. */
  fun setAnchorMessage(topicId: Long, anchorMessageId: Long) {
    writableDatabase
      .update(TABLE_NAME)
      .values(ANCHOR_MESSAGE_ID to anchorMessageId)
      .where("$ID = ?", topicId)
      .run()
  }

  /** Renames an active topic. No-ops if the topic doesn't exist or is already deleted. */
  fun renameTopic(topicId: Long, newName: String): Boolean {
    val count = writableDatabase
      .update(TABLE_NAME)
      .values(NAME to newName)
      .where("$ID = ? AND $ACTIVE_WHERE", topicId)
      .run()
    return count > 0
  }

  /**
   * Deletes a topic. This is a tombstone (sets [DELETED_TIMESTAMP]), not a row
   * delete -- see docs/topic-threads-design.md §4.2/§7 for why: the topic
   * needs to stop showing up anywhere active (dropdown, tab badge, etc.)
   * without disturbing the parent-timeline messages that still reference it
   * by label, and without complicating a later multi-device tombstone sync.
   *
   * Callers are responsible for removing/handling the topic's owned message
   * copies per the delete-for-me vs delete-for-everyone distinction in the
   * design doc -- this call only retires the topic itself.
   */
  fun deleteTopic(topicId: Long, deletedTimestamp: Long = System.currentTimeMillis()): Boolean {
    val count = writableDatabase
      .update(TABLE_NAME)
      .values(DELETED_TIMESTAMP to deletedTimestamp)
      .where("$ID = ? AND $ACTIVE_WHERE", topicId)
      .run()
    return count > 0
  }

  fun getTopic(topicId: Long): TopicRecord? {
    return readableDatabase
      .select()
      .from(TABLE_NAME)
      .where("$ID = ?", topicId)
      .run()
      .readToSingleObject { it.toTopicRecord() }
  }

  fun getTopicByUuid(topicUuid: String): TopicRecord? {
    return readableDatabase
      .select()
      .from(TABLE_NAME)
      .where("$TOPIC_UUID = ?", topicUuid)
      .run()
      .readToSingleObject { it.toTopicRecord() }
  }

  /** Active (non-deleted) topics for a chat, in display order. Up to [MAX_ACTIVE_TOPICS_PER_THREAD]. */
  fun getActiveTopicsForThread(threadId: Long): List<TopicRecord> {
    return readableDatabase
      .select()
      .from(TABLE_NAME)
      .where("$THREAD_ID = ? AND $ACTIVE_WHERE", threadId)
      .orderBy("$TOPIC_ORDER ASC")
      .run()
      .readToList { it.toTopicRecord() }
  }

  fun getActiveTopicCountForThread(threadId: Long): Int {
    return readableDatabase
      .select("COUNT(*)")
      .from(TABLE_NAME)
      .where("$THREAD_ID = ? AND $ACTIVE_WHERE", threadId)
      .run()
      .readToSingleInt()
  }

  /**
   * Records that [sourceMessageIds] were selected to start [topicId], so the
   * parent timeline can render a "part of topic X" label on them. See
   * [TopicSourceMessageTable].
   */
  fun recordSourceMessages(topicId: Long, sourceMessageIds: Collection<Long>) {
    if (sourceMessageIds.isEmpty()) return

    writableDatabase.withinTransaction { db ->
      SqlUtil.buildBulkInsert(
        TopicSourceMessageTable.TABLE_NAME,
        arrayOf(TopicSourceMessageTable.TOPIC_ID, TopicSourceMessageTable.MESSAGE_ID),
        sourceMessageIds.map { messageId -> contentValuesOf(TopicSourceMessageTable.TOPIC_ID to topicId, TopicSourceMessageTable.MESSAGE_ID to messageId) }
      ).forEach {
        db.execSQL(it.where, it.whereArgs)
      }
    }
  }

  /** The active topic (if any) a given parent-timeline message was used to start, for label rendering. */
  fun getActiveTopicForSourceMessage(messageId: Long): TopicRecord? {
    return readableDatabase
      .select("$TABLE_NAME.*")
      .from("${TopicSourceMessageTable.TABLE_NAME} JOIN $TABLE_NAME ON ${TopicSourceMessageTable.TABLE_NAME}.${TopicSourceMessageTable.TOPIC_ID} = $TABLE_NAME.$ID")
      .where("${TopicSourceMessageTable.TABLE_NAME}.${TopicSourceMessageTable.MESSAGE_ID} = ? AND $ACTIVE_WHERE", messageId)
      .run()
      .readToSingleObject { it.toTopicRecord() }
  }

  /**
   * Per docs/topic-threads-design.md §3.1/§4.3: unread-message counts for
   * every active topic in a chat, derived from `message.read` rather than a
   * separate column. Used for the per-topic dropdown dots.
   */
  fun getUnreadCountsByTopic(threadId: Long): Map<Long, Int> {
    val qualifiedTopicId = "${MessageTable.TABLE_NAME}.${MessageTable.TOPIC_ID}"
    return readableDatabase
      .select("$qualifiedTopicId, COUNT(*) AS unread_count")
      .from("${MessageTable.TABLE_NAME} JOIN $TABLE_NAME ON $qualifiedTopicId = $TABLE_NAME.$ID")
      .where("$TABLE_NAME.$THREAD_ID = ? AND $ACTIVE_WHERE AND ${MessageTable.TABLE_NAME}.${MessageTable.READ} = 0", threadId)
      .groupBy(qualifiedTopicId)
      .run()
      .readToMap { cursor -> cursor.requireLong(MessageTable.TOPIC_ID) to cursor.requireInt("unread_count") }
  }

  private fun Cursor.toTopicRecord(): TopicRecord {
    return TopicRecord(
      id = requireLong(ID),
      topicUuid = requireNonNullString(TOPIC_UUID),
      threadId = requireLong(THREAD_ID),
      name = requireNonNullString(NAME),
      order = requireInt(TOPIC_ORDER),
      anchorMessageId = requireLongOrNull(ANCHOR_MESSAGE_ID),
      createdTimestamp = requireLong(CREATED_TIMESTAMP),
      deletedTimestamp = requireLongOrNull(DELETED_TIMESTAMP)
    )
  }
}
