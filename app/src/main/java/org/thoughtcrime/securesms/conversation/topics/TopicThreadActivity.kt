/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.topics

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.models.media.Media
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.AnimatingToggle
import org.thoughtcrime.securesms.components.ComposeText
import org.thoughtcrime.securesms.components.InputAwareConstraintLayout
import org.thoughtcrime.securesms.components.InputPanel
import org.thoughtcrime.securesms.components.SendButton
import org.thoughtcrime.securesms.components.recyclerview.SmoothScrollingLinearLayoutManager
import org.thoughtcrime.securesms.conversation.AttachmentKeyboardButton
import org.thoughtcrime.securesms.conversation.ConversationAdapter
import org.thoughtcrime.securesms.conversation.MessageSendType
import org.thoughtcrime.securesms.conversation.colors.ColorizerV1
import org.thoughtcrime.securesms.conversation.colors.RecyclerViewColorizer
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.StickerRecord
import org.thoughtcrime.securesms.mediasend.MediaSendActivityResult
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionActivity
import org.thoughtcrime.securesms.mms.DocumentSlide
import org.thoughtcrime.securesms.mms.GifSlide
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.mms.SlideFactory
import org.thoughtcrime.securesms.mms.VideoSlide
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.MediaUtil
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
 * The compose bar (Phase B1) is the real [InputPanel]/[org.thoughtcrime.securesms.components.ComposeText]
 * used by the main conversation screen, rather than a bespoke `EditText`+`ImageButton` row.
 * Attachments (Phase B2) reuse the real [org.thoughtcrime.securesms.conversation.AttachmentKeyboard]
 * via [TopicAttachmentKeyboardFragment], shown in the `R.id.input_container` fragment slot that
 * [org.thoughtcrime.securesms.components.InputAwareConstraintLayout.toggleInput] always targets.
 * Only Gallery and File attachments are wired up so far -- Contact/Location/Poll are filtered out
 * of the sheet rather than left as dead buttons (see [TopicAttachmentKeyboardFragment]). Voice
 * notes are Phase B3, not yet implemented.
 *
 * Isolation note: this reads/instantiates shared rendering and input classes (`ConversationAdapter`,
 * `ColorizerV1`, `RecyclerViewColorizer`, `ChatWallpaperDimLevelUtil`, `InputPanel`,
 * `MediaSelectionActivity`) but doesn't modify any of them, and nothing outside `conversation.topics`
 * references this screen's classes.
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
  private lateinit var inputPanel: InputPanel
  private lateinit var composeText: ComposeText
  private lateinit var sendButton: SendButton
  private lateinit var buttonToggle: AnimatingToggle
  private lateinit var root: InputAwareConstraintLayout
  private var firstRender = true
  private var hasWallpaper = false

  private val mediaSelectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      result.data?.let { handleMediaSendResult(MediaSendActivityResult.fromData(it)) }
    }
  }

  private val fileSelectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      result.data?.data?.let { handleFileSelected(it) }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    super.onCreate(savedInstanceState, ready)

    if (threadId == -1L || topicId == -1L) {
      finish()
      return
    }

    setContentView(R.layout.activity_topic_thread)

    root = findViewById(R.id.topic_thread_root)
    root.fragmentManager = supportFragmentManager

    toolbar = findViewById(R.id.topic_thread_toolbar)
    recyclerView = findViewById(R.id.topic_thread_recycler)
    val wallpaperView: ImageView = findViewById(R.id.topic_thread_wallpaper)
    val wallpaperDimView: View = findViewById(R.id.topic_thread_wallpaper_dim)

    inputPanel = findViewById(R.id.topic_input_panel)
    composeText = inputPanel.findViewById(R.id.embedded_text_editor)
    sendButton = inputPanel.findViewById(R.id.send_button)
    buttonToggle = inputPanel.findViewById(R.id.button_toggle)

    // InputPanel wires emoji/quick-camera clicks and voice-note-draft callbacks straight to
    // its Listener, so setListener must be called even though none of those apply yet for a
    // text-only (Phase B1) compose bar -- otherwise those clicks NPE.
    inputPanel.setListener(NoOpInputPanelListener())

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

    composeText.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
      override fun afterTextChanged(s: Editable?) {
        if (s.isNullOrBlank()) {
          buttonToggle.displayQuick(inputPanel.findViewById(R.id.attach_button))
        } else {
          buttonToggle.displayQuick(sendButton)
        }
      }
    })

    sendButton.setOnClickListener {
      val text = composeText.textTrimmed.toString()
      if (text.isNotBlank()) {
        viewModel.sendMessage(text)
        composeText.setText("")
      }
    }

    inputPanel.findViewById<View>(R.id.attach_button).setOnClickListener {
      root.toggleInput(TopicAttachmentFragmentCreator(), composeText)
    }

    supportFragmentManager.setFragmentResultListener(TopicAttachmentKeyboardFragment.RESULT_KEY, this) { _, bundle ->
      onAttachmentKeyboardResult(bundle)
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
    hasWallpaper = threadRecipient.hasWallpaper

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

  @Suppress("DEPRECATION")
  private fun onAttachmentKeyboardResult(bundle: Bundle) {
    val button = bundle.getSerializable(TopicAttachmentKeyboardFragment.BUTTON_RESULT) as? AttachmentKeyboardButton
    val media = bundle.getParcelable<Media>(TopicAttachmentKeyboardFragment.MEDIA_RESULT)

    if (button != null) {
      when (button) {
        AttachmentKeyboardButton.GALLERY -> launchGallery()
        AttachmentKeyboardButton.FILE -> launchFilePicker()
        else -> Unit // Filtered out of the attachment sheet -- see TopicAttachmentKeyboardFragment.
      }
    } else if (media != null) {
      launchMediaEditor(media)
    }

    root.hideInput()
  }

  private fun launchGallery() {
    val recipient = viewModel.threadRecipient
    val intent = MediaSelectionActivity.gallery(this, MessageSendType.SignalMessageSendType, emptyList(), recipient.id, composeText.textTrimmed, false)
    mediaSelectionLauncher.launch(intent)
  }

  private fun launchMediaEditor(media: Media) {
    val recipient = viewModel.threadRecipient
    val intent = MediaSelectionActivity.editor(this, MessageSendType.SignalMessageSendType, listOf(media), recipient.id, composeText.textTrimmed)
    mediaSelectionLauncher.launch(intent)
  }

  private fun launchFilePicker() {
    try {
      fileSelectionLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*"))
      return
    } catch (e: ActivityNotFoundException) {
      // Fall through to ACTION_GET_CONTENT below.
    }

    try {
      fileSelectionLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).setType("*/*"))
    } catch (e: ActivityNotFoundException) {
      Toast.makeText(this, R.string.AttachmentManager_cant_open_media_selection, Toast.LENGTH_LONG).show()
    }
  }

  private fun handleFileSelected(uri: Uri) {
    val slide = SlideFactory.getSlide(this, contentResolver.getType(uri), uri, 0, 0, null) ?: return
    viewModel.sendMessage(composeText.textTrimmed.toString(), slideDeck = SlideDeck().apply { addSlide(slide) })
    composeText.setText("")
  }

  private fun handleMediaSendResult(result: MediaSendActivityResult) {
    if (result.recipientId != viewModel.threadRecipient.id) {
      Toast.makeText(this, R.string.ConversationActivity_error_sending_media, Toast.LENGTH_LONG).show()
      return
    }

    if (result.isPushPreUpload) {
      viewModel.sendMessage(result.body, preUploadResults = result.preUploadResults)
    } else {
      val slides: List<Slide> = result.nonUploadedMedia.mapNotNull {
        when {
          MediaUtil.isVideoType(it.contentType) -> VideoSlide(this, it.uri, it.size, it.isVideoGif, it.width, it.height, it.caption, it.transformProperties)
          MediaUtil.isGif(it.contentType) -> GifSlide(this, it.uri, it.size, it.width, it.height, it.isBorderless, it.caption)
          MediaUtil.isImageType(it.contentType) -> ImageSlide(this, it.uri, it.contentType, it.size, it.width, it.height, it.isBorderless, it.caption, null, it.transformProperties)
          MediaUtil.isDocumentType(it.contentType) -> DocumentSlide(this, it.uri, it.contentType!!, it.size, it.fileName)
          else -> null
        }
      }
      viewModel.sendMessage(result.body, slideDeck = SlideDeck().apply { slides.forEach { addSlide(it) } })
    }

    composeText.setText("")
  }

  private inner class TopicAttachmentFragmentCreator : InputAwareConstraintLayout.FragmentCreator {
    override val id: Int = 1
    override fun create(): Fragment = TopicAttachmentKeyboardFragment.create(hasWallpaper)
  }

  private inner class NoOpInputPanelListener : InputPanel.Listener {
    override fun onRecorderStarted() = Unit
    override fun onRecorderLocked() = Unit
    override fun onRecorderSaveDraft() = Unit
    override fun onRecorderFinished() = Unit
    override fun onRecorderCanceled(byUser: Boolean) = Unit
    override fun onRecorderPermissionRequired() = Unit
    override fun onRecorderAlreadyInUse() = Unit
    override fun onEmojiToggle() = Unit
    override fun onLinkPreviewCanceled() = Unit
    override fun onStickerSuggestionSelected(sticker: StickerRecord) = Unit
    override fun onQuoteChanged(id: Long, author: RecipientId) = Unit
    override fun onQuoteCleared() = Unit
    override fun onQuoteClicked(quoteId: Long, authorId: RecipientId) = Unit
    override fun onEnterEditMode() = Unit
    override fun onExitEditMode() = Unit
    override fun onQuickCameraToggleClicked() = Unit
    override fun onVoiceNoteDraftPlay(audioUri: Uri, progress: Double) = Unit
    override fun onVoiceNoteDraftPause(audioUri: Uri) = Unit
    override fun onVoiceNoteDraftSeekTo(audioUri: Uri, progress: Double) = Unit
    override fun onVoiceNoteDraftDelete(audioUri: Uri) = Unit
  }
}
