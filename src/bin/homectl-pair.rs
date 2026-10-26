//! `homectl-pair`: a normal window that shows how to pair a phone with this machine. It shows the QR code and the link on request
//! (the link carries the access token, so it is hidden until the button is pressed and hides again after two minutes), copies the
//! link, and lists the phones that asked to pair with a 6-digit code. It is an ordinary toplevel with the theme's own titlebar and
//! colours, and a fixed size, so it does not jump when the QR code appears. The only fixed colour is the white card behind the QR
//! code, because phone cameras need the contrast; it sits in the theme's card style. Build with `cargo build --features gui --bin homectl-pair`. Escape closes it.
//! niri floats windows that have a fixed size; to be sure, add a window-rule with app-id "org.homectl.Pair" and open-floating true.

#[path = "../pairlink.rs"]
mod pairlink;

use gtk::{gdk, glib, prelude::*};
use serde_json::Value;
use std::{
    cell::{Cell, RefCell},
    rc::Rc,
};

/// How long the QR code and link stay on screen after they are shown.
const REVEAL_SECS: u32 = 120;
/// Fixed sizes: the window never resizes, because the parts that change sit in areas of these heights.
const WIDTH: i32 = 420;
const QR_AREA: i32 = 300;
const QR_SIZE: i32 = 240;
const LIST_HEIGHT: i32 = 150;

struct Ui {
    port: u16,
    token: Option<String>,
    stack: gtk::Stack,
    qr: gtk::DrawingArea,
    /// The QR code as modules (true = dark), drawn by `qr`.
    matrix: RefCell<Option<Vec<Vec<bool>>>>,
    link_label: gtk::Label,
    show: gtk::Button,
    pending: gtk::ListBox,
    status: gtk::Label,
    /// Changed on every reveal, so an old timer does not hide a newer reveal early.
    reveal_gen: Cell<u32>,
}

fn main() -> glib::ExitCode {
    let app = gtk::Application::builder().application_id("org.homectl.Pair").build();
    app.connect_activate(build_ui);
    app.run()
}

/// The token from `~/.config/homectl/token` (the daemon writes it there and reads it on start).
fn read_token() -> Option<String> {
    let home = std::env::var("HOME").ok()?;
    let t = std::fs::read_to_string(std::path::Path::new(&home).join(".config/homectl/token")).ok()?;
    Some(t.trim().to_string()).filter(|t| !t.is_empty())
}

/// The desktop's GTK 4 theme: `gtk-4.0/gtk.css` in the user's config directory, the same file the other GTK 4 programs use.
/// Loaded here as well, so the window gets the theme whether or not GTK itself picks the file up.
fn load_theme() {
    let config = std::env::var_os("XDG_CONFIG_HOME").map(std::path::PathBuf::from).or_else(|| {
        std::env::var_os("HOME").map(|h| std::path::Path::new(&h).join(".config"))
    });
    let Some(path) = config.map(|c| c.join("gtk-4.0/gtk.css")).filter(|p| p.is_file()) else { return };
    let Some(display) = gdk::Display::default() else { return };
    let provider = gtk::CssProvider::new();
    provider.load_from_path(&path);
    gtk::style_context_add_provider_for_display(&display, &provider, gtk::STYLE_PROVIDER_PRIORITY_USER);
    // GTK's own window rules win over a bare @define-color, so the palette's names are applied to the parts of this window
    let colors = gtk::CssProvider::new();
    colors.load_from_data(THEME_RULES);
    gtk::style_context_add_provider_for_display(&display, &colors, gtk::STYLE_PROVIDER_PRIORITY_USER + 1);
}

/// Colours are the palette's own names from `gtk.css`, never values written here.
const THEME_RULES: &str = "
window, headerbar { background-color: @window_bg_color; color: @window_fg_color; }
.card { background-color: @card_bg_color; color: @card_fg_color; }
list, .boxed-list { background-color: @view_bg_color; color: @view_fg_color; }
";

