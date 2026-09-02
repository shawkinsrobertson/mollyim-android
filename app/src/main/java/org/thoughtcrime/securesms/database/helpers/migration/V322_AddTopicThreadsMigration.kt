package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.SQLiteDatabase

/**
 * Adds the `topic` table and the `message.topic_id` column needed for topic
 * threads (sub-conversations inside a chat). See docs/topic-threads-design.md.
 *
 * Phase 1 (this migration): local data model only. Topics are not yet synced
 * to other devices or participants -- that's a later phase per the design doc.
 */
@Suppress("ClassName")
object V322_AddTopicThreadsMigration : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL(
      """
      CREATE TABLE topic (
        _id INTEGER PRIMARY KEY AUTOINCREMENT,
        topic_uuid TEXT NOT NULL UNIQUE,
        thread_id INTEGER NOT NULL REFERENCES thread (_id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        topic_order INTEGER NOT NULL DEFAULT 0,
        anchor_message_id INTEGER DEFAULT NULL REFERENCES message (_id) ON DELETE SET NULL,
        created_timestamp INTEGER NOT NULL,
        deleted_timestamp INTEGER DEFAULT NULL
      )
      """
    )

    db.execSQL("CREATE INDEX topic_thread_id_index ON topic (thread_id)")
    db.execSQL("CREATE UNIQUE INDEX topic_thread_active_index ON topic (thread_id, _id) WHERE deleted_timestamp IS NULL")

    // Which pre-existing messages (still living only in the parent timeline) were
    // selected to start a topic -- this is what lets the parent timeline render a
    // "part of topic X" label on them. Deliberately a separate join table rather
    // than another column on `message`: a message can only ever *own* (message.topic_id)
    // at most one topic, but can be a *source* of a topic's creation from any one topic,
    // and topic creation can pull in many source messages at once.
    db.execSQL(
      """
      CREATE TABLE topic_source_message (
        topic_id INTEGER NOT NULL REFERENCES topic (_id) ON DELETE CASCADE,
        message_id INTEGER NOT NULL REFERENCES message (_id) ON DELETE CASCADE,
        PRIMARY KEY (topic_id, message_id)
      )
      """
    )
    db.execSQL("CREATE INDEX topic_source_message_message_id_index ON topic_source_message (message_id)")

    db.execSQL("ALTER TABLE message ADD COLUMN topic_id INTEGER DEFAULT NULL REFERENCES topic (_id) ON DELETE SET NULL")
    db.execSQL("CREATE INDEX message_topic_id_index ON message (topic_id)")
    db.execSQL("CREATE INDEX message_topic_id_read_index ON message (topic_id, read) WHERE topic_id IS NOT NULL")
  }
}
