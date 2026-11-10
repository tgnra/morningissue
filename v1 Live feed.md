# Feed-Style RSS Reader for Android — Plan

Nov 10, 2026 · @Tania

## Overview

The app is an RSS reader whose home screen is a single ranked feed, not a list of folders and unread counts. Each article's place in the feed depends on how often its source posts and how much the reader cares about that source, so a monthly essay from a favourite blog stays visible for days while a news wire's posts fade within hours.

A chronological reader treats every item the same: the busiest feeds bury the quiet ones, and unread counts turn reading into a chore. This design fixes both by judging freshness relative to each feed's own pace.

**Principles**

- **Freshness is relative.** "Old" means old for that feed. Two days is ancient for a news site and brand new for a blog that posts monthly.
- **No unread debt.** Items quietly expire from the feed. There is no badge counting what you missed.
- **Scarcity earns attention.** Rare posts from liked sources get a longer stay and a boost.
- **Explainable.** Every card can say why it is there in one line.
- **Private and local.** All signals and scoring live on the device. No account is required.
- **User stays in control.** Pinning, muting and per-feed overrides always beat the algorithm.

## Signals we collect

Three families of signal drive ranking: how often each feed posts, how the reader treats each article, and a per-feed affinity score rolled up from those interactions.

### Feed cadence

Cadence is the typical gap between posts, recomputed after each sync from the last 30 publish timestamps (or 90 days, whichever covers more).

- **Median interval** between posts, in hours. Median rather than mean, so one long holiday gap doesn't distort it.
- **Posts per day**, derived as 24 ÷ median interval.
- **Burstiness**: ratio of the 90th to the 10th percentile interval. High values flag feeds that dump many posts at once (podcasts, link roundups).
- **Cadence tier**, used in the UI and as a default: Firehose (more than 8 a day), Daily (1–8 a day), Weekly (1–6 a week), Rare (fewer than 1 a week).

New feeds with fewer than 5 items fall back to the tier of their sample, defaulting to Weekly.

### Article engagement

Each card records what happened to it. These are the raw events:

| Event | How it's detected | Meaning |
| --- | --- | --- |
| Impression | Card ≥ 50% visible for ≥ 1 second | Was seen |
| Open | Tapped into reader view or browser | Interested |
| Read | Scrolled ≥ 70% of body, or dwell ≥ estimated read time × 0.5 | Strong interest |
| Save (read later) / share | Explicit action | Strongest interest |
| Skip | Impression, then scrolled past without opening, 3+ times | Mild disinterest |
| Dismiss | Swiped away or "Not interested" | Clear disinterest |
| Like | Heart button on the card or in reader view | "I read this and liked it": strongest signal |

Open and Read are stored separately because headline clickbait gets opens without reads.

### Feed affinity

Affinity is one number per feed, 0 to 1, that answers "how much does this reader care about this source?" It is an exponentially weighted ratio of positive engagement to impressions, with a 30-day half-life so tastes can drift.

- Positive weight per article: Open 1, Read 2, Save or share 2, Like 3.
- Negative weight: Skip 0.3. A single dismiss only hides that post (weight 0); from the second dismiss from the same feed within 14 days, each counts 1.5.
- Feeds start at 0.5 (neutral) and need about 10 impressions before the score moves far from it, using a Bayesian prior.
- A per-feed 5-step slider overrides learning: Rarely (caps affinity at 0.15), Less (caps at 0.35), Auto (centre, the default: fully learned), More (floors at 0.65), Favourite (pins at 1.0).

Users can tag feeds (e.g. "tech", "personal"), and each tag learns its own affinity the same way. A new or rarely seen feed borrows the average of its tags' affinity until it has enough impressions of its own.

### Data model (Room)

| Table | Key fields |
| --- | --- |
| `feed` | id, url, title, iconUrl, medianIntervalHrs, burstiness, tier, affinity, sliderStep, tags, notify, muteUntil, dismissCount14d, lastSyncAt |
| `article` | id, feedId, guid, url, title, summary, imageUrl, author, publishedAt, fetchedAt, wordCount, contentHash |
| `engagement` | articleId, impressions, firstSeenAt, opened, readFraction, saved, liked, dismissed, skipCount |
| `feed_score_cache` | articleId, score, computedAt, reason |

## Ranking algorithm

