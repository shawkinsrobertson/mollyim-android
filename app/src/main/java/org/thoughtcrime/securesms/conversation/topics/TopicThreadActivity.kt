/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.topics

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.recyclerview.SmoothScrollingLinearLayoutManager
import org.thoughtcrime.securesms.conversation.ConversationAdapter
import org.thoughtcrime.securesms.conversation.colors.ColorizerV1
import org.thoughtcrime.securesms.conversation.colors.RecyclerViewColorizer
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.viewModel
import org.thoughtcrime.securesms.wallpaper.ChatWallpaperDimLevelUtil
import java.util.Locale

/**
 * Full-screen view of a single topic thread. See docs/topic-threads-design.md §3 and the
 * "full parity" design discussion: this reuses [ConversationAdapter] (the same class that
 * renders bubbles/reactions/quotes/theming for the main conversation screen) rather than a
 * bespoke renderer, following the same pattern this app already uses for other filtered views
 * of a thread's messages (see ScheduledMessagesBottomSheet/PinnedMessagesBottomSheet).
 *
 * Isolation note: this reads/instantiates shared rendering classes (`ConversationAdapter`,
 * `ColorizerV1`, `RecyclerViewColorizer`, `ChatWallpaperDimLevelUtil`) but doesn't modify any of
 * them, and nothing outside `conversation.topics` references this screen's classes.
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

  private lateinit var toolbar: Toolbar
  private lateinit var recyclerView: RecyclerView
  private lateinit var adapter: ConversationAdapter
  private var firstRender = true

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    super.onCreate(savedInstanceState, ready)

    if (threadId == -1L || topicId == -1L) {
      finish()
      return
    }

    setContentView(R.layout.activity_topic_thread)

    toolbar = findViewById(R.id.topic_thread_toolbar)
    recyclerView = findViewById(R.id.topic_thread_recycler)
    val wallpaperView: ImageView = findViewById(R.id.topic_thread_wallpaper)
    val wallpaperDimView: View = findViewById(R.id.topic_thread_wallpaper_dim)
    val composeText: EditText = findViewById(R.id.topic_thread_compose_text)
    val sendButton: ImageButton = findViewById(R.id.topic_thread_send_button)

    toolbar.setNavigationOnClickListener { finish() }
    toolbar.inflateMenu(R.menu.topic_thread)
    toolbar.setOnMenuItemClickListener { item ->
      when (item.itemId) {
        R.id.menu_rename_topic -> {
          showRenameDialog()
          true
        }
        R.id.menu_delete_topic -> {
          showDeleteDialog()
          true
        }
        else -> false
      }
    }

    sendButton.setOnClickListener {
      val text = composeText.text?.toString().orEmpty()
      if (text.isNotBlank()) {
        viewModel.sendMessage(text)
        composeText.text?.clear()
      }
    }

    // Resolved independently of the ViewModel's own (IO-dispatched) recipient lookup, so this
    // UI-setup work never risks running the DB lookup on the main thread -- see the "full parity"
    // design discussion for why this isn't shared state with the ViewModel.
    //
    // Deliberately a plain coroutine rather than SimpleTask.run(lifecycle, ...): SimpleTask only
    // invokes its tasks once the Lifecycle is already at least CREATED, but that transition is
    // dispatched by the framework *after* onCreate() returns -- so calling it synchronously here
    // (while still inside onCreate()) silently no-ops both the background and foreground task,
    // and setUpConversationUi() (which is what wires up the message-rendering collector) never runs.
    lifecycleScope.launch {
      val threadRecipient = withContext(Dispatchers.IO) {
        SignalDatabase.threads.getRecipientForThreadId(threadId)!!
      }
      setUpConversationUi(threadRecipient, wallpaperView, wallpaperDimView)
    }
  }

  private fun setUpConversationUi(threadRecipient: Recipient, wallpaperView: ImageView, wallpaperDimView: View) {
    val chatWallpaper = threadRecipient.wallpaper
    if (chatWallpaper != null) {
      chatWallpaper.loadInto(wallpaperView)
      ChatWallpaperDimLevelUtil.applyDimLevelForNightMode(wallpaperDimView, chatWallpaper)
    }

    adapter = ConversationAdapter(
      this,
      this,
      Glide.with(this),
      Locale.getDefault(),
      TopicItemClickListener(onItemClicked = {}),
      threadRecipient.hasWallpaper,
      ColorizerV1()
    )

    recyclerView.layoutManager = SmoothScrollingLinearLayoutManager(this, true)
    recyclerView.adapter = adapter
    recyclerView.itemAnimator = null

    RecyclerViewColorizer(recyclerView).setChatColors(threadRecipient.chatColors)

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
          toolbar.title = state.topicName

          adapter.submitList(state.messages) {
            if (firstRender && state.messages.isNotEmpty()) {
              recyclerView.scrollToPosition(0)
              firstRender = false
            }
          }

          if (state.isFinished) {
            finish()
          }
        }
      }
    }
  }

  private fun showRenameDialog() {
    val input = EditText(this).apply {
      setText(viewModel.uiState.value.topicName)
      setSelection(text.length)
    }

    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.ConversationTopics__rename_topic)
      .setView(input)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val name = input.text.toString().trim()
        if (name.isNotEmpty()) {
          viewModel.renameTopic(name)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showDeleteDialog() {
    val topicName = viewModel.uiState.value.topicName
    MaterialAlertDialogBuilder(this)
      .setTitle(getString(R.string.ConversationTopics__delete_topic_question, topicName))
      .setMessage(R.string.ConversationTopics__delete_topic_body)
      .setNegativeButton(R.string.ConversationTopics__delete_for_me) { _, _ -> viewModel.deleteTopic(isFullDelete = false) }
      .setPositiveButton(R.string.ConversationTopics__delete_for_everyone) { _, _ -> viewModel.deleteTopic(isFullDelete = true) }
      .show()
  }
}
