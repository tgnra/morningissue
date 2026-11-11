//! The protocol's JSON documents (`docs/kobo-sync.md`). Every reader here ignores fields it does
//! not know and fills in fields that are missing, so a newer phone does not break an older Kobo.

use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;

#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Beacon {
    pub app: String,
    pub v: i64,
    pub port: u16,
    pub name: String,
    pub db_id: String,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PairResponse {
    pub token: String,
    pub db_id: String,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Manifest {
    pub v: i64,
    pub db_id: String,
    pub acked_seq: i64,
    pub editions: Vec<ManifestEdition>,
    /// Left out by a phone that has no saved shelf to offer.
    pub saved: Option<SavedInfo>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct SavedInfo {
    pub count: i64,
    pub version: String,
}

/// `GET /v1/saved`, stored as `data/saved.json`.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SavedList {
    pub version: String,
    /// Newest saved first.
    pub order: Vec<i64>,
    pub articles: BTreeMap<String, Article>,
}

impl SavedList {
    pub fn article(&self, id: i64) -> Option<&Article> {
        self.articles.get(&id.to_string())
    }

    pub fn article_mut(&mut self, id: i64) -> Option<&mut Article> {
        self.articles.get_mut(&id.to_string())
    }

    /// The listed articles in the phone's order; an ID with no article is skipped.
    pub fn items(&self) -> Vec<(i64, &Article)> {
        self.order.iter().filter_map(|id| Some((*id, self.article(*id)?))).collect()
    }

    pub fn image_urls(&self) -> Vec<String> {
        let mut urls: Vec<String> = Vec::new();
        for (_, a) in self.items() {
            for u in a.image_urls() {
                if !urls.contains(&u) {
                    urls.push(u);
                }
            }
        }
        urls
    }
}

#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct ManifestEdition {
    pub id: i64,
    pub date: String,
    pub kind: String,
    pub number: i64,
    pub release_at: Option<String>,
    pub version: String,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct EventsResponse {
    pub acked_seq: i64,
}

/// `GET /v1/editions/{id}`.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Bundle {
    pub id: i64,
    pub date: String,
    pub kind: String,
    pub number: i64,
    pub length: String,
    pub release_at: Option<String>,
    pub finished_at: Option<String>,
    pub pages: Vec<BundlePage>,
    pub articles: BTreeMap<String, Article>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BundlePage {
    pub page: i64,
    pub items: Vec<Item>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Item {
    pub article_id: i64,
    pub slot: i64,
    pub kind: String,
    pub section: Option<String>,
    pub reason: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Article {
    pub feed_title: String,
    pub title: String,
    pub author: Option<String>,
    pub url: Option<String>,
    pub published_at: Option<String>,
    pub summary: Option<String>,
    pub image_url: Option<String>,
    pub word_count: i64,
    #[serde(deserialize_with = "blocks")]
    pub blocks: Vec<Block>,
    pub engagement: Engagement,
}

impl Article {
    /// The lead picture and every `img` block, in that order.
    pub fn image_urls(&self) -> Vec<String> {
        let mut urls = Vec::new();
        if let Some(u) = self.image_url.as_ref().filter(|u| !u.is_empty()) {
            urls.push(u.clone());
        }
        for b in &self.blocks {
            if let Block::Image { url, .. } = b {
                if !url.is_empty() && !urls.contains(url) {
                    urls.push(url.clone());
                }
            }
        }
        urls
    }

    /// True when the phone could not store the article's text, so only the summary is here.
    pub fn summary_only(&self) -> bool {
        self.blocks.is_empty()
    }
}

#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Engagement {
    pub opened_at: Option<String>,
    pub read_fraction: f64,
    pub read_at: Option<String>,
    pub saved_at: Option<String>,
    pub liked_at: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq)]
#[serde(default)]
pub struct RichText {
    pub text: String,
    #[serde(deserialize_with = "spans")]
    pub spans: Vec<Span>,
}

impl RichText {
    pub fn plain(text: impl Into<String>) -> RichText {
        RichText { text: text.into(), spans: Vec::new() }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum SpanStyle {
    #[serde(rename = "b")]
    Bold,
    #[serde(rename = "i")]
    Italic,
    #[serde(rename = "code")]
    Code,
    #[serde(rename = "a")]
    Link,
    #[serde(rename = "s")]
    Strike,
    #[serde(rename = "sup")]
    Sup,
    #[serde(rename = "sub")]
    Sub,
}

/// `[start, end)` in UTF-16 code units, as Kotlin strings count them.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Span {
    pub start: usize,
    pub end: usize,
    pub style: SpanStyle,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub href: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Block {
    Paragraph(RichText),
    Heading { level: u8, text: RichText },
    Image { url: String, alt: Option<String>, caption: Option<RichText>, width: Option<u32>, height: Option<u32> },
    Quote { paragraphs: Vec<RichText>, citation: Option<RichText> },
    List { ordered: bool, items: Vec<RichText> },
    Pre { text: String },
}

fn rich(v: Option<&Value>) -> Option<RichText> {
    let v = v?;
    match v {
        Value::String(s) => Some(RichText::plain(s.clone())),
        Value::Object(_) => serde_json::from_value(v.clone()).ok(),
        _ => None,
    }
}

impl Block {
    /// One stored block, or None for a type or shape this reader does not know.
    pub fn from_value(v: &Value) -> Option<Block> {
        let t = v.get("t")?.as_str()?;
        let s = |k: &str| v.get(k).and_then(Value::as_str).map(str::to_owned);
        let n = |k: &str| v.get(k).and_then(Value::as_u64).map(|x| x.min(u32::MAX as u64) as u32);
        Some(match t {
            "p" => Block::Paragraph(rich(v.get("text"))?),
            "h" => Block::Heading { level: v.get("level").and_then(Value::as_u64).unwrap_or(2).clamp(1, 6) as u8, text: rich(v.get("text"))? },
            "img" => Block::Image { url: s("url")?, alt: s("alt"), caption: rich(v.get("caption")), width: n("width"), height: n("height") },
            "quote" => Block::Quote {
                paragraphs: v.get("paragraphs").and_then(Value::as_array).map(|a| a.iter().filter_map(|p| rich(Some(p))).collect()).unwrap_or_default(),
                citation: rich(v.get("citation")),
            },
            "list" => Block::List {
                ordered: v.get("ordered").and_then(Value::as_bool).unwrap_or(false),
                items: v.get("items").and_then(Value::as_array).map(|a| a.iter().filter_map(|p| rich(Some(p))).collect()).unwrap_or_default(),
            },
            "pre" => Block::Pre { text: s("text")? },
            _ => return None,
        })
    }
}

fn blocks<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<Block>, D::Error> {
    let v = Value::deserialize(d)?;
    Ok(match v {
        Value::Array(items) => items.iter().filter_map(Block::from_value).collect(),
        // A phone that sent the stored JSON as a string.
        Value::String(s) => serde_json::from_str::<Vec<Value>>(&s).map(|a| a.iter().filter_map(Block::from_value).collect()).unwrap_or_default(),
        _ => Vec::new(),
    })
}

fn spans<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<Span>, D::Error> {
    let v = Value::deserialize(d)?;
    Ok(match v {
        Value::Array(items) => items.into_iter().filter_map(|s| serde_json::from_value(s).ok()).collect(),
        _ => Vec::new(),
    })
}

impl Serialize for Block {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        use serde_json::json;
        let v = match self {
            Block::Paragraph(text) => json!({"t": "p", "text": text}),
            Block::Heading { level, text } => json!({"t": "h", "level": level, "text": text}),
            Block::Image { url, alt, caption, width, height } => {
                json!({"t": "img", "url": url, "alt": alt, "caption": caption, "width": width, "height": height})
            }
            Block::Quote { paragraphs, citation } => json!({"t": "quote", "paragraphs": paragraphs, "citation": citation}),
            Block::List { ordered, items } => json!({"t": "list", "ordered": ordered, "items": items}),
            Block::Pre { text } => json!({"t": "pre", "text": text}),
        };
        v.serialize(s)
    }
}

// Blocks are written back out in the protocol's own shape so stored bundles round-trip.
impl<'de> Deserialize<'de> for Block {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let v = Value::deserialize(d)?;
        Block::from_value(&v).ok_or_else(|| serde::de::Error::custom("unknown block"))
    }
}

/// What a page of the edition is, decided by its first item as the app does (`EditionPages`).
#[derive(Clone, Debug, PartialEq)]
pub enum PageKind {
    Front,
    Top,
    Section(String),
    Brief,
    Archive,
    Away,
    ReadingList,
    Back,
    /// A kind this reader does not know; shown as a plain list of stories.
    Other(String),
}

/// One page of the edition as the reader turns it.
#[derive(Clone, Debug)]
pub struct EditionPage {
    /// 1-based position, the number the app records page views under.
    pub number: i64,
    pub kind: PageKind,
    /// Article IDs in slot order. On the front page the lead comes first.
    pub stories: Vec<i64>,
    /// The lead, on the front page.
    pub lead: Option<i64>,
}

pub const GENERAL_SECTION: &str = "General";

impl Bundle {
    pub fn article(&self, id: i64) -> Option<&Article> {
        self.articles.get(&id.to_string())
    }

    pub fn article_mut(&mut self, id: i64) -> Option<&mut Article> {
        self.articles.get_mut(&id.to_string())
    }

    /// The pages in the app's order: printed items grouped by `page` and sorted by `slot`, then
    /// the back page with the index, which is always last.
    pub fn edition_pages(&self) -> Vec<EditionPage> {
        let mut printed: BTreeMap<i64, Vec<&Item>> = BTreeMap::new();
        let mut index: Vec<&Item> = Vec::new();
        for page in &self.pages {
            for item in &page.items {
                if item.kind == "INDEX" {
                    index.push(item);
                } else {
                    printed.entry(page.page).or_default().push(item);
                }
            }
        }
        let mut pages = Vec::new();
        for (_, mut items) in printed {
            items.sort_by_key(|i| i.slot);
            let number = pages.len() as i64 + 1;
            let first = items[0];
            let ids: Vec<i64> = items.iter().map(|i| i.article_id).collect();
            let kind = match first.kind.as_str() {
                "LEAD" | "FRONT" => PageKind::Front,
                "TOP" => PageKind::Top,
                "SECTION" => PageKind::Section(first.section.clone().unwrap_or_else(|| GENERAL_SECTION.into())),
                "BRIEF" => PageKind::Brief,
                "ARCHIVE" => PageKind::Archive,
                "AWAY" => PageKind::Away,
                "READING_LIST" => PageKind::ReadingList,
                other => PageKind::Other(other.to_string()),
            };
            let (lead, stories) = if kind == PageKind::Front {
                let lead = items.iter().find(|i| i.kind == "LEAD").map(|i| i.article_id);
                let mut rest: Vec<i64> = items.iter().filter(|i| Some(i.article_id) != lead || i.kind != "LEAD").map(|i| i.article_id).collect();
                if let Some(l) = lead {
                    rest.retain(|&x| x != l);
                    rest.insert(0, l);
                }
                (lead, rest)
            } else {
                (None, ids)
            };
            pages.push(EditionPage { number, kind, stories, lead });
        }
        index.sort_by_key(|i| i.slot);
        pages.push(EditionPage { number: pages.len() as i64 + 1, kind: PageKind::Back, stories: index.iter().map(|i| i.article_id).collect(), lead: None });
        pages
    }

    /// Every image URL the bundle refers to: lead images and `img` blocks.
    pub fn image_urls(&self) -> Vec<String> {
        let mut urls: Vec<String> = self.articles.values().flat_map(Article::image_urls).collect();
        urls.sort();
        urls.dedup();
        urls
    }

    /// The same URLs in the order a reader meets them: the front page's lead picture first
    /// (it is on the cover), then page by page, each article's lead before its body.
    pub fn image_urls_in_reading_order(&self) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        let mut push = |u: String| {
            if !out.contains(&u) {
                out.push(u);
            }
        };
        let pages = self.edition_pages();
        let ids: Vec<i64> = pages.iter().flat_map(|p| p.stories.iter().copied()).collect();
        for id in &ids {
            if let Some(u) = self.article(*id).and_then(|a| a.image_url.clone()).filter(|u| !u.is_empty()) {
                push(u);
            }
        }
        for id in &ids {
            for u in self.article(*id).map(Article::image_urls).unwrap_or_default() {
                push(u);
            }
        }
        for u in self.image_urls() {
            push(u);
        }
        out
    }

    /// Stories printed in the edition, the back page's index left out.
    pub fn story_count(&self) -> usize {
        self.pages.iter().flat_map(|p| &p.items).filter(|i| i.kind != "INDEX").count()
    }

    /// "Morning edition", "Evening edition", "Extra edition".
    pub fn kind_label(&self) -> String {
        kind_label(&self.kind)
    }
}

