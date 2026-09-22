/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.topics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

/**
 * A headless [Fragment] with no purpose beyond satisfying
 * [org.thoughtcrime.securesms.conversation.v2.VoiceMessageRecordingDelegate]'s constructor, which
 * requires a real `Fragment` (it uses `fragment.viewLifecycleOwner` and `fragment.requireActivity()`
 * internally) rather than a generic `LifecycleOwner` -- [TopicThreadActivity] is an `Activity`, not
 * a `Fragment`, so this is added to its `supportFragmentManager` purely to bridge that gap without
 * editing the shared delegate class. `onCreateView` returns a bare `View` (not `null`) so that
 * `viewLifecycleOwner` is valid immediately, since the delegate reads it in its own constructor.
 */
class TopicVoiceRecordingHostFragment : Fragment() {
  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return View(requireContext())
  }
}
