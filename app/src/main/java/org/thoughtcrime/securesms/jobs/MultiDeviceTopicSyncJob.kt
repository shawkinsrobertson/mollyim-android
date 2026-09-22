/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import okio.ByteString.Companion.toByteString
import org.signal.core.util.logging.Log
import org.signal.network.exceptions.PushNetworkException
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.jobmanager.impl.SealedSenderConstraint
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.messages.SignalServiceProtoUtil.pad
import org.thoughtcrime.securesms.recipients.Recipient
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.ConversationIdentifier
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.util.Optional
import kotlin.time.Duration.Companion.days

/**
 * Sends docs/topic-threads-design.md §6.3's `SyncMessage.TopicSync` to the user's own linked
 * devices whenever a topic is created, renamed, or deleted -- so the change reaches those
 * devices even when no `DataMessage.TopicContext` (Phase A/A2) would otherwise be sent to a peer
 * (e.g. renaming a topic in a chat the user isn't actively messaging in).
 *
 * Modeled on [MultiDeviceCallLinkSyncJob]'s shape (the job's own serialized data is just a plain
 * Wire-proto payload, encoded/decoded via its own generated `ADAPTER`) rather than
 * [MultiDeviceDeleteSyncJob]'s heavier, `.proto`-backed job-data type with chunking -- topic
 * lifecycle events are one-at-a-time, user-driven actions, never a bulk operation, so that
 * machinery isn't needed here.
 */
class MultiDeviceTopicSyncJob private constructor(
  parameters: Parameters,
  private val topicSync: SyncMessage.TopicSync
) : BaseJob(parameters) {

  companion object {
    const val KEY = "MultiDeviceTopicSyncJob"

    private val TAG = Log.tag(MultiDeviceTopicSyncJob::class.java)

    @JvmStatic
    fun enqueueCreate(threadRecipient: Recipient, topicUuid: String, name: String) {
      val conversation = threadRecipient.toTopicSyncConversationId() ?: return
      enqueue(SyncMessage.TopicSync(creates = listOf(SyncMessage.TopicSync.Create(conversation = conversation, topicId = topicUuid, name = name))))
    }

    @JvmStatic
    fun enqueueRename(threadRecipient: Recipient, topicUuid: String, name: String) {
      val conversation = threadRecipient.toTopicSyncConversationId() ?: return
      enqueue(SyncMessage.TopicSync(renames = listOf(SyncMessage.TopicSync.Rename(conversation = conversation, topicId = topicUuid, name = name))))
    }

    @JvmStatic
    fun enqueueDelete(threadRecipient: Recipient, topicUuid: String, isFullDelete: Boolean) {
      val conversation = threadRecipient.toTopicSyncConversationId() ?: return
      enqueue(SyncMessage.TopicSync(deletes = listOf(SyncMessage.TopicSync.Delete(conversation = conversation, topicId = topicUuid, isFullDelete = isFullDelete))))
    }

    private fun enqueue(topicSync: SyncMessage.TopicSync) {
      if (!SignalStore.account.isMultiDevice) {
        return
      }

      AppDependencies.jobManager.add(
        MultiDeviceTopicSyncJob(
          Parameters.Builder()
            .setQueue("__MULTI_DEVICE_TOPIC_SYNC_JOB__")
            .addConstraint(NetworkConstraint.KEY)
            .addConstraint(SealedSenderConstraint.KEY)
            .setLifespan(1.days.inWholeMilliseconds)
            .setMaxAttempts(Parameters.UNLIMITED)
            .build(),
          topicSync
        )
      )
    }

    /** Same mapping [MultiDeviceDeleteSyncJob] uses for `DeleteForMe`'s conversation identifier. */
    private fun Recipient.toTopicSyncConversationId(): ConversationIdentifier? {
      return when {
        isGroup -> ConversationIdentifier(threadGroupId = requireGroupId().decodedId.toByteString())
        hasAci -> ConversationIdentifier(threadServiceIdBinary = requireAci().toByteString())
        hasPni -> ConversationIdentifier(threadServiceIdBinary = requirePni().toByteString())
        hasE164 -> ConversationIdentifier(threadE164 = requireE164())
        else -> null
      }
    }
  }

  override fun serialize(): ByteArray = topicSync.encode()

  override fun getFactoryKey(): String = KEY

  override fun onFailure() = Unit

  override fun onRun() {
    val syncMessage = SyncMessage.Builder().pad().topicSync(topicSync).build()
    AppDependencies.signalServiceMessageSender.sendSyncMessage(Content(syncMessage = syncMessage), true, Optional.empty())
  }

  override fun onShouldRetry(exception: Exception): Boolean {
    return when (exception) {
      is PushNetworkException -> true
      else -> false
    }
  }

  class Factory : Job.Factory<MultiDeviceTopicSyncJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): MultiDeviceTopicSyncJob {
      val data = SyncMessage.TopicSync.ADAPTER.decode(serializedData!!)
      return MultiDeviceTopicSyncJob(parameters, data)
    }
  }
}