pub fn kind_label(kind: &str) -> String {
    match kind {
        "MORNING" => "Morning edition".into(),
        "EVENING" => "Evening edition".into(),
        "EXTRA" => "Extra edition".into(),
        other if !other.is_empty() => {
            let mut s = other.to_lowercase();
            if let Some(c) = s.get_mut(0..1) {
                c.make_ascii_uppercase();
            }
            format!("{s} edition")
        }
        _ => "Edition".into(),
    }
}

/// Estimated minutes to read, as the app's `ReadingTime` (230 words a minute, at least 1).
pub fn read_minutes(word_count: i64) -> i64 {
    ((word_count.max(0) + 229) / 230).max(1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tolerant_bundle() {
        let json = r#"{
          "id": 7, "date": "2026-11-11", "kind": "MORNING", "number": 7, "futureField": {"x": 1},
          "pages": [
            {"page": 2, "items": [{"articleId": 3, "slot": 1, "kind": "SECTION", "section": "Tech"}, {"articleId": 2, "slot": 0, "kind": "SECTION", "section": "Tech"}]},
            {"page": 1, "items": [{"articleId": 5, "slot": 1, "kind": "FRONT"}, {"articleId": 1, "slot": 0, "kind": "LEAD", "section": null, "reason": "r"}]},
            {"page": 3, "items": [{"articleId": 9, "slot": 0, "kind": "INDEX"}]}
          ],
          "articles": {
            "1": {"feedTitle": "F", "title": "T", "wordCount": 10, "unknown": true,
                  "blocks": [{"t": "p", "text": {"text": "Hi", "spans": [{"start": 0, "end": 2, "style": "b"}, {"start": 0, "end": 1, "style": "blink"}]}},
                             {"t": "video", "src": "x"}, {"t": "h", "level": 9, "text": {"text": "H"}},
                             {"t": "img", "url": "http://i/1.webp", "width": 10}],
                  "engagement": {"openedAt": null, "readFraction": 0.5, "newThing": 1}}
          }
        }"#;
        let b: Bundle = serde_json::from_str(json).unwrap();
        let a = b.article(1).unwrap();
        assert_eq!(a.blocks.len(), 3);
        assert_eq!(a.blocks[1], Block::Heading { level: 6, text: RichText::plain("H") });
        if let Block::Paragraph(t) = &a.blocks[0] {
            assert_eq!(t.spans.len(), 1);
        } else {
            panic!()
        }
        let pages = b.edition_pages();
        assert_eq!(pages.len(), 3);
        assert_eq!(pages[0].kind, PageKind::Front);
        assert_eq!(pages[0].lead, Some(1));
        assert_eq!(pages[0].stories, vec![1, 5]);
        assert_eq!(pages[1].kind, PageKind::Section("Tech".into()));
        assert_eq!(pages[1].stories, vec![2, 3]);
        assert_eq!(pages[2].kind, PageKind::Back);
        assert_eq!(pages[2].stories, vec![9]);
        assert_eq!(b.image_urls(), vec!["http://i/1.webp".to_string()]);
        // Round trip through our own storage.
        let again: Bundle = serde_json::from_str(&serde_json::to_string(&b).unwrap()).unwrap();
        assert_eq!(again.article(1).unwrap().blocks, a.blocks);
    }
}
