//! The quick-access dock: a row of app or folder slots. Screen-fixed; this is
//! only the slot model. The slot count is user-tunable (settings) and every slot
//! may hold either one app or a folder of apps.

use serde::{Deserialize, Serialize};

/// Slot-count bounds the settings screen offers.
pub const MIN_SLOTS: usize = 3;
pub const MAX_SLOTS: usize = 8;
pub const DEFAULT_SLOTS: usize = 5;

/// Last-resort name for a folder that arrived without one (hand-edited state).
const FALLBACK_FOLDER_NAME: &str = "Folder";

/// A named group of apps living in one dock slot.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Folder {
    pub name: String,
    #[serde(default)]
    pub apps: Vec<String>,
}

/// One dock slot's content. Serialized transparently so state written before
/// folders existed (a bare string per slot) still loads: a string is an app, an
/// object is a folder.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum DockItem {
    App(String),
    Folder(Folder),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(transparent)]
pub struct Dock {
    slots: Vec<Option<DockItem>>,
}

impl Dock {
    pub fn new() -> Self {
        Dock {
            slots: vec![None; DEFAULT_SLOTS],
        }
    }

    pub fn count(&self) -> usize {
        self.slots.len()
    }

    /// Grow or shrink to `n` slots (clamped to the allowed range). Content of
    /// slots that fall off the end is dropped.
    pub fn set_count(&mut self, n: usize) {
        self.slots.resize(n.clamp(MIN_SLOTS, MAX_SLOTS), None);
    }

    /// Restore from persisted data of any length: the slot count is clamped,
    /// every key may appear only once anywhere in the dock. A folder may be
    /// empty (it still holds a slot and offers its "add" tile).
    pub fn normalized(mut self) -> Self {
        let n = if self.slots.is_empty() {
            DEFAULT_SLOTS
        } else {
            self.slots.len().clamp(MIN_SLOTS, MAX_SLOTS)
        };
        self.slots.resize(n, None);
        let mut seen: Vec<String> = Vec::new();
        for slot in &mut self.slots {
            match slot {
                Some(DockItem::App(k)) => {
                    if seen.contains(k) {
                        *slot = None;
                    } else {
                        seen.push(k.clone());
                    }
                }
                Some(DockItem::Folder(f)) => {
                    f.apps.retain(|k| {
                        if seen.contains(k) {
                            false
                        } else {
                            seen.push(k.clone());
                            true
                        }
                    });
                    if f.name.trim().is_empty() {
                        f.name = FALLBACK_FOLDER_NAME.to_owned();
                    }
                }
                None => {}
            }
        }
        self
    }

    /// The app key of an app slot; `None` for folders and empty slots.
    pub fn get(&self, i: usize) -> Option<&str> {
        match self.item(i) {
            Some(DockItem::App(k)) => Some(k),
            _ => None,
        }
    }

    pub fn item(&self, i: usize) -> Option<&DockItem> {
        self.slots.get(i).and_then(|s| s.as_ref())
    }

    pub fn folder_at(&self, i: usize) -> Option<&Folder> {
        match self.item(i) {
            Some(DockItem::Folder(f)) => Some(f),
            _ => None,
        }
    }

    pub fn slots(&self) -> &[Option<DockItem>] {
        &self.slots
    }

    /// Slot holding `key`, whether as a standalone app or inside a folder.
    pub fn position_of(&self, key: &str) -> Option<usize> {
        self.slots.iter().position(|s| match s {
            Some(DockItem::App(k)) => k == key,
            Some(DockItem::Folder(f)) => f.apps.iter().any(|a| a == key),
            None => false,
        })
    }

    pub fn first_empty(&self) -> Option<usize> {
        self.slots.iter().position(|s| s.is_none())
    }

    /// Put `key` into `slot` as a plain app, replacing what was there. If `key`
    /// already lives elsewhere in the dock it moves (a folder member is pulled
    /// out of its folder).
    pub fn assign(&mut self, slot: usize, key: &str) -> bool {
        if slot >= self.slots.len() {
            return false;
        }
        if let Some(from) = self.position_of(key) {
            let is_app = matches!(self.slots.get(from), Some(Some(DockItem::App(_))));
            if is_app {
                self.slots.swap(from, slot);
            } else {
                self.remove_from_folder_at(from, key);
                self.slots[slot] = Some(DockItem::App(key.to_owned()));
            }
            return true;
        }
        self.slots[slot] = Some(DockItem::App(key.to_owned()));
        true
    }

