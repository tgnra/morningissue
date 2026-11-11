//! Morning Issue for Kobo: syncs editions from the phone and reads them on e-ink.
//!
//! The reader draws into an 8-bit grey [`canvas::Canvas`] and talks to the hardware only
//! through [`device::Display`], [`device::Input`] and [`device::Clock`], so the same screens
//! run on a Kobo ([`kobo`]) and on a desktop that writes PNGs ([`desktop`]).

pub mod app;
pub mod canvas;
pub mod config;
pub mod desktop;
pub mod device;
pub mod events;
pub mod fonts;
pub mod gesture;
pub mod http;
pub mod images;
pub mod kobo;
pub mod layout;
pub mod light;
pub mod model;
pub mod net;
pub mod profile;
pub mod screens;
pub mod settings;
pub mod store;
pub mod sync;
pub mod text;
pub mod time;
pub mod ui;
