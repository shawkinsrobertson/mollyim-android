# Topic Threads — Design Doc

Status: **draft / pre-implementation**
Target: Molly (this repo), a close fork of Signal-Android
Author: written with Claude Code from a design conversation on 2026-09-02

## 1. Summary

Add the ability to spin up a "topic thread" inside an existing conversation
(1:1 or group): a labeled sub-conversation that lives inside the parent chat,
can be started from scratch or from a set of already-sent messages, carries
over the parent chat's theme/appearance and full messaging feature set, and
can be renamed or deleted from within itself. A chat can have up to **6**
topics at once.

This is being built as a **full cross-client protocol feature**: topic
creation, renaming, deletion, and messages sent inside a topic are meant to
sync to every device — the user's own linked devices, and (as best effort)
whatever the other participant(s) are running. Molly talks to the same
servers and the same Signal Android/iOS/Desktop clients as stock Signal, so
this doc treats "the other side is stock Signal, not Molly" as the normal
case to design for, not an edge case.

**Important scoping note:** this repo can only implement the Molly/Android
side. The Signal wire protocol (`SignalService.proto`) is shared with
iOS/Desktop/Server, but proto3 is forward-compatible — clients that don't
recognize a field simply ignore it. So the plan below adds new *optional*
fields that degrade gracefully rather than requiring any server or other-client
change. Nothing here can be tested against real iOS/Desktop clients from
this repo; it can only be verified Molly-to-Molly and reasoned about for the
stock-Signal-recipient case from the proto's own compatibility guarantees.

## 2. Terminology

| Term | Meaning |
|---|---|
| Parent chat | The existing 1:1 or group conversation a topic lives inside. |
| Topic | A named sub-thread inside a parent chat. Max 6 per chat. |
| Anchor message | The real, sent "topic started" notice message that every message inside the topic quotes back to (see §5). |
| Topic-owned message | A message row that belongs to a topic (see §4.2 for the duplication model). |
| Molly-aware client | Any client version (Molly, and in principle any client) that understands the new proto fields in §6. |

## 3. UX Spec (as agreed)

- **Entry points:**
  - A new icon in the conversation header, to the left of the video/audio call icons. Tapping it with zero topics: starts topic creation. With ≥1 topic: opens a dropdown listing existing topics (max 6) plus a "New topic" action.
  - Long-press → select one or more messages → the selection bottom-bar gets a "Start topic" action. The selected messages become the topic's initial content.
