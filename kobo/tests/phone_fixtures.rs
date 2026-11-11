//! The phone's golden fixtures (`docs/kobo-sync-fixtures/`, written by the app's
//! `BundleExportTest`) parse here without losing anything, so the two sides agree on the wire.

use morningissue::model::{Block, Bundle, Manifest};
use serde_json::Value;
use std::path::PathBuf;

fn fixture(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../docs/kobo-sync-fixtures").join(name);
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
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
