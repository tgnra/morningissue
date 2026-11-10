# Daily Edition RSS Reader — Plan v2

Nov 10, 2026

This replaces the live-feed design in v1: Live feed. The signals, affinity, slider, Like button, scoring maths and Android architecture there still apply, with the changes listed in "What changes from v1" below.

## Overview

The app publishes one personal newspaper a day from the user's RSS feeds. Each morning's edition is finite: a front page, a section per tag, a column of briefs, and a back page. When you reach the back page, you're done for the day.

Nothing piles up and nothing is lost. A post you skip either carries into tomorrow's paper (if it's from a slow feed you love) or stays findable in back issues and search.

**Principles**

- **One edition a day, with an end.** No infinite scroll, no unread counts.
- **Freshness is relative** to each feed's pace, measured at publish time.
- **Scarcity earns space.** Rare posts from loved feeds get page space a news wire doesn't.
- **Nothing is lost.** Every edition and every fetched post stays browsable.
- **Explainable, private, local.** Every story can say why it's there; all data stays on the phone.
- **User stays in control** through the slider, tags, mutes and edition settings.

## The edition

### Publishing schedule

- The edition resets at a time the user picks: 6:30 am by default, in the user's local time zone, and as early as they like (a shift worker might choose 3 am).
- The edition is built ahead of time and released at the reset time. About 1–2 hours before the reset, a background job does a final sync, builds the edition, prefetches its images and full text, and stores it unreleased. Releasing needs no job: when the app opens after the reset time, it shows the stored edition. If the early job didn't run, the app builds the edition on open instead. No notification announces the edition.
- Each edition covers posts the app first saw between the previous cutoff and this one. Using first-seen time rather than published date means a post fetched late lands in the next edition instead of vanishing.
- Opening the app before the reset time shows the current edition, with "Next edition at 6:30" on the back page.
- An optional **Evening edition** (off by default) covers only fast-moving feeds (Firehose and Daily tiers) since the morning cutoff. Weekly and Rare feeds never appear in it; their posts wait for the morning paper.

### Why build early, with a fallback

Android won't reliably run a job at an exact time. WorkManager's periodic work is deliberately inexact and can be held back by Doze and battery optimisation, sometimes for hours, and exact alarms need a permission that Google Play only grants to alarm and calendar apps. So the early build is best effort: a one-off WorkManager job aimed at 1–2 hours before the reset, which usually runs in time but isn't guaranteed. Release is just a clock check when the app opens, so it can't be late, and building on open covers the days the early job doesn't run. Anything fetched after the early build rolls into the next edition, since posts are assigned by when the app first saw them.

### Sync and build

| Job | When | What it does |
| --- | --- | --- |
| Background sync | WorkManager, roughly every 1–2 hours | Fetches feeds, stores new posts with their first-seen time, updates cadence stats and scores |
| Early build | One-off job aimed at 1–2 hours before the reset | Final sync, builds the edition, prefetches images and full text for its stories, stores it unreleased |
| Release | When the app opens after the reset time | Shows the stored edition, or builds it on the spot if the early build didn't run |

- **First launch.** Nothing is stored yet, so onboarding fetches every feed straight away ("Fetching 42 feeds…") and builds a first edition. This is the only time opening the app waits on the network.
- **Pull to refresh** never changes the day's edition. It fetches posts for the next one and says so: "12 new posts saved for tomorrow".
- **Long gaps** (phone off, jobs held back): the next build includes everything first seen since the last cutoff, and While you were away covers it.
- **Offline.** An edition built early already has its images and text on the phone, so it reads fully offline.

### Performance

Building is cheap. Scoring around 1,000 candidates takes about a millisecond; the main cost is a database read of tens of milliseconds.

- Index `article` on first-seen time and feed, so the candidate query only touches the last day or two plus held-over posts, however big the archive grows.
- Never sync on open, apart from first launch.
- Keep scores current during sync, so a fallback build mostly reads a sorted list.
- Draw the masthead and page layout instantly and fill in stories as they load.
- Budget: a fallback build under 300 ms on a low-end phone, checked with a Macrobenchmark test.

### Notifications

The edition itself never notifies. Notifications are only for feeds the user loves, and the details still need working out. Starting points:

- Opt-in per feed, offered for feeds set to Favourite on the slider.
- Sent when a background sync finds a new post, so they arrive within an hour or two of publishing rather than instantly.
- Quiet hours respected, and several posts bundled into one notification.
- Tapping opens the article in the reader view. It doesn't change the day's edition; if left unread, the post still runs in the next one.

### Structure

