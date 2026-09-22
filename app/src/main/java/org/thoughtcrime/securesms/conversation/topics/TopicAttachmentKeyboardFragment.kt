/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.topics

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.signal.core.models.media.Media
import org.signal.core.ui.logging.LoggingFragment
import org.signal.core.ui.permissions.Permissions
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.concurrent.addTo
import org.signal.core.util.permissions.PermissionCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.AttachmentKeyboard
import org.thoughtcrime.securesms.conversation.AttachmentKeyboardButton
import org.thoughtcrime.securesms.conversation.ManageContextMenu
import org.thoughtcrime.securesms.conversation.v2.keyboard.AttachmentKeyboardFragment
import org.thoughtcrime.securesms.conversation.v2.keyboard.AttachmentKeyboardViewModel

/**
 * [TopicThreadActivity]'s "+"-button attachment sheet.
 *
 * This is a clone of [AttachmentKeyboardFragment], not a reuse of it: that fragment's
 * `onViewCreated` does `ViewModelProvider(requireParentFragment())` for a `ConversationViewModel`
 * and calls `Permissions.with(requireParentFragment())` twice, both of which throw when the
 * fragment is added directly to an Activity's FragmentManager (as this one is, via
 * [org.thoughtcrime.securesms.components.InputAwareConstraintLayout.toggleInput]) rather than a
 * parent Fragment's -- there is no parent fragment at all in that case. Everything else
 * ([AttachmentKeyboard]/[R.layout.attachment_keyboard_fragment]/[AttachmentKeyboardViewModel]) is
 * reused unmodified. Wallpaper state is passed in once as a fragment argument rather than
 * observed reactively, since a topic thread's wallpaper doesn't change during the lifetime of
 * this short-lived sheet.
 */
class TopicAttachmentKeyboardFragment : LoggingFragment(R.layout.attachment_keyboard_fragment), AttachmentKeyboard.Callback {

  companion object {
    const val RESULT_KEY = AttachmentKeyboardFragment.RESULT_KEY
    const val MEDIA_RESULT = AttachmentKeyboardFragment.MEDIA_RESULT
    const val BUTTON_RESULT = AttachmentKeyboardFragment.BUTTON_RESULT
    private const val ARG_HAS_WALLPAPER = "has_wallpaper"

    fun create(hasWallpaper: Boolean): TopicAttachmentKeyboardFragment {
      return TopicAttachmentKeyboardFragment().apply {
        arguments = bundleOf(ARG_HAS_WALLPAPER to hasWallpaper)
      }
    }
  }

  private val viewModel: AttachmentKeyboardViewModel by viewModels()

  private lateinit var attachmentKeyboardView: AttachmentKeyboard

  private val lifecycleDisposable = LifecycleDisposable()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    lifecycleDisposable.bindTo(viewLifecycleOwner)

    attachmentKeyboardView = view.findViewById(R.id.attachment_keyboard)
    attachmentKeyboardView.apply {
      setCallback(this@TopicAttachmentKeyboardFragment)
      setWallpaperEnabled(requireArguments().getBoolean(ARG_HAS_WALLPAPER))
      // Contact/location/poll attachments aren't wired up for topic threads yet -- only offer
      // the two button types TopicThreadActivity actually knows how to send.
      filterAttachmentKeyboardButtons { it == AttachmentKeyboardButton.GALLERY || it == AttachmentKeyboardButton.FILE }
    }

    viewModel.getRecentMedia()
      .subscribeBy {
        attachmentKeyboardView.onMediaChanged(it)
      }
      .addTo(lifecycleDisposable)
  }

  override fun onAttachmentMediaClicked(media: Media) {
    setFragmentResult(RESULT_KEY, bundleOf(MEDIA_RESULT to media))
  }

  override fun onAttachmentSelectorClicked(button: AttachmentKeyboardButton) {
    setFragmentResult(RESULT_KEY, bundleOf(BUTTON_RESULT to button))
  }

  override fun onAttachmentPermissionsRequested() {
    Permissions.with(this)
      .request(*PermissionCompat.forImagesAndVideos())
      .ifNecessary()
      .onAnyResult { viewModel.refreshRecentMedia() }
      .withPermanentDenialDialog(getString(R.string.AttachmentManager_signal_requires_the_external_storage_permission_in_order_to_attach_photos_videos_or_audio), null, R.string.AttachmentManager_signal_allow_storage, R.string.AttachmentManager_signal_to_show_photos, true, parentFragmentManager)
      .onSomeDenied {
        val deniedPermissions = PermissionCompat.getRequiredPermissionsForDenial()
        if (it.containsAll(deniedPermissions.toList())) {
          Toast.makeText(requireContext(), R.string.AttachmentManager_signal_needs_storage_access, Toast.LENGTH_LONG).show()
        }
      }
      .execute()
  }

  override fun onDisplayMoreContextMenu(v: View, showAbove: Boolean, showAtStart: Boolean) {
    ManageContextMenu.show(
      context = requireContext(),
      anchorView = v,
      showAbove = showAbove,
      showAtStart = showAtStart,
      onSelectMore = { selectMorePhotos() },
      onSettings = { requireContext().startActivity(Permissions.getApplicationSettingsIntent(requireContext())) }
    )
  }

  private fun selectMorePhotos() {
    Permissions.with(this)
      .request(*PermissionCompat.forImagesAndVideos())
      .onAnyResult { viewModel.refreshRecentMedia() }
      .execute()
  }
}