    pub fn remove(&mut self, slot: usize) -> bool {
        match self.slots.get_mut(slot) {
            Some(s) if s.is_some() => {
                *s = None;
                true
            }
            _ => false,
        }
    }

    /// Drag-reorder: move the entry at `from` to `to`, shifting the ones between.
    pub fn move_slot(&mut self, from: usize, to: usize) -> bool {
        if from >= self.slots.len() || to >= self.slots.len() || from == to {
            return false;
        }
        let item = self.slots.remove(from);
        self.slots.insert(to, item);
        true
    }

    pub fn clear_missing(&mut self, mut exists: impl FnMut(&str) -> bool) {
        for slot in &mut self.slots {
            let drop = match slot {
                Some(DockItem::App(k)) => !exists(k),
                // A folder keeps its slot even when every app inside it is gone;
                // it simply shows its "add" tile again.
                Some(DockItem::Folder(f)) => {
                    f.apps.retain(|k| exists(k));
                    false
                }
                None => false,
            };
            if drop {
                *slot = None;
            }
        }
    }

    // ---- folders --------------------------------------------------------------

    /// Turn `slot` into a folder named `name`. An app slot moves its app inside;
    /// an empty slot becomes an empty folder that shows its "add" tile. No-op for
    /// a slot that already holds a folder.
    pub fn make_folder(&mut self, slot: usize, name: &str) -> bool {
        let Some(entry) = self.slots.get_mut(slot) else {
            return false;
        };
        match entry {
            Some(DockItem::App(key)) => {
                let key = key.clone();
                *entry = Some(DockItem::Folder(Folder {
                    name: name.to_owned(),
                    apps: vec![key],
                }));
                true
            }
            None => {
                *entry = Some(DockItem::Folder(Folder {
                    name: name.to_owned(),
                    apps: Vec::new(),
                }));
                true
            }
            Some(DockItem::Folder(_)) => false,
        }
    }

    /// Add `key` to the folder in `slot`, pulling it out of any other slot first.
    /// Returns false when the slot is not a folder or the app is already there.
    pub fn move_to_folder(&mut self, slot: usize, key: &str) -> bool {
        if !matches!(self.slots.get(slot), Some(Some(DockItem::Folder(_)))) {
            return false;
        }
        if let Some(from) = self.position_of(key) {
            if from == slot {
                return false; // already in this folder
            }
            let is_app = matches!(self.slots.get(from), Some(Some(DockItem::App(_))));
            if is_app {
                self.slots[from] = None;
            } else {
                self.remove_from_folder_at(from, key);
            }
        }
        if let Some(Some(DockItem::Folder(f))) = self.slots.get_mut(slot) {
            f.apps.push(key.to_owned());
            return true;
        }
        false
    }

    /// Remove `key` from the folder in `slot`. The folder stays (possibly empty,
    /// showing its "add" tile).
    pub fn remove_from_folder(&mut self, slot: usize, key: &str) -> bool {
        match self.slots.get_mut(slot) {
            Some(Some(DockItem::Folder(f))) => {
                let before = f.apps.len();
                f.apps.retain(|a| a != key);
                f.apps.len() != before
            }
            _ => false,
        }
    }

    /// Drag-reorder inside the folder in `slot`: move the app at `from` to `to`,
    /// shifting the ones between. False when the slot is not a folder, an index is
    /// out of range, or nothing would change.
    pub fn move_in_folder(&mut self, slot: usize, from: usize, to: usize) -> bool {
        let Some(Some(DockItem::Folder(f))) = self.slots.get_mut(slot) else {
            return false;
        };
        let n = f.apps.len();
        if from >= n || to >= n || from == to {
            return false;
        }
        let key = f.apps.remove(from);
        f.apps.insert(to, key);
        true
    }

    pub fn rename_folder(&mut self, slot: usize, name: &str) -> bool {
        match self.slots.get_mut(slot) {
            Some(Some(DockItem::Folder(f))) => {
                f.name = name.to_owned();
                true
            }
            _ => false,
        }
    }

