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
 * `viewLifecycleOwner` is eventually valid, since the delegate reads it in its own constructor.
 *
 * [doOnReady] exists because "eventually" is load-bearing: a fragment added to an Activity's
 * `FragmentManager` from inside that Activity's own `onCreate()` cannot have its view created
 * synchronously within that same call, no matter how the transaction is committed -- the
 * FragmentManager can only advance a fragment through `onCreateView()` up to a state cap tied to
 * the host Activity's `Lifecycle.currentState`, which the framework doesn't bump to `CREATED`
 * until *after* `onCreate()` returns. Code that needs this fragment's view (or its
 * `viewLifecycleOwner`) must wait for [onViewCreated] to actually fire, not assume it already has.
 */
class TopicVoiceRecordingHostFragment : Fragment() {
  private var readyListener: ((TopicVoiceRecordingHostFragment) -> Unit)? = null

  /** Invoked from [onViewCreated], once this fragment's view genuinely exists. */
  fun doOnReady(listener: (TopicVoiceRecordingHostFragment) -> Unit) {
    readyListener = listener
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return View(requireContext())
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    readyListener?.invoke(this)
    readyListener = null
  }
}
