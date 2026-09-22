/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.topics

import org.thoughtcrime.securesms.conversation.ConversationAdapter
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart
import org.thoughtcrime.securesms.conversation.ui.edit.EmptyConversationAdapterListener

/**
 * [ConversationAdapter.ItemClickListener] for the topic thread screen. Delegates every
 * interaction the topic thread doesn't yet handle to [EmptyConversationAdapterListener] (a real,
 * complete no-op implementation already used elsewhere in the app for this exact interface)
 * rather than hand-stubbing all ~54 methods here -- only the handful actually wired for this
 * screen are overridden below. See docs/topic-threads-design.md.
 *
 * Everything else -- long-press selection, voice-note playback, attachment taps, and the rarer
 * callbacks (group description, donate prompts, safety-number learn-more, etc.) -- is
 * deliberately still a no-op for this pass via the delegate. They render correctly (rendering
 * doesn't go through this listener), they just aren't interactive yet; tracked as follow-up
 * alongside the send/attachments slice.
 */
class TopicItemClickListener(
  private val onItemClicked: (MultiselectPart) -> Unit
) : ConversationAdapter.ItemClickListener by EmptyConversationAdapterListener {
  override fun onItemClick(item: MultiselectPart?) {
    item?.let(onItemClicked)
  }
}
