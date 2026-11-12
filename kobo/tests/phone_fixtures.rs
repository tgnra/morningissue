//! The phone's golden fixtures (`docs/kobo-sync-fixtures/`, written by the app's
//! `BundleExportTest`) parse here without losing anything, so the two sides agree on the
//! formats a relay package carries: the manifest, edition bundles and the saved shelf.

use morningissue::model::{Block, Bundle, Manifest, SavedList};
use serde_json::Value;
use std::path::PathBuf;

fn fixture_path(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../docs/kobo-sync-fixtures").join(name)
}

fn fixture(name: &str) -> String {
    let path = fixture_path(name);
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

/// The phone's saved shelf parses whole: every listed article is there with all its
/// blocks, and one whose text the phone could not get is marked summary-only.
#[test]
fn phone_saved_list_parses() {
    // The phone side adds this fixture; until it is merged there is nothing to check.
    if !fixture_path("saved.json").exists() {
        eprintln!("no saved.json fixture yet");
        return;
    }
    let raw = fixture("saved.json");
    let saved: SavedList = serde_json::from_str(&raw).unwrap();
    let value: Value = serde_json::from_str(&raw).unwrap();
    assert!(!saved.version.is_empty());
    assert_eq!(saved.items().len(), saved.order.len(), "every id in order has its article");
    for (id, article) in &saved.articles {
        let stored = value["articles"][id]["blocks"].as_array().unwrap();
        assert_eq!(article.blocks.len(), stored.len(), "saved article {id} dropped a block");
        assert_eq!(article.summary_only(), stored.is_empty());
    }
    // The manifest says the same list is there.
    let manifest: Manifest = serde_json::from_str(&fixture("manifest.json")).unwrap();
    if let Some(info) = manifest.saved {
        assert_eq!(info.count as usize, saved.order.len());
        assert_eq!(info.version, saved.version);
    }
}

#[test]
fn phone_manifest_parses() {
    let manifest: Manifest = serde_json::from_str(&fixture("manifest.json")).unwrap();
    assert!(!manifest.db_id.is_empty());
    assert!(!manifest.editions.is_empty());
    assert!(manifest.editions.iter().all(|e| e.id > 0 && !e.version.is_empty()));
}

#[test]
fn phone_bundle_parses_every_block() {
    let raw = fixture("edition.json");
    let bundle: Bundle = serde_json::from_str(&raw).unwrap();
    let value: Value = serde_json::from_str(&raw).unwrap();

    assert!(!bundle.pages.is_empty());
    for item in bundle.pages.iter().flat_map(|p| &p.items) {
        assert!(bundle.articles.contains_key(&item.article_id.to_string()), "item {} has no article", item.article_id);
    }

    let mut kinds = std::collections::BTreeSet::new();
    for (id, article) in &bundle.articles {
        let stored = value["articles"][id]["blocks"].as_array().unwrap();
        assert_eq!(article.blocks.len(), stored.len(), "article {id} dropped a block the phone sent");
        for block in &article.blocks {
            kinds.insert(match block {
                Block::Paragraph(_) => "p",
                Block::Heading { .. } => "h",
                Block::Image { .. } => "img",
                Block::Quote { .. } => "quote",
                Block::List { .. } => "list",
                Block::Pre { .. } => "pre",
            });
        }
    }
    assert_eq!(kinds.into_iter().collect::<Vec<_>>(), ["h", "img", "list", "p", "pre", "quote"]);
}