fn build_ui(app: &gtk::Application) {
    let port: u16 = std::env::var("HOMECTL_PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(7878);
    load_theme();

    // not resizable: GTK then asks for a fixed size, which compositors float; the window keeps the size below
    let window = gtk::ApplicationWindow::builder().application(app).title("homectl pairing").resizable(false).build();

    let heading = gtk::Label::builder().label("Pair a phone").xalign(0.0).css_classes(["title-2"]).build();
    let hint = gtk::Label::builder()
        .label("Scan the code in the homectl app, or paste the link. The link and the code are a password: show them only to a phone you own.")
        .wrap(true)
        .xalign(0.0)
        .css_classes(["dim-label"])
        .build();

    // the QR area has the same size whatever is shown in it, so the window keeps its size
    let qr = gtk::DrawingArea::builder().content_width(QR_SIZE).content_height(QR_SIZE).halign(gtk::Align::Center).valign(gtk::Align::Center).build();
    let link_label = gtk::Label::builder().wrap(true).wrap_mode(gtk::pango::WrapMode::Char).selectable(true).xalign(0.0).build();
    let link_scroll = gtk::ScrolledWindow::builder().min_content_height(56).max_content_height(56).child(&link_label).build();
    let shown = gtk::Box::new(gtk::Orientation::Vertical, 8);
    // the theme's card frames the code; the code itself stays dark on white (phone cameras need that contrast)
    let card = gtk::Box::builder().css_classes(["card"]).halign(gtk::Align::Center).build();
    qr.set_margin_top(12);
    qr.set_margin_bottom(12);
    qr.set_margin_start(12);
    qr.set_margin_end(12);
    card.append(&qr);
    shown.append(&card);
    shown.append(&link_scroll);

    let hidden = gtk::Box::new(gtk::Orientation::Vertical, 0);
    hidden.set_valign(gtk::Align::Center);
    hidden.append(&gtk::Label::builder().label("Hidden. Press Show to display the QR code and link.").css_classes(["dim-label"]).wrap(true).build());

    let stack = gtk::Stack::builder()
        .transition_type(gtk::StackTransitionType::Crossfade)
        .transition_duration(200)
        .hhomogeneous(true)
        .vhomogeneous(true)
        .height_request(QR_AREA)
        .build();
    stack.add_named(&hidden, Some("hidden"));
    stack.add_named(&shown, Some("shown"));

    let show = gtk::Button::with_label("Show");
    let copy = gtk::Button::with_label("Copy link");
    let close = gtk::Button::with_label("Close");
    let buttons = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    for b in [&show, &copy, &close] {
        b.set_hexpand(true);
        buttons.append(b);
    }

    let pending_heading = gtk::Label::builder().label("Phones asking to pair").xalign(0.0).css_classes(["title-4"]).build();
    let pending = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list"]).build();
    let pending_scroll = gtk::ScrolledWindow::builder().min_content_height(LIST_HEIGHT).max_content_height(LIST_HEIGHT).child(&pending).build();
    let status = gtk::Label::builder().xalign(0.0).wrap(true).css_classes(["dim-label"]).build();
    status.set_size_request(-1, 40);

    let column = gtk::Box::new(gtk::Orientation::Vertical, 12);
    column.set_size_request(WIDTH, -1);
    column.set_margin_top(20);
    column.set_margin_bottom(20);
    column.set_margin_start(20);
    column.set_margin_end(20);
    column.append(&heading);
    column.append(&hint);
    column.append(&stack);
    column.append(&buttons);
    column.append(&pending_heading);
    column.append(&pending_scroll);
    column.append(&status);
    window.set_child(Some(&column));

    let ui = Rc::new(Ui {
        port,
        token: read_token(),
        stack,
        qr: qr.clone(),
        matrix: RefCell::new(None),
        link_label,
        show: show.clone(),
        pending,
        status,
        reveal_gen: Cell::new(0),
    });

    let u = ui.clone();
    qr.set_draw_func(move |_, cr, w, h| {
        if let Some(m) = u.matrix.borrow().as_ref() {
            draw_qr(cr, m, w as f64, h as f64);
        }
    });
    let u = ui.clone();
    show.connect_clicked(move |_| {
        if u.matrix.borrow().is_some() {
            hide(&u);
        } else if let Some(link) = fetch_link(&u) {
            reveal(&u, &link);
        }
    });
    let u = ui.clone();
    copy.connect_clicked(move |_| {
        if let Some(link) = fetch_link(&u) {
            if let Some(display) = gdk::Display::default() {
                display.clipboard().set_text(&link);
                u.status.set_label("Link copied. It is a password: do not paste it anywhere public.");
            }
        }
    });
    let w = window.clone();
    close.connect_clicked(move |_| w.close());

    let keys = gtk::EventControllerKey::new();
    let w = window.clone();
    keys.connect_key_pressed(move |_, key, _, _| {
        if key == gdk::Key::Escape {
            w.close();
            return glib::Propagation::Stop;
        }
        glib::Propagation::Proceed
    });
    window.add_controller(keys);

    refresh_pending(&ui);
    let u = ui.clone();
    glib::timeout_add_seconds_local(2, move || {
        refresh_pending(&u);
        glib::ControlFlow::Continue
    });

    window.present();
}

/// The current pairing link from the daemon, or None (with the reason on the status line).
fn fetch_link(ui: &Ui) -> Option<String> {
    let Some(token) = ui.token.as_deref() else {
        ui.status.set_label("No token found in ~/.config/homectl/token. Start the homectl daemon once, then try again.");
        return None;
    };
    let Some(endpoints) = pairlink::get(ui.port, "/api/endpoints", Some(token)) else {
        ui.status.set_label(&format!("The homectl daemon does not answer on port {}.", ui.port));
        return None;
    };
    Some(pairlink::link(&endpoints, token))
}

fn reveal(ui: &Rc<Ui>, link: &str) {
    match qr_matrix(link) {
        Some(m) => {
            *ui.matrix.borrow_mut() = Some(m);
            ui.qr.queue_draw();
        }
        None => ui.status.set_label("Could not make the QR code (is qrencode installed?). The link is shown below."),
    }
    ui.link_label.set_label(link);
    ui.stack.set_visible_child_name("shown");
    ui.show.set_label("Hide");

    let gen = ui.reveal_gen.get().wrapping_add(1);
    ui.reveal_gen.set(gen);
    let u = ui.clone();
    glib::timeout_add_seconds_local_once(REVEAL_SECS, move || {
        if u.reveal_gen.get() == gen {
            hide(&u);
        }
    });
}

fn hide(ui: &Ui) {
    ui.stack.set_visible_child_name("hidden");
    ui.matrix.borrow_mut().take();
    ui.link_label.set_label("");
    ui.show.set_label("Show");
}

/// Lists the phones asking to pair (`/api/pair/pending`, local only, no token: the code is short-lived and the phone must type it).
fn refresh_pending(ui: &Ui) {
    while let Some(child) = ui.pending.first_child() {
        ui.pending.remove(&child);
    }
    let Some(list) = pairlink::get(ui.port, "/api/pair/pending", None) else {
        ui.status.set_label(&format!("The homectl daemon does not answer on port {}.", ui.port));
        ui.pending.append(&gtk::Label::new(Some("Not available.")));
        return;
    };
    let items = list.as_array().cloned().unwrap_or_default();
    if items.is_empty() {
        ui.pending.append(&gtk::Label::builder().label("No phone is asking to pair right now.").xalign(0.0).css_classes(["dim-label"]).margin_start(12).margin_top(8).margin_bottom(8).build());
    }
    for p in &items {
        ui.pending.append(&pending_row(p));
    }
}

fn pending_row(p: &Value) -> gtk::Box {
    let row = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    row.set_margin_top(8);
    row.set_margin_bottom(8);
    row.set_margin_start(12);
    row.set_margin_end(12);
    let who = gtk::Label::builder()
        .label(p["device"].as_str().unwrap_or("phone"))
        .xalign(0.0)
        .hexpand(true)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .build();
    let code = gtk::Label::builder().label(p["code"].as_str().unwrap_or("------")).css_classes(["title-3", "monospace"]).selectable(true).build();
    let left = p["expires_in"].as_u64().unwrap_or(0);
    let expires = gtk::Label::builder().label(format!("{left} s")).css_classes(["dim-label"]).width_chars(5).xalign(1.0).build();
    row.append(&who);
    row.append(&code);
    row.append(&expires);
    row
}

/// The QR code for `link`, from `qrencode`'s text output (two characters per module: `##` dark, two spaces light).
/// This build of qrencode has no PNG output, so the window draws the matrix itself.
fn qr_matrix(link: &str) -> Option<Vec<Vec<bool>>> {
    let out = std::process::Command::new("qrencode").args(["-t", "ASCII", "-m", "0", link]).output().ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8(out.stdout).ok()?;
    let rows: Vec<Vec<bool>> = text
        .lines()
        .map(|l| l.as_bytes().chunks(2).map(|c| c[0] == b'#').collect())
        .filter(|r: &Vec<bool>| !r.is_empty())
        .collect();
    let n = rows.len();
    (n > 0 && rows.iter().all(|r| r.len() == n)).then_some(rows)
}

/// Draws the modules on a white card with the standard four-module quiet zone around them.
fn draw_qr(cr: &gtk::cairo::Context, m: &[Vec<bool>], w: f64, h: f64) {
    let total = m.len() as f64 + 8.0;
    let cell = (w.min(h) / total).floor().max(1.0);
    let x0 = (w - cell * m.len() as f64) / 2.0;
    let y0 = (h - cell * m.len() as f64) / 2.0;
    cr.set_source_rgb(1.0, 1.0, 1.0);
    cr.paint().ok();
    cr.set_source_rgb(0.0, 0.0, 0.0);
    for (y, row) in m.iter().enumerate() {
        for (x, dark) in row.iter().enumerate() {
            if *dark {
                cr.rectangle(x0 + x as f64 * cell, y0 + y as f64 * cell, cell, cell);
            }
        }
    }
    cr.fill().ok();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_qr_matrix_is_square_and_has_the_finder_pattern() {
        let m = qr_matrix("homectl://pair?t=abc&n=test").expect("qrencode is installed");
        let n = m.len();
        assert!(n >= 21 && (n - 17) % 4 == 0, "a QR code has 4*version+17 modules, got {n}");
        assert!(m.iter().all(|r| r.len() == n));
        // the top-left finder: a dark 7x7 ring, light inside it, dark centre
        assert!((0..7).all(|i| m[0][i] && m[6][i] && m[i][0] && m[i][6]));
        assert!(!m[1][1] && m[3][3]);
    }
}
