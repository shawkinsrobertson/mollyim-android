/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.topics

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.util.viewModel

/**
 * Full-screen view of a single topic thread (see docs/topic-threads-design.md
 * §3: "topic thread screen"). Reached from the conversation header's topics
 * icon/dropdown, the long-press "start topic" selection action, or an
 * in-timeline topic label/notice (once those are wired up to link here).
 *
 * Deliberately a standalone minimal screen rather than the full
 * ConversationFragment reused/filtered by topic -- see the Phase 1 UI PR
 * notes for why.
 */
class TopicThreadActivity : PassphraseRequiredActivity() {

  companion object {
    private const val EXTRA_THREAD_ID = "thread_id"
    private const val EXTRA_TOPIC_ID = "topic_id"

    @JvmStatic
    fun createIntent(context: Context, threadId: Long, topicId: Long): Intent {
      return Intent(context, TopicThreadActivity::class.java)
        .putExtra(EXTRA_THREAD_ID, threadId)
        .putExtra(EXTRA_TOPIC_ID, topicId)
    }
  }

  private val threadId: Long by lazy { intent.getLongExtra(EXTRA_THREAD_ID, -1L) }
  private val topicId: Long by lazy { intent.getLongExtra(EXTRA_TOPIC_ID, -1L) }

  private val viewModel by viewModel { TopicThreadViewModel(threadId, topicId) }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    super.onCreate(savedInstanceState, ready)

    if (threadId == -1L || topicId == -1L) {
      finish()
      return
    }

    setContent {
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()

      LaunchedEffect(uiState.isFinished) {
        if (uiState.isFinished) {
          finish()
        }
      }

      SignalTheme {
        TopicThreadScreen(
          uiState = uiState,
          onBackClick = ::finish,
          onSendMessage = viewModel::sendMessage,
          onRenameConfirmed = viewModel::renameTopic,
          onDeleteConfirmed = viewModel::deleteTopic
        )
      }
    }
  }
}