Each unread article gets a score from four multiplied factors: how much you like its feed, how fresh it is relative to that feed's pace, how rare posts from that feed are, and whether you've already scrolled past it.

```latex
\text{score} = A \cdot F \cdot S \cdot P
```

### A — Affinity weight

A = 0.3 + 0.7 × affinity. The 0.3 floor keeps disliked-but-subscribed feeds from vanishing entirely, so the reader can rediscover them.

### F — Relative freshness

Freshness decays exponentially, with a half-life tied to the feed's median posting interval. This is the core idea: age is measured in "posts this feed would normally have made since", not hours.

```latex
F = 0.5^{\,\text{age} / h} \qquad h = \mathrm{clamp}(1.5 \times \text{medianInterval},\ 4\text{h},\ 10\text{d})
```

| Cadence tier | Typical median interval | Half-life h | Drops out of feed after (F < 0.05) |
| --- | --- | --- | --- |
| Firehose | 30 min | 4 h (floor) | \~17 h |
| Daily | 24 h | 36 h | \~6 days |
| Weekly | 7 days | 10 days (ceiling) | 30 days; never while unread for loved feeds |
| Rare | 30 days | 10 days (ceiling) | 30 days; never while unread for loved feeds |

### S — Scarcity boost

Posts from slow feeds get a modest lift, because each one is a rarer event. It grows with the log of the interval and is capped so it can't overwhelm affinity.

```latex
S = \min\left(1.8,\ 1 + 0.15 \cdot \log_2\left(1 + \frac{\text{medianInterval}}{24\text{h}}\right)\right)
```

That's 1.0 for a firehose, 1.15 for daily, 1.45 for weekly, about 1.74 for monthly.

### P — Seen penalty

P = 0.6 ^ (impressions without an open). An item scrolled past twice keeps 36% of its score, so it sinks but can resurface if nothing better arrives. P = 0 once the article is read or dismissed; saved items move to the Saved tab.

### Worked examples

Scores at the same moment, all unread and unseen:

| Article | Affinity | Age | A | F | S | Score |
| --- | --- | --- | --- | --- | --- | --- |
| Favourite weekly blog | 0.9 | 7 days | 0.93 | 0.62 | 1.45 | **0.83** |
| Daily newsletter, so-so | 0.3 | 6 h | 0.51 | 0.89 | 1.15 | 0.52 |
| News site (firehose), neutral | 0.5 | 3 h | 0.65 | 0.59 | 1.00 | 0.39 |
| Daily newsletter, neutral | 0.5 | 3 days | 0.65 | 0.25 | 1.15 | 0.19 |
| News site (firehose), neutral | 0.5 | 12 h | 0.65 | 0.13 | 1.00 | 0.08 |

The week-old post from the blog you love beats this morning's news, which is exactly the behaviour you described. The news item from last night has effectively left the feed.

### Why multiply, not add

Multiplying means any factor near zero kills the item: stale news can't be rescued by affinity, and a dismissed item never returns. Every constant above (1.5×, 0.15, 0.6, the clamps) lives in one `RankingConfig` object so it can be tuned without touching the formula.

## Feed assembly

Scoring ranks items; assembly turns that ranking into a scroll that feels varied and stable. It runs on app open, on pull-to-refresh, and after each background sync, never while the user is mid-scroll.

1. **Build the candidate pool.** All unread, undismissed articles with F ≥ 0.05 and age ≤ 30 days, plus unread items of any age from loved Weekly or Rare feeds (affinity ≥ 0.7, or slider at More or Favourite), up to 5 per feed. These never expire while unread; their low freshness just lets them sink until From the archive picks them up.
2. **Score** every candidate with the formula above.
3. **Deduplicate.** Collapse articles sharing a canonical URL or a near-identical title (same story syndicated across feeds); keep the one from the higher-affinity feed and show "also in 2 feeds".
4. **Diversify greedily.** Take the top item, then repeatedly pick the next-best item after applying a penalty of × 0.7 for each item from the same feed in the last 5 slots. No feed gets more than 2 of any 10 consecutive cards.
5. **Cluster bursts.** If one feed has 4+ items in the pool from a burst (high burstiness), show the best as a card and fold the rest into a "+3 more from The Verge" stack.
6. **Insert special cards** at fixed slots: a "From the archive" card (see below) around position 8, a "Catch-up" digest at the top after 24+ hours away.
7. **Freeze the order** for the session. New items from a sync appear behind a "5 new posts" pill at the top, never by reshuffling cards under the user's thumb.