    /// Pull `key` out of the folder in `slot`, leaving the folder in place even
    /// when it becomes empty.
    fn remove_from_folder_at(&mut self, slot: usize, key: &str) {
        if let Some(Some(DockItem::Folder(f))) = self.slots.get_mut(slot) {
            f.apps.retain(|a| a != key);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn folder(name: &str, apps: &[&str]) -> Option<DockItem> {
        Some(DockItem::Folder(Folder {
            name: name.into(),
            apps: apps.iter().map(|s| (*s).to_string()).collect(),
        }))
    }

    fn app(key: &str) -> Option<DockItem> {
        Some(DockItem::App(key.into()))
    }

    #[test]
    fn default_has_five_slots() {
        assert_eq!(Dock::new().count(), 5);
        assert_eq!(Dock::default().normalized().count(), 5);
    }

    #[test]
    fn count_is_clamped() {
        let mut d = Dock::new();
        d.set_count(2);
        assert_eq!(d.count(), MIN_SLOTS);
        d.set_count(99);
        assert_eq!(d.count(), MAX_SLOTS);
        d.set_count(7);
        assert_eq!(d.count(), 7);
    }

    #[test]
    fn shrinking_drops_content_growing_empties() {
        let mut d = Dock::new();
        d.assign(4, "e");
        d.set_count(3);
        assert_eq!(d.count(), 3);
        assert_eq!(d.position_of("e"), None);
        d.set_count(6);
        assert_eq!(d.first_empty(), Some(0));
        assert_eq!(d.count(), 6);
    }

    #[test]
    fn assign_and_replace() {
        let mut d = Dock::new();
        assert!(d.assign(2, "a"));
        assert!(d.assign(2, "b"));
        assert_eq!(d.get(2), Some("b"));
        assert_eq!(d.position_of("a"), None);
        assert!(!d.assign(9, "x"));
    }

    #[test]
    fn assigning_pinned_app_moves_it() {
        let mut d = Dock::new();
        d.assign(0, "a");
        d.assign(3, "b");
        d.assign(3, "a");
        assert_eq!(d.get(3), Some("a"));
        assert_eq!(d.get(0), Some("b"));
    }

    #[test]
    fn remove_and_first_empty() {
        let mut d = Dock::new();
        d.assign(0, "a");
        d.assign(1, "b");
        assert_eq!(d.first_empty(), Some(2));
        assert!(d.remove(0));
        assert!(!d.remove(0));
        assert_eq!(d.first_empty(), Some(0));
    }

    #[test]
    fn reorder() {
        let mut d = Dock::new();
        for (i, k) in ["a", "b", "c"].iter().enumerate() {
            d.assign(i, k);
        }
        assert!(d.move_slot(0, 2));
        assert_eq!(d.get(0), Some("b"));
        assert_eq!(d.get(1), Some("c"));
        assert_eq!(d.get(2), Some("a"));
        assert!(!d.move_slot(1, 1));
        assert!(!d.move_slot(0, 9));
    }

    #[test]
    fn create_and_rename_folder() {
        let mut d = Dock::new();
        d.assign(1, "a");
        assert!(d.make_folder(1, "Games"));
        assert_eq!(d.folder_at(1).map(|f| f.name.as_str()), Some("Games"));
        assert_eq!(d.folder_at(1).unwrap().apps, vec!["a"]);
        assert_eq!(d.get(1), None);
        assert!(d.rename_folder(1, "Fun"));
        assert_eq!(d.folder_at(1).map(|f| f.name.as_str()), Some("Fun"));
        // A folder is not an app slot and cannot be made into a folder again.
        assert!(!d.make_folder(1, "X"));
        assert!(!d.rename_folder(0, "X"));
    }

    #[test]
    fn add_and_remove_folder_apps() {
        let mut d = Dock::new();
        d.assign(0, "a");
        d.assign(1, "b");
        d.make_folder(0, "F");
        assert!(d.move_to_folder(0, "b"));
        assert_eq!(d.folder_at(0).unwrap().apps, vec!["a", "b"]);
        assert_eq!(d.get(1), None, "moved app leaves its old slot");
        // Already inside: no duplicate.
        assert!(!d.move_to_folder(0, "b"));
        assert!(d.remove_from_folder(0, "a"));
        assert_eq!(d.folder_at(0).unwrap().apps, vec!["b"]);
        assert!(d.remove_from_folder(0, "b"));
        assert_eq!(
            d.folder_at(0).unwrap().apps,
            Vec::<String>::new(),
            "an emptied folder stays, ready for more apps"
        );
        assert!(d.item(0).is_some(), "the folder keeps its slot");
    }

    #[test]
    fn folder_key_counts_as_present() {
        let mut d = Dock::new();
        d.assign(0, "a");
        d.make_folder(0, "F");
        assert_eq!(d.position_of("a"), Some(0));
        assert_eq!(d.first_empty(), Some(1));
        // Pulling the app out of a folder replaces the target slot with the app.
        d.assign(2, "a");
        assert_eq!(d.get(2), Some("a"));
        assert_eq!(
            d.folder_at(0).unwrap().apps,
            Vec::<String>::new(),
            "the source folder stays, now empty"
        );
    }

    #[test]
    fn normalize_fixes_bad_data() {
        let d = Dock {
            slots: vec![
                app("a"),
                app("a"),
                None,
                None,
                None,
                app("z"),
            ],
        }
        .normalized();
        assert_eq!(d.count(), 6, "a 6-slot dock is within range and kept");
        assert_eq!(d.get(1), None);
        let short = Dock {
            slots: vec![app("a")],
        }
        .normalized();
        assert_eq!(short.count(), MIN_SLOTS, "a too-short dock grows to the minimum");
    }

    #[test]
    fn normalize_dedups_and_keeps_empty_folders() {
        let d = Dock {
            slots: vec![
                app("a"),
                folder("F", &["a", "b"]),
                folder("G", &[]),
                folder("", &["c"]),
            ],
        }
        .normalized();
        assert_eq!(d.count(), 4, "a 4-slot dock is within range and kept");
        assert_eq!(d.get(0), Some("a"));
        assert_eq!(d.folder_at(1).unwrap().apps, vec!["b"], "duplicate dropped");
        assert_eq!(
            d.folder_at(2).unwrap().apps,
            Vec::<String>::new(),
            "an empty folder is kept"
        );
        assert_eq!(d.folder_at(3).map(|f| f.name.as_str()), Some(FALLBACK_FOLDER_NAME));
    }

    #[test]
    fn create_folder_in_empty_slot() {
        let mut d = Dock::new();
        assert!(d.make_folder(2, "New"));
        let f = d.folder_at(2).unwrap();
        assert_eq!(f.name, "New");
        assert!(f.apps.is_empty());
        assert_eq!(d.first_empty(), Some(0), "the folder occupies its slot");
    }

    #[test]
    fn move_in_folder_reorders() {
        let mut d = Dock::new();
        d.assign(0, "a");
        d.make_folder(0, "F");
        d.move_to_folder(0, "b");
        d.move_to_folder(0, "c");
        assert_eq!(d.folder_at(0).unwrap().apps, vec!["a", "b", "c"]);
        assert!(d.move_in_folder(0, 2, 0));
        assert_eq!(d.folder_at(0).unwrap().apps, vec!["c", "a", "b"]);
        assert!(d.move_in_folder(0, 0, 2));
        assert_eq!(d.folder_at(0).unwrap().apps, vec!["a", "b", "c"]);
        assert!(!d.move_in_folder(0, 1, 1), "same index is a no-op");
        assert!(!d.move_in_folder(0, 9, 0), "out of range is a no-op");
        assert!(!d.move_in_folder(4, 0, 1), "an empty slot is not a folder");
    }

    #[test]
    fn empty_folder_round_trips() {
        let mut d = Dock::new();
        d.make_folder(2, "New");
        let s = serde_json::to_string(&d).unwrap();
        let parsed: Dock = serde_json::from_str(&s).unwrap();
        let back = parsed.normalized();
        let f = back.folder_at(2).expect("an empty folder survives a save/load");
        assert_eq!(f.name, "New");
        assert!(f.apps.is_empty());
    }

    #[test]
    fn clear_missing_drops_uninstalled() {
        let mut d = Dock::new();
        d.assign(0, "a");
        d.assign(1, "b");
        d.assign(2, "c");
        d.make_folder(2, "F");
        d.assign(3, "gone");
        d.make_folder(3, "G");
        d.clear_missing(|k| k == "a" || k == "c");
        assert_eq!(d.get(0), Some("a"));
        assert_eq!(d.get(1), None);
        assert_eq!(d.folder_at(2).unwrap().apps, vec!["c"]);
        assert_eq!(
            d.folder_at(3).unwrap().apps,
            Vec::<String>::new(),
            "a folder whose apps all vanished is kept"
        );
    }

    #[test]
    fn serde_round_trip() {
        let mut d = Dock::new();
        d.assign(1, "x");
        d.assign(2, "y");
        d.make_folder(2, "F");
        d.set_count(6);
        let s = serde_json::to_string(&d).unwrap();
        let back: Dock = serde_json::from_str(&s).unwrap();
        assert_eq!(back, d);
    }

    #[test]
    fn old_plain_string_dock_still_loads() {
        let back: Dock = serde_json::from_str(r#"["com.a",null,"com.b",null,null]"#).unwrap();
        assert_eq!(back.count(), 5);
        assert_eq!(back.get(0), Some("com.a"));
        assert_eq!(back.get(2), Some("com.b"));
    }
}