| Part | What goes in | Size |
| --- | --- | --- |
| Front page | The best stories across all feeds and tags, at most one per feed | 1 lead story + 4 |
| Top stories | Pages 2 and 3: the next best stories, still mixed regardless of tag | Up to 8 per page |
| Section pages | One page per tag (Tech, Personal…), ranked within the tag; untagged feeds go in General | Up to 6 stories each |
| In brief | Headlines only, mostly from Firehose feeds | Up to 15 lines |
| From the archive | Best unread post still held over from a loved slow feed | 1 story |
| Also in your feeds | Back-page index of every post that didn't make the paper, grouped by source, headlines only | Everything left over |
| Subscriptions desk | The feed health check (see below) | Once a month |

An edition-length setting (Short, Standard, Long) scales these limits. Standard aims for about 25 stories plus briefs, roughly 20–30 minutes of reading.

## Edition assembly

This replaces v1's live feed assembly. The score is the same A × F × S × P; what changes is when it's measured and how stories are placed on pages.

1. **Build the candidate pool.** Posts first seen since the last cutoff, plus held-over unread posts from loved Weekly or Rare feeds (affinity ≥ 0.7, or slider at More or Favourite). Muted feeds are skipped.
2. **Score at publish time.** Freshness is measured from the publish moment, not from when the user reads. In daily mode the half-life floor rises from 4 h to 12 h, so last night's news still counts by morning.
3. **Deduplicate** syndicated stories across feeds, keeping the copy from the higher-affinity feed.
4. **Lay out the front page.** The top 5 scores, at most one per feed; the highest-scoring story becomes the lead, whether or not it has an image.
5. **Fill Top stories, then sections.** The next best stories, regardless of tag, fill the Top stories pages (2 and 3). Each tag's section page then takes its top remaining stories. A feed with several tags appears in the section where it scores best, never twice in one edition.
6. **Fill In brief.** Remaining Firehose and Daily posts above a score threshold go in as headline-only lines.
7. **Pick From the archive.** The best held-over post that hasn't run in the last 3 editions.
8. **Freeze and store** the edition. Reading it never reshuffles it; Likes, reads and skips only shape future editions.

### Carry-over

- Posts from loved slow feeds stay eligible until read, matching v1's decision. They can lead the front page at most twice; after that they only appear via From the archive.
- Everything else that didn't make the edition is still listed on the back-page index, Also in your feeds, so nothing is silently skipped. Those posts, and any that ran without being opened, aren't printed again but stay in back issues and search.
- The seen penalty now counts editions, not scroll-pasts: P = 0.5 for each edition a post ran in without being opened.

## Seeing what you missed

A finite paper only works if nothing feels thrown away. There are five ways back to older posts:

- **While you were away.** If the user skips one or more days, the next edition opens with a pull-out section: the best stories from the missed editions, ranked by score with freshness measured from the user's last visit. Firehose feeds are left out unless the user has Liked or saved one of their posts in the last 30 days. Missed editions never pile up as a to-do list.
- **Back issues.** Every edition is stored as published and can be opened from a calendar, with read stories ticked. Editions are stored compressed (gzip) within a storage budget the user sets with a slider in Settings. When the budget fills, the oldest editions are trimmed first; Liked and saved posts are always kept.
- **Source pages.** Tapping a source's byline opens all its recent posts in date order, like a columnist's page.
- **Search.** Full-text search across every fetched post, filterable by tag, source, date, and Liked or saved.
- **Saved and Liked** shelves, reached from the masthead menu.

**Archive export and import.** Users can export old editions to external storage (an SD card, a cloud drive, a computer) and import a backup later. Import merges into the current database, skipping posts it already holds (matched by feed and post ID), so someone can free up space without losing anything and bring it back whenever they like.

## Feed health check

Once a month the edition carries a **Subscriptions desk** page, also always available in Sources. It flags feeds that need attention and offers one-tap fixes. It never unsubscribes on its own.