### From the archive

This is the "surface an unread week-old post" behaviour made explicit. Once per session, pick the highest-scoring unread item older than 3 days from a feed with affinity ≥ 0.7 and label it "From the archive" or "You missed this". It gives slow, loved feeds a guaranteed slot even on heavy news days.

### Catch-up mode

After a long absence the pool is dominated by stale firehose items that have already expired. A Catch-up card at the top summarises "While you were away: 12 posts from 7 feeds", ordered by affinity, so a holiday doesn't lose rare-feed posts.

Tapping the card opens **Catch-up mode**: a separate list of posts that expired from the main feed, ranked by the same score with freshness measured from when the user was last active instead of from now. Firehose feeds are left out unless the user has Liked or saved a post from them in the last 30 days, and only feeds at Auto or above appear, so the list holds things the user is likely to care about rather than stale news. It stops after the top 30.

### Cold start

A new user has no engagement data, so every feed starts at affinity 0.5 and ranking is driven by freshness and scarcity alone. To learn faster:

- During onboarding, ask the user to star 3–5 favourite feeds (sets affinity to 0.8).
- For the first two weeks, raise the diversity penalty so every feed gets impressions and affinity can be measured.
- OPML import keeps folder names as a weak hint (e.g. a folder called "Must read" seeds 0.7).

## UI design

The home screen is one vertical card feed, with three bottom tabs: **Feed**, **Saved**, **Sources**. There are no unread counts anywhere.

### Card types

| Card | When used | Content |
| --- | --- | --- |
| Hero | Item has a large image and score in the top 3 | Full-width image, title, feed icon + name, relative time, 2-line excerpt |
| Standard | Default | Thumbnail right, title, feed name, time, 1-line excerpt |
| Compact | Firehose feeds, text-only items | Title and feed name on one or two lines |
| Stack | Burst from one feed | Top item plus "+3 more from …", expands in place |
| Archive | "From the archive" slot | Standard card with a label chip and a subtle tinted background |
| Catch-up | After 24+ h away | Horizontal carousel of the top items per feed |
| Video | YouTube RSS and video podcast items | 16:9 thumbnail with play overlay and duration, plays inline; audio podcasts get a compact player row |

Time labels are relative to the feed too: a firehose item says "3h ago", a rare blog's says "new this week" so the reader isn't put off by age.

### Gestures and actions

- **Tap** opens the article in a reader view (parsed full text when the feed is truncated, via Readability), with a toggle to the browser. A heart button on every card and at the end of the reader view records a Like.
- **Swipe right** saves; **swipe left** dismisses with an undo snackbar. One dismiss only hides that post; the feed's affinity drops only if the user keeps dismissing it.
- **Long-press** opens a sheet: Save, Share, Mark read, *Show more from this feed*, *Show less from this feed*, Mute feed (1 day, 3 days, 7 days or 30 days), *Why am I seeing this?*
- **Pull to refresh** syncs and inserts the "N new posts" pill.

### Why am I seeing this?

A one-line explanation built from the dominant factor, e.g. "You read most posts from Daring Fireball", "Rare post: this blog publishes about once a month", "Popular right now in a feed you follow". It builds trust and doubles as a correction point: its Show more / Show less buttons move the feed's slider one step.

### End of feed

When the pool is exhausted, show "You're all caught up" with the time of the next expected posts, instead of padding with stale items. A "Show older posts" link opens the Catch-up list, ranked by score, rather than a plain chronological dump.

### Sources tab

A list of feeds, each showing its cadence tier chip, posts per week and the 5-step affinity slider. Per-feed controls: tags (e.g. "tech", "personal"), notifications (off, or on for every new post), Mute, and "Always show everything" (bypasses expiry, for feeds the user reads exhaustively, like a webcomic). Add-feed accepts a site URL and autodiscovers the feed link; OPML import and export live here too.

### Tag filters

Tags double as filtered views of the feed. A row of chips under the top bar (All, plus one per tag, e.g. Tech, Personal) switches the feed to only that tag's feeds, ranked by the same score. A feed can carry several tags, so it shows up under each.

