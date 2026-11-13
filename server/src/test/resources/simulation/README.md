# Ranking simulation fixtures

Real feeds for `RankingSimulationTest`
(`server/src/test/kotlin/net/tgnra/morningissue/server/simulation/`), which drives the server's
refresher, edition builds, event merge and learning for one simulated reader per persona. The
tests never touch the network: `SimulatedInternet` answers the server's fetcher from these
files.

Run it with

    ./gradlew -PserverOnly :server:test --tests '*RankingSimulation*'

(a few minutes). Add `-PsimulationReport` for a report per persona, with the numbers each
assertion measured, in `server/build/reports/simulation/` (or `-PsimulationReport=<dir>`).

## The feeds

All fetched on **2026-11-11**.

| Fixture | Feed | Fetched from | Items | Real cadence | Simulated cadence |
| --- | --- | --- | --- | --- | --- |
| `bbc-news.xml` | BBC News, top stories | https://feeds.bbci.co.uk/news/rss.xml | 32 | many a day | 10 a day |
| `bbc-scotland.xml` | BBC News, Scotland | https://feeds.bbci.co.uk/news/scotland/rss.xml | 33 | 7.6 a day | 6 a day |
| `guardian-uk.xml` | The Guardian, UK news | https://www.theguardian.com/uk-news/rss | 30 | 4.5 a day | 4 a day |
| `guardian-business.xml` | The Guardian, Business | https://www.theguardian.com/uk/business/rss | 30 | 9.7 a day | 9 a day |
| `jvns.xml` | Julia Evans (tech blog, code) | https://jvns.ca/atom.xml | 20 | about monthly, in bursts | weekly ± 3 days |
| `smitten-kitchen.xml` | smitten kitchen (cooking) | https://smittenkitchen.com/feed/ and `?paged=2` | 20 | every 2 weeks | weekly ± 3 days |
| `irunfar.xml` | iRunFar (running) | https://www.irunfar.com/feed, `?paged=2`, `?paged=3` | 15 | 1.5 a day | 1.5 a day |
| `yarn-harlot.xml` | Yarn Harlot (knitting) | https://www.yarnharlot.ca/feed/ and `?paged=2` | 20 | every few weeks | weekly ± 3 days |
| `bike-snob.xml` | BIKESNOBNYC (cycling) | https://bikesnobnyc.blogspot.com/feeds/posts/default?max-results=25&redirect=false | 25 | 2-3 a week (2019) | every 3 ± 1.5 days |
| `35mmc.xml` | 35mmc (photography) | https://www.35mmc.com/feed/ | 10 | 3.3 a day | 2.5 a day |
| `indie-games-plus.xml` | Indie Games Plus (indie games) | https://indiegamesplus.com/feed/ | 10 | 0.5 a day | 0.5 a day |
| `atp.xml` | Accidental Tech Podcast (audio enclosures) | https://atp.fm/rss | 25 | weekly | weekly |
| `daring-fireball.xml` | Daring Fireball (link blog) | https://daringfireball.net/feeds/main | 40 | 2-3 a day | 3 a day |
| `danluu.xml` | Dan Luu (long-form, low volume) | https://danluu.com/atom.xml | 20 | every month or two | monthly ± 10 days |

The cadences of the slow blogs (Julia Evans, smitten kitchen, Yarn Harlot) were nudged up to
weekly so that the personas who love them have something to read in 180 days; a feed that posts
every six weeks would give a persona four posts in all. Dan Luu stays the low-volume feed.

Tried and not used: The Online Photographer (403), Mason-Dixon Knitting (DNS failure), Warp Door
(a bot challenge instead of a feed), Running Writings (404), Purl Soho and Tin Can Knits (not
well-formed).

## What was kept

`tools/simulation/normalize_feeds.py` wrote these files from the downloads:

- Every feed is rewritten as RSS 2.0, whatever it was (Atom for Julia Evans, BIKESNOBNYC, Daring
  Fireball and Dan Luu). The channel keeps its title and site address.
- Each item keeps its title, link (for a link blog, the address it points to), publish date,
  categories, enclosure and the opening of its body: at most 130 words of text, with links and
  images, plus the body's first code block when the cut dropped it. Scripts, styles, embeds and
  attributes other than `href`, `src` and `alt` are dropped.
- `<mi:words>` holds the number of words in the full body as fetched. The simulation restores
  each post's length from it, by repeating the kept paragraphs (up to 2,500 words), so length
  keys see real lengths while the fixtures stay small.

## How 180 days are made from 10 to 40 items

`PostSchedule` (in `SimulatedFeeds.kt`), from a seed:

- Each feed's posting times follow its simulated cadence above: a Poisson number of posts a day
  for the busy feeds, at daytime hours; one post every so many days, give or take, for the slow
  ones. Times start a year before day 0, so the first fetch finds a history and a cadence.
- The items are replayed as templates in a seeded order, each used once before any is used
  again, with their titles, categories, lengths, enclosures and bodies.
- A replayed item gets its own GUID and address (`?mi=<n>` added to the link) and, from its
  second use on, a bracketed tail on its title ("(update)", "(follow-up)", ...), so posts are not
  exact duplicates. The tail cannot create a title prefix and barely moves the embedding.
- A feed's document at any moment holds its newest 10 or 15 posts published by then.

The same seed always gives the same posts at the same times.