- **Naming:** creating (and renaming) a topic prompts for a name via a simple text input, similar to existing group-name-edit flows.
- **Inline notice:** starting a topic drops an inline system-style notice into the parent chat's timeline (same visual treatment as call events / date headers), containing the topic name as a tappable link that navigates into the topic thread. Renaming and deleting a topic also leave inline notices.
- **Switching topics:** the header icon's dropdown lets the user jump directly between the parent chat and any of its topics, and create a new one (up to the 6 cap, at which point "New topic" is disabled/hidden).
- **Topic thread screen:** tapping a topic (via label, notice, or dropdown) pushes a new full-screen conversation view — its own header (topic name + back arrow to the parent), reusing the existing conversation UI/adapter filtered to that topic.
- **Renaming / deleting:** available from inside the topic thread itself (e.g. via its header's overflow menu). Deleting prompts with the same **Delete for me / Delete for everyone** two-button flow already used for individual messages (§7 covers exactly what each option does).
- **Feature parity:** all standard messaging capabilities (attachments, reactions, replies, edits, polls, etc.) work inside a topic thread exactly as in a normal chat. Chat theme/wallpaper/color follow the parent chat automatically since a topic is not a separate `Recipient` (see §4.1) — no extra plumbing needed there.
- **Per-message deletion:** individual messages inside a topic support the existing **Delete for me / Delete for everyone**, unchanged.

## 4. Local Data Model

### 4.1 Why not reuse `ThreadTable` directly

`ThreadTable.RECIPIENT_ID` is `UNIQUE` — the schema hard-codes "one thread per
recipient." A topic is not a recipient, so topics need their own table, and
`MessageTable` needs a way to tag which topic (if any) a message row belongs
to. This mirrors the existing story model, where `MessageTable` already has
`STORY_TYPE` / `PARENT_STORY_ID` to express a message's relationship to
something other than a flat thread — same shape of problem, new table
instead of reusing those columns (stories are a distinct enough concept that
overloading them would be confusing).

### 4.2 New `topic` table

```sql
CREATE TABLE topic (
  _id INTEGER PRIMARY KEY AUTOINCREMENT,
  topic_uuid TEXT NOT NULL UNIQUE,        -- stable cross-device id (see §6)
  thread_id INTEGER NOT NULL REFERENCES thread (_id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  color_index INTEGER,                    -- ordinal among the chat's topics, for UI ordering
  anchor_message_id INTEGER REFERENCES message (_id) ON DELETE SET NULL,
  created_timestamp INTEGER NOT NULL,
  deleted_timestamp INTEGER               -- tombstone; NULL while active
);
CREATE UNIQUE INDEX topic_thread_active_index
  ON topic (thread_id, _id) WHERE deleted_timestamp IS NULL;
```

- `topic_uuid` is what's referenced across the wire and in backups — local
  autoincrement `_id`s are per-database and must never leak into proto
  messages (same reasoning `AddressableMessage`/`ConversationIdentifier`
  already encode: address by stable identity, not local row id).
- The 6-per-chat cap is enforced at write time: `SELECT COUNT(*) FROM topic
  WHERE thread_id = ? AND deleted_timestamp IS NULL` before insert. This is
  a **client-side-only** limit (see §9 — nothing stops a modified peer from
  sending a 7th).
- Deletion is a tombstone (`deleted_timestamp`), not a row delete, so that
  "Delete for me" can locally hide a topic without disturbing sync bookkeeping,
  while "Delete for everyone" propagates the tombstone (§6.3) and the topic
  disappears from the switcher/dropdown everywhere. A tombstoned topic's
  owned messages are handled per §7.

### 4.3 `message` table additions

```sql
ALTER TABLE message ADD COLUMN topic_id INTEGER REFERENCES topic (_id) ON DELETE SET NULL;
CREATE INDEX message_topic_id_index ON message (topic_id);
```

Per the "duplicate rows" decision:

- **Messages selected to start a topic** (long-press flow) are **copied**:
  new `message` rows are inserted with `thread_id` unchanged but `topic_id`
  set to the new topic, `topic_id` left `NULL` on the originals which
  instead get a label (rendered from a `MessageExtras.topicUpdate` payload,
  see §5) pointing at the topic.
- **New messages composed while inside a topic thread** are ordinary sends
  with `topic_id` set from the start — there is no "original" row in the
  parent timeline for these; the parent timeline only ever shows the
  anchor's inline notice.
- The parent chat's `ThreadTable` snippet/unread-count logic is **not**
  changed by topic-owned messages with no parent-timeline row — a topic is
  additive read/unread state layered on the same underlying thread (see
  §9 for the still-open question of whether topic activity should bump the
  parent chat's unread badge).

## 5. Local system notices — `MessageExtras`

Rather than spend more of the tight 5-bit `BASE_TYPE_MASK` in
`MessageTypes.java` (only values `19`, `29`, `30`, `31` remain free), follow
the pattern already used for GV2 updates, admin-delete status, and pinned
messages: **one** new base type plus a structured payload in the existing
`MessageExtras` proto (`app/src/main/protowire/Database.proto`), which is a
local-only, per-message metadata blob already designed for exactly this
kind of extensibility.

```proto
// MessageTypes.java
long TOPIC_UPDATE_TYPE = 19;

// Database.proto
message MessageExtras {
    oneof extra {
        // ...existing entries...
        TopicUpdate topicUpdate = 8;
    }
}

message TopicUpdate {
    enum Kind {
        STARTED = 0;
        RENAMED = 1;
        DELETED = 2;
    }
    Kind kind = 1;
    string topicUuid = 2;
    string name = 3;          // new name for STARTED/RENAMED
    string previousName = 4;  // set for RENAMED
}
```

This message row (type `TOPIC_UPDATE_TYPE`, extras `TopicUpdate{STARTED,
...}`) is what renders as the inline "topic started" notice, and — per §6 —
is also the one actually **sent** to the other participant(s) as a normal
text message, making it the topic's **anchor message**.

## 6. Wire Protocol (`SignalService.proto`)

Current next-free field numbers (checked directly against this repo):
`DataMessage` → **30**, `SyncMessage.content` oneof → **27**,
`ChatItem.item` oneof (backup) → **23**. Numbers below assume no other
feature lands first.

### 6.1 `DataMessage.TopicContext` (new field 30)

```proto
message TopicContext {
  enum Action {
    CREATE = 0;
    RENAME = 1;
    DELETE = 2;
  }
  optional string topicId = 1;         // topic_uuid
  optional Action action = 2;
  optional string name = 3;            // for CREATE/RENAME
  repeated AddressableMessage sourceMessages = 4; // messages copied in at CREATE time
}

// on DataMessage:
optional TopicContext topicContext = 30;
```

- Sent **once** on the anchor message itself (body = the human-readable
  fallback text, e.g. `"📌 Sarah started a topic: Weekend Trip Planning"`),
  with `action = CREATE`, `name` set, and `sourceMessages` listing the
  messages selected at creation time (addressed the same way
  `AddressableMessage` already addresses messages elsewhere in this proto:
  author + `sentTimestamp`, not local row ids — a Molly peer resolves those
  back to its own local rows the same way quote/reaction targets are
  resolved today).
- `RENAME` and `DELETE` are sent as their own small text messages (fallback
  bodies: `"📌 Topic renamed: Weekend Trip Planning → Trip Logistics"`,
  `"📌 Topic deleted: Trip Logistics"`), each carrying `topicContext` with
  the corresponding `action`.
- **Every ordinary message sent while inside a topic thread also sets the
  standard `quote` field (already field 8) pointing at the anchor message**,
  in addition to a lightweight `topicContext`-less marker
  (see 6.2) carrying just the `topicId`. This is the graceful-degradation
  mechanism discussed in the design conversation: a Molly-aware recipient
  groups by `topicId`; a stock-Signal recipient still gets a normal,
  native "replying to: 📌 Sarah started a topic…" preview instead of an
  unexplained flat message. Anchoring every reply to the fixed anchor
  (rather than chaining to the previous message) mirrors how Slack/Discord
  present "N replies" off one parent and avoids click-through chains on
  clients with no topic UI at all.

### 6.2 Per-message topic tag (new field 31, `DataMessage.topicId`)

```proto
optional string topicId = 31; // set on every message sent inside a topic thread
```

A plain string rather than a submessage, kept separate from
`topicContext` (which is only present on the three lifecycle notices) so
that a normal chat message doesn't need to build a `TopicContext` just to
carry a topic id.

### 6.3 Multi-device sync — `SyncMessage.TopicSync` (new field 27)

Topic lifecycle events need to reach the user's *other* devices even when
no DataMessage would otherwise be sent to a peer (e.g. renaming a topic in
a chat where you're not actively messaging). This follows the exact shape
`DeleteForMe` already uses (`ConversationIdentifier` + `AddressableMessage`,
§6.3 of `SignalService.proto` as it stands today):

```proto
message TopicSync {
  message Create {
    optional ConversationIdentifier conversation = 1;
    optional string topicId = 2;
    optional string name = 3;
    repeated AddressableMessage sourceMessages = 4;
    optional AddressableMessage anchorMessage = 5;
  }
  message Rename {
    optional ConversationIdentifier conversation = 1;
    optional string topicId = 2;
    optional string name = 3;
  }
  message Delete {
    optional ConversationIdentifier conversation = 1;
    optional string topicId = 2;
    optional bool isFullDelete = 3; // false = "delete for me" (this device only, but device-sync still applies to *this account's* devices), true = "delete for everyone"
  }

  repeated Create creates = 1;
  repeated Rename renames = 2;
  repeated Delete deletes = 3;
}

// on SyncMessage.content oneof:
TopicSync topicSync = 27;
```

Sent via the existing sync-message channel (same delivery path as
`DeleteForMe`) any time a topic is created, renamed, or deleted, regardless
of whether the lifecycle DataMessage above also went to the peer.

### 6.4 Per-message delete — no new proto needed

Deleting an individual message inside a topic (for me or for everyone) is
just a normal message delete — `DataMessage.Delete` (existing field 17) and
the existing `DeleteForMe` sync message already work off `AddressableMessage`
+ `ConversationIdentifier`/`AddressableMessage` and don't care whether the
target message happens to have a `topic_id`. Nothing new required here.

### 6.5 Degradation summary (stock Signal recipient)

| Event | What a stock Signal client renders |
|---|---|
| Topic created | Plain text bubble: `"📌 Sarah started a topic: Weekend Trip Planning"` (the `topicContext`/`topicId` fields are simply unknown/ignored). Selected source messages: unaffected, they already existed in the timeline as normal messages. |
| Message sent inside topic | Plain text/media bubble with a native "replying to: 📌 Sarah started a topic…" quote preview (from the `quote` field we also set). Tapping the quote preview jumps to the anchor message, the closest stock-Signal analog to "open the topic." |
| Topic renamed / deleted | Plain text bubble with the fallback body text. No retroactive change to already-rendered messages (stock Signal has no notion of a topic to update). |

## 7. Interaction between §4 duplication and §6.4 message deletion

Because a message selected to start a topic exists as **two rows**
(the untouched original in the parent timeline, plus a topic-owned copy),
deleting it needs a defined behavior:

- **Delete for me**, from inside the topic thread, removes only the
  topic-owned copy. The original in the parent timeline is unaffected.
- **Delete for me**, from the parent timeline (on a message that has a
  topic label), removes only that original row; the topic-owned copy
  remains visible inside the topic.
- **Delete for everyone**, from either location, resolves to the same
  underlying `AddressableMessage` (author + `sentTimestamp`) and — like all
  delete-for-everyone today — is a tombstone broadcast, so it is applied to
  **both** rows locally (parent-timeline original and topic-owned copy) on
  every device, since a genuine "everyone" delete means the message is gone,
  full stop, not just gone from one view of it.
- **Deleting an entire topic** ("Delete for me"): the topic's tombstone is
  set locally (§4.2); its topic-owned message copies are removed on this
  device; the parent-timeline originals and their labels are **unaffected**
  (the label simply stops being tappable/becomes plain text, since the
  topic it pointed to no longer resolves). "Delete for everyone": the
  `TopicSync.Delete{isFullDelete: true}` (§6.3) and the peer-facing
  lifecycle notice (§6.1) both fire, and every Molly-aware device tombstones
  the topic and removes its owned copies; a stock-Signal peer just sees the
  plain-text deletion notice and nothing else changes for them (their
  timeline never had a separate topic view to begin with).

## 8. Backup Format (`lib/archive/.../Backup.proto`, "Backup v2")

`Chat` stays 1:1 with a recipient; `ChatItem`s belong to a `chatId`. Adding
topics:

### 8.1 New `Topic` frame

Modeled the same way `DistributionListItem`/`CallLink` are — their own
top-level `Frame.item` oneof variant, referencing their parent by id, with a
tombstone shape identical to `DistributionListItem`'s
`deletionTimestamp`/full-item oneof:

```proto
message TopicItem {
  uint64 chatId = 1;               // references the owning Chat
  string topicUuid = 2;
  oneof item {
    uint64 deletionTimestamp = 3;  // tombstoned topic: skip rendering, keep the id reserved
    TopicDetails topicDetails = 4;
  }
}

message TopicDetails {
  string name = 1;
  uint64 createdTimestamp = 2;
  optional uint64 anchorMessageChatItemId = 3; // local-file-scoped id of the anchor ChatItem
}

// on Frame.item oneof:
TopicItem topic = 5;
```

Per the ordering rule already documented at the top of `Backup.proto`
("all Frames referencing an item must come after it"), a chat's `TopicItem`
frames must appear after its `Chat` frame and before any `ChatItem` that
references that topic.

### 8.2 `ChatItem.topicId` (new field 23)

```proto
optional string topicId = 23; // set only on topic-owned message copies
```

### 8.3 Lifecycle notices in the backup

The anchor/rename/delete notice messages (§5/§6.1) back up as ordinary
`ChatItem`s with a `StandardMessage` body (so a restore on a client that
doesn't understand topics at all still shows the plain-text fallback) — no
new `ChatUpdateMessage` variant is required for correctness, though one
could be added later purely for nicer backup-restore rendering on
Molly-aware clients, matching how `GroupChangeChatUpdate` already gets a
richer backup representation than its wire form.

### 8.4 Legacy local backup (`app/src/main/protowire/Backups.proto`)

This is the older, pre-Backup-v2 local-encrypted-backup format. Given it's
being phased out in favor of the format above, this design proposes **not**
extending it for topics in the initial implementation — a restore from a
legacy backup would simply not have topic data (topic-owned message copies
would not be present; parent-timeline originals restore normally since
they're just ordinary messages). Flagging this as an explicit, deliberate
gap rather than an oversight.

## 9. Open Questions / Risks

1. **Upstream merge risk.** Molly regularly merges from Signal-Android
   upstream (confirmed: `PinMessage`/`UnpinMessage`/`AdminDelete` at fields
   27–29 already exist in both repos identically). Any new field numbers
   claimed here (`DataMessage` 30/31, `SyncMessage.content` 27,
   `ChatItem.item` 23) are a race against whatever upstream claims next —
   this needs to be re-verified against upstream's current numbering
   immediately before implementation, not just at design time.
2. **`BASE_TYPE_MASK` headroom.** Only 4 values remain free (19, 29, 30, 31)
   in the 5-bit base-type space system-wide, not just for this feature. This
   design deliberately spends only **one** of them (§5) and pushes
   everything else through `MessageExtras`, but that's a shared, scarce
   resource worth flagging to whoever owns that part of the schema.
3. **Does topic activity affect the parent chat's unread badge / snippet /
   notifications?** Not decided yet — §4.3 currently treats topic-owned
   messages as invisible to `ThreadTable`'s existing unread/snippet logic,
   which means a topic could accumulate unread messages with zero visible
   signal on the conversation list. Needs a decision: likely "yes, still
   contributes to the parent chat's unread count and triggers notifications
   normally, just doesn't render inline," but should be confirmed.
4. **Does global message search include topic-owned messages?** Leaning
   yes (they're still real rows in the same database), but not yet decided.
5. **6-topic cap is unenforceable against a modified peer.** A client that
   doesn't respect the cap (or a future Molly bug) could create a 7th topic
   and sync it in; this design treats that as "display the first 6,
   degrade gracefully" rather than trying to enforce a hard protocol-level
   limit, since there's no server-side authority for chat-scoped metadata
   like this (unlike, say, group membership).
6. **New-linked-device backfill.** A device linked *after* topics already
   exist needs the existing multi-device history-sync mechanism to carry
   topic state over — not yet mapped to a specific existing sync flow;
   needs investigation into how the current initial-sync/backfill path
   works before implementation.

## 10. Suggested Phased Delivery

Given the scope, this is not a single PR:

1. **Phase 1 — local data model + UI, single device only.** `topic` table,
   `message.topic_id`, `MessageExtras.topicUpdate`, all the UI (header icon
   + dropdown, selection action, name-input dialog, topic thread screen,
   inline notices, rename/delete prompts). No wire protocol or sync yet —
   topics exist only on the device that created them. This is enough to
   fully validate the UX and data model in isolation.
2. **Phase 2 — wire protocol + own-device sync.** §6.1, §6.2, §6.3. Topics
   and topic messages now sync across the user's own linked devices and
   degrade gracefully to peers on stock Signal/older Molly.
3. **Phase 3 — backup v2 integration.** §8.
4. **Phase 4 — polish & the open questions in §9**, plus real Molly↔Molly
   multi-device testing.