- The chip row only appears once at least one tag exists.
- Each tag view keeps its own frozen order and scroll position for the session.
- The last-used tag is remembered; the app always opens on All unless the user pins a tag as default.
- Tag affinity (see Feed affinity) keeps learning in every view, so reading in Tech also teaches the All feed.

### Reader view

Clean typography with adjustable text size, estimated read time, and a progress bar. Scroll depth here is what feeds the Read signal, so the reader view is part of the algorithm, not just presentation.

## Android architecture

The app is Kotlin with Jetpack Compose, a single Room database, and WorkManager for background sync; there is no server.

&#91;embedded content: app architecture · 7 components, one local database\]

Sync and the engagement tracker only ever write to the database; the ranker reads from it and hands an ordered list up to the UI.

| Concern | Choice | Notes |
| --- | --- | --- |
| UI | Jetpack Compose, `LazyColumn` | Impressions via `onGloballyPositioned` visibility checks |
| Storage | Room + Flow | Score cache rebuilt after sync, read as a Flow |
| Sync | WorkManager periodic work | 1 h on Wi-Fi/charging, 2 h otherwise; respects Doze |
| Fetching | OkHttp with ETag / Last-Modified | Avoids re-downloading unchanged feeds |
| Parsing | Rome or a light XmlPullParser-based parser | Handles RSS 2.0, Atom, JSON Feed |
| Full text | Readability4J | For feeds that only publish excerpts |
| Images | Coil | Thumbnails cached on disk |

Scoring a few thousand candidates is a sub-10 ms loop, so the ranker can run on every open without precomputation; the cache table exists mainly to give each card its "why" reason cheaply.

## Tuning, metrics and roadmap

Ship v1 with the formula and constants above, then tune them against a few on-device metrics rather than guessing.

### Metrics to watch (local, opt-in debug screen)

- **Read-through rate**: reads ÷ impressions in the top 10 cards. The main health signal for ranking.
- **Rare-feed coverage**: share of posts from Weekly/Rare feeds that get at least one impression before expiring. Target above 90%.
- **Expired unread from favourites**: should be close to zero; if not, half-lives are too short.
- **Dismiss rate** by card position: spikes high in the feed mean affinity is overweighted.
- **Session depth**: cards scrolled per session, to spot feeds that are too long or too thin.

### Phased roadmap

1. **MVP**: add feeds, OPML import, background sync, cadence stats, the A × F × S × P score, Standard and Compact cards, reader view, save and dismiss.
2. **Learning**: engagement tracker, affinity, seen penalty, Like button, affinity slider, Why am I seeing this?
3. **Polish**: diversity pass, burst stacks, From the archive slot, Catch-up card and mode, Hero and Video cards, per-feed notifications, feed tags with tag filter chips, dedupe across feeds.
4. **Later**: tag-level affinity, feed discovery (suggested feeds based on what the user Likes), full-text search, home-screen widgets.

### Open questions

- [ ] Should a favourite rare feed's new post trigger a notification, or is the archive slot enough?
- [ ] Is a 30-day hard cap right, or should loved rare feeds keep items until read?
- [ ] Do we want topic-level affinity (e.g. via keywords or on-device embeddings) or keep it per feed?
- [ ] How should podcasts and video feeds (YouTube RSS) render: same cards, or their own card type?
- [ ] Is a fully local app enough, or will users expect cross-device read state from day one?

### Decisions from review

| Question | Decision | Where it's reflected |
| --- | --- | --- |
| Notifications for rare feeds | A per-feed option: off, or on for every new post | Sources tab, Polish phase |
| 30-day cap | Loved Weekly/Rare feeds keep items until read; others still expire at 30 days | Freshness table, Feed assembly step 1 |
| Topic affinity | User tags on feeds ("tech", "personal"), each with learned affinity | Feed affinity, Later phase |
| Podcasts and video | Their own Video card | Card types |
| Sync | Fully local, no account | Principles |
| Save vs Like | Save = read later; Like = read it and enjoyed it, the strongest signal | Article engagement, gestures |
| Feed override | 5-step slider, Auto in the centre | Feed affinity, Sources tab |
| Dismiss and mute | One dismiss doesn't punish a feed; mute for 1, 3, 7 or 30 days | Feed affinity, gestures |
| Older posts | "Show older posts" and the Catch-up card both open Catch-up mode, ranked by score | Catch-up mode, End of feed |
| OPML folder hints | Dropped; feed discovery added as a later feature | Later phase |