| Flag | Rule | Suggested action |
| --- | --- | --- |
| Broken | Fetch errors 7 days running, or the feed returns 410 Gone | Retry automatically, then try common feed addresses (/feed, /rss, /atom.xml, /index.xml, http/https and www variants, and the site's own feed link); only ask the user to fix or remove if none work |
| Moved | Permanent redirect (301) | Update the address automatically, then tell the user |
| Gone quiet | No posts in 90 days (not a problem) | Nothing to do. The feed moves to a Quiet group at the bottom of Sources and is checked less often, stepping down from hourly to weekly |
| Ignored | Ran in 10+ editions over 60 days with no opens | Unsubscribe, or set the slider to Less |
| Noisy | 30+ posts a week with a read rate under 2% | Move to In brief only, or set to Less |
| Loved | High read and Like rate | Suggest Favourite or turning on notifications |

Dismissing a flag hides it for 6 months. The page shows nothing when all feeds are healthy, so it doesn't become noise.

**Welcome back.** Quiet feeds are kept on purpose. When one posts again, its return is news in its own right: the story gets a "Back after 2 years" label and a comeback boost on top of the scarcity boost, so it almost always makes the front page.

## Newspaper design

The edition should read like a well-set paper adapted to a phone, not a social feed with a serif font.

### Masthead and pages

- **Masthead** at the top of the front page: the app's name, set smaller than a real newspaper's so the stories get the space, the date, the edition number, and the estimated reading time.
- **Pages** turn with a horizontal swipe and a 3D page-turn animation: front page → one page per section → In brief → back page. A thin bar under the masthead shows page 3 of 7, and tapping it opens a contents list.
- **Back page**: "That's today's edition", the time of the next one, and links to back issues and search.

**Page turn.** A 3D flip built in Compose: each page rotates around its spine (`graphicsLayer` with `rotationY` and a raised camera distance) and darkens slightly as it turns. It tracks the finger, so a half-turn can be let go. A true paper curl is possible later with an AGSL shader on Android 13 and up. When the system's animations are switched off, it falls back to a plain slide.

### Story layout

- **Lead story**: large headline, two-line standfirst and byline ("Daring Fireball · 12 min read"), with a full-width photo when the article has one. Without a photo, the headline grows, the standfirst runs to four lines, and rules above and below give the page a clear anchor.
- **Secondary stories**: a 2-column grid on phones, each with a headline, byline and optional small photo; 3 columns on tablets and in landscape.
- **In brief**: a tight list of headlines with source names, separated by hairline rules.
- No card shadows or rounded boxes: stories sit on the page, divided by thin rules and white space.

### Type and colour

- Serif display face for headlines and a readable serif for articles (open-licence options such as Newsreader or Fraunces), with a sans for bylines and labels. Section names in small caps.
- A warm off-white paper theme by default and a true dark "Night edition" theme; Material You accent colour optional.

### Reading and actions

- Tapping a story opens the reader view in the same typography; Like and Save sit at the end of the article. Read stories get a small tick on the page.
- Long-press a story for Save, Like, Show more / Show less (moves the feed's slider a step), Mute for 1, 3, 7 or 30 days, and Why is this here?
- There are no swipe-to-dismiss gestures: horizontal swipes turn pages, and skipping a story is simply not reading it.

## What changes from v1

| Area | v1: Live feed | v2: Daily edition |
| --- | --- | --- |
| Home screen | Endless ranked scroll | One finite edition a day |
| Freshness | Measured from now; 4 h half-life floor | Measured at publish time; 12 h floor |
| Seen penalty | Per scroll-past | Per edition run without an open |
| Tags | Filter chips | Section pages, after mixed Top stories |
| Firehose posts | Compact cards | In brief column |
| Catch-up | Catch-up card and list | While you were away pull-out, back issues |
| Dismiss | Swipe left | Removed; long-press menu only |

Unchanged: cadence tracking, affinity, the 5-step slider, Like, tags, scarcity boost, on-device architecture. The data model gains three tables: `edition` (id, date, cutoffAt, publishedAt, length), `edition_item` (editionId, articleId, page, section, slot), and `feed_health` (feedId, lastPostAt, errorStreak, lastFlag, flagDismissedUntil), plus a full-text search index on `article`.

## Roadmap

1. **MVP**: sync, cadence stats, scoring, the morning edition, built early with an on-open fallback (front page, Top stories, In brief, Also in your feeds), newspaper reader view, back issues.
2. **Learning**: engagement tracking, affinity, Like, the slider, tags as sections.
3. **Polish**: While you were away, search, Subscriptions desk with quiet feeds and Welcome back, archive export and import, 3D page turn, tablet layout, Evening edition, notifications for loved feeds.
4. **Later**: feed discovery from Likes, a home-screen widget showing today's front page, Likes as a public feed.

## Open questions

- [ ] Should anything appear between editions (for example breaking news from a Favourite feed), or is the Evening edition enough?
- [ ] How long should back issues be kept by default: 90 days, a year, forever?
- [ ] Should the publish time adapt to when the user usually opens the app?
- [ ] Would a PDF or printable export of an edition be worth building?

### Decisions

| Question | Decision |
| --- | --- |
| Anything between editions? | No. Only opt-in notifications for loved feeds, which open the article without changing the edition |
| How long to keep back issues? | Set by a storage budget slider, with archive export and import |
| Should publish time adapt? | No. A fixed reset time in the user's time zone, 6:30 am by default, set as early as they like |
| PDF or print export? | Not initially |

**Still open:** the details of loved-feed notifications (see Notifications).
