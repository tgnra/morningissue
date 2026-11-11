# Discovery catalogue sources

Where the candidate feeds in `app/src/main/assets/discovery/catalogue.db` come from, and on
what terms. The licence notice that ships in the APK is
`app/src/main/assets/licenses/discovery-catalogue.txt`, next to the embedding model's.

| Source | What is used | Licence | Attribution |
| --- | --- | --- | --- |
| [Kagi Small Web](https://github.com/kagisearch/smallweb) | `smallweb.txt`: personal blogs, one feed address per line | MIT, © 2023 Kagi Search | Required: the MIT notice is reproduced in the shipped notice file |
| [plenaryapp/awesome-rss-feeds](https://github.com/plenaryapp/awesome-rss-feeds) | `recommended/with_category/*.opml` and the English-language countries' `countries/with_category/*.opml`. Each file's name is a category, used to label clusters | CC0 1.0 | Not required; given anyway |
| [outcoldman/hackernews-personal-blogs](https://github.com/outcoldman/hackernews-personal-blogs) | `list.opml` | MIT, © 2023 Denis Gladkikh | Required: the MIT notice is reproduced in the shipped notice file |
| [Public Suffix List](https://publicsuffix.org/) | Read at build time to work out each feed's registrable host. Nothing from it ships | MPL 2.0 | Not shipped, so none needed |

The feeds themselves are fetched once per build. What the catalogue keeps from each (its title,
description, icon address, three headlines with their links, dates and lengths, its categories,
and a 256-number summary vector) is the publisher's own metadata, shown in the app only to
describe the feed and link to it, as any feed reader does.

## Left out on purpose

- **Kagi's `smallyt.txt` (YouTube) and `smallcomic.txt` (webcomics).** The user decided against
  video and comics. Feeds of either kind that arrive from the other lists are dropped too
  (`blocklist.txt`, and the YouTube and comic checks in `build_catalogue.py`).
- **ooh.directory and blogroll.org.** Excellent hand-curated directories, but neither states a
  licence. Not to be used without the maintainers' permission.
- **Feedspot.** Commercial, refuses bots, and its terms do not allow reuse.
- **Feedly, Inoreader, Feedbin.** Need an account or OAuth, and search through them would send
  readers' interests to a third party.
- **Adult content.** Excluded entirely, by keyword and by `media:rating`; see `blocklist.txt`.

## When adding a source

Add it to `sources.toml`, add a row above with its licence, and, if its licence asks for
attribution, add its notice to `app/src/main/assets/licenses/discovery-catalogue.txt`.
