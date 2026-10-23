//! homectl-pty [-n NAME] [--] [COMMAND ARGS…] — run a shell (or COMMAND) inside this terminal window and let the
//! homectl daemon mirror its screen to the phone.
//!
//! The wrapper is transparent: it puts the real terminal in raw mode, runs the command on a pty and copies bytes both
//! ways. On the side it keeps a small model of the screen (cells, colours, cursor, title, scrollback) and serves it on a
//! unix socket, `$XDG_RUNTIME_DIR/homectl/win/<pid>.sock` (mode 0600 in a 0700 directory), so only the same user can read it.
//!
//! Socket protocol (frame = type u8, length u32 little-endian, payload):
//!   client -> 'G' [u16 scrollback]   one snapshot now
//!   client -> 'W' [u16 scrollback]   snapshot now, then one whenever the screen changes
//!   client -> 'I' bytes              type these bytes into the command
//!   server -> 'S' JSON               a snapshot (see `snapshot`)
//! No dependencies, raw syscalls, no libc.
const std = @import("std");
const linux = std.os.linux;

const A = std.heap.page_allocator;
const scrollback_cap = 1000;

const Cell = struct { cp: u21 = ' ', fg: i32 = -1, bg: i32 = -1, at: u8 = 0 };
const BOLD = 1;
const DIM = 2;
const ITALIC = 4;
const UNDER = 8;
const INVERSE = 16;
const STRIKE = 32;

const Grid = struct {
    cells: []Cell,
    cols: usize,
    rows: usize,

    fn init(cols: usize, rows: usize) Grid {
        const c = A.alloc(Cell, cols * rows) catch @panic("oom");
        @memset(c, .{});
        return .{ .cells = c, .cols = cols, .rows = rows };
    }
    fn row(g: *Grid, y: usize) []Cell {
        return g.cells[y * g.cols ..][0..g.cols];
    }
    fn resized(g: *Grid, cols: usize, rows: usize) Grid {
        var n = Grid.init(cols, rows);
        for (0..@min(rows, g.rows)) |y| {
            const w = @min(cols, g.cols);
            @memcpy(n.row(y)[0..w], g.row(y)[0..w]);
        }
        A.free(g.cells);
        return n;
    }
};

const St = enum { ground, esc, csi, osc, osc_esc, ignore_st, esc_inter };

const Term = struct {
    master: i32,
    cols: usize,
    rows: usize,
    main: Grid,
    alt: Grid,
    use_alt: bool = false,
    cx: usize = 0,
    cy: usize = 0,
    wrap_pending: bool = false,
    autowrap: bool = true,
    cursor_visible: bool = true,
    /// 0 = the program does not want the mouse, otherwise the highest of DECSET 1000/1002/1003 it asked for; sgr = DECSET 1006.
    mouse: u16 = 0,
    mouse_sgr: bool = false,
    pen: Cell = .{},
    saved: struct { cx: usize = 0, cy: usize = 0, pen: Cell = .{} } = .{},
    top: usize = 0,
    bot: usize,
    wid: [6]u8 = "000000".*,
    title: [200]u8 = undefined,
    title_len: usize = 0,
    cwd: [400]u8 = undefined,
    cwd_len: usize = 0,
    seq: u64 = 1,
    back: [scrollback_cap]?[]Cell = [_]?[]Cell{null} ** scrollback_cap,
    back_head: usize = 0, // next slot to write
    back_len: usize = 0,
    // parser
    st: St = .ground,
    params: [16]i32 = undefined,
    np: usize = 0,
    cur_param: i32 = -1,
    private: u8 = 0,
    osc_buf: [512]u8 = undefined,
    osc_len: usize = 0,
    utf: u21 = 0,
    utf_need: u8 = 0,

    fn grid(t: *Term) *Grid {
        return if (t.use_alt) &t.alt else &t.main;
    }

    fn init(master: i32, cols: usize, rows: usize) Term {
        return .{ .master = master, .cols = cols, .rows = rows, .main = Grid.init(cols, rows), .alt = Grid.init(cols, rows), .bot = rows - 1 };
    }

    fn resize(t: *Term, cols: usize, rows: usize) void {
        if (cols == t.cols and rows == t.rows) return;
        t.main = t.main.resized(cols, rows);
        t.alt = t.alt.resized(cols, rows);
        t.cols = cols;
        t.rows = rows;
        t.top = 0;
        t.bot = rows - 1;
        t.cx = @min(t.cx, cols - 1);
        t.cy = @min(t.cy, rows - 1);
        t.wrap_pending = false;
        t.seq += 1;
    }

    fn blank(t: *Term) Cell {
        return .{ .cp = ' ', .bg = t.pen.bg };
    }

    fn pushBack(t: *Term, y: usize) void {
        const g = &t.main;
        const copy = A.alloc(Cell, g.cols) catch return;
        @memcpy(copy, g.row(y));
        if (t.back[t.back_head]) |old| A.free(old);
        t.back[t.back_head] = copy;
        t.back_head = (t.back_head + 1) % scrollback_cap;
        if (t.back_len < scrollback_cap) t.back_len += 1;
    }

    fn scrollUp(t: *Term, n: usize) void {
        const g = t.grid();
        var i: usize = 0;
        while (i < n) : (i += 1) {
            if (!t.use_alt and t.top == 0) t.pushBack(0);
            var y = t.top;
            while (y < t.bot) : (y += 1) @memcpy(g.row(y), g.row(y + 1));
            @memset(g.row(t.bot), t.blank());
        }
    }

    fn scrollDown(t: *Term, n: usize) void {
        const g = t.grid();
        var i: usize = 0;
        while (i < n) : (i += 1) {
            var y = t.bot;
            while (y > t.top) : (y -= 1) @memcpy(g.row(y), g.row(y - 1));
            @memset(g.row(t.top), t.blank());
        }
    }

    fn lineFeed(t: *Term) void {
        if (t.cy == t.bot) t.scrollUp(1) else if (t.cy + 1 < t.rows) t.cy += 1;
    }

    fn put(t: *Term, cp: u21) void {
        const w = width(cp);
        if (w == 0) return;
        if (t.wrap_pending or t.cx + w > t.cols) {
            if (t.autowrap) {
                t.cx = 0;
                t.lineFeed();
            } else t.cx = t.cols - w;
            t.wrap_pending = false;
        }
        const g = t.grid();
        var c = t.pen;
        c.cp = cp;
        g.row(t.cy)[t.cx] = c;
        if (w == 2 and t.cx + 1 < t.cols) g.row(t.cy)[t.cx + 1] = .{ .cp = 0, .fg = t.pen.fg, .bg = t.pen.bg, .at = t.pen.at };
        if (t.cx + w >= t.cols) t.wrap_pending = true else t.cx += w;
    }

    fn reply(t: *Term, s: []const u8) void {
        _ = linux.write(t.master, s.ptr, s.len);
    }

    fn clearRange(t: *Term, y: usize, x0: usize, x1: usize) void {
        if (x0 >= x1) return;
        @memset(t.grid().row(y)[x0..x1], t.blank());
    }

    fn param(t: *Term, i: usize, def: i32) i32 {
        if (i >= t.np or t.params[i] < 0) return def;
        return t.params[i];
    }

    fn feed(t: *Term, data: []const u8) void {
        for (data) |b| t.byte(b);
        t.seq += 1;
    }

    fn byte(t: *Term, b: u8) void {
        // C0 controls act in every state except inside strings
        switch (t.st) {
            .osc, .osc_esc, .ignore_st => {},
            else => switch (b) {
                0x07 => return,
                0x08 => {
                    if (t.cx > 0) t.cx -= 1;
                    t.wrap_pending = false;
                    return;
                },
                0x09 => {
                    t.cx = @min((t.cx / 8 + 1) * 8, t.cols - 1);
                    t.wrap_pending = false;
                    return;
                },
                0x0A, 0x0B, 0x0C => {
                    t.lineFeed();
                    t.wrap_pending = false;
                    return;
                },
                0x0D => {
                    t.cx = 0;
                    t.wrap_pending = false;
                    return;
                },
                0x1B => {
                    t.st = .esc;
                    return;
                },
                0x18, 0x1A => {
                    t.st = .ground;
                    return;
                },
                0x00...0x06, 0x0E...0x17, 0x19, 0x1C...0x1F => return,
                else => {},
            },
        }
        switch (t.st) {
            .ground => t.ground(b),
            .esc => t.escByte(b),
            .esc_inter => t.st = .ground,
            .csi => t.csiByte(b),
            .osc => switch (b) {
                0x07 => t.oscEnd(),
                0x1B => t.st = .osc_esc,
                else => if (t.osc_len < t.osc_buf.len) {
                    t.osc_buf[t.osc_len] = b;
                    t.osc_len += 1;
                },
            },
            .osc_esc => {
                if (b == '\\') t.oscEnd() else t.st = .ground;
            },
            .ignore_st => switch (b) {
                0x07 => t.st = .ground,
                0x1B => t.st = .osc_esc,
                else => {},
            },
        }
    }

    fn ground(t: *Term, b: u8) void {
        if (t.utf_need > 0) {
            if (b & 0xC0 == 0x80) {
                t.utf = (t.utf << 6) | (b & 0x3F);
                t.utf_need -= 1;
                if (t.utf_need == 0) t.put(t.utf);
                return;
            }
            t.utf_need = 0; // broken sequence: fall through and treat b as a fresh start
        }
        if (b < 0x80) {
            if (b >= 0x20 and b != 0x7F) t.put(b);
        } else if (b & 0xE0 == 0xC0) {
            t.utf = b & 0x1F;
            t.utf_need = 1;
        } else if (b & 0xF0 == 0xE0) {
            t.utf = b & 0x0F;
            t.utf_need = 2;
        } else if (b & 0xF8 == 0xF0) {
            t.utf = b & 0x07;
            t.utf_need = 3;
        }
    }

    fn escByte(t: *Term, b: u8) void {
        t.st = .ground;
        switch (b) {
            '[' => {
                t.st = .csi;
                t.np = 0;
                t.cur_param = -1;
                t.private = 0;
            },
            ']' => {
                t.st = .osc;
                t.osc_len = 0;
            },
            'P', 'X', '^', '_' => t.st = .ignore_st,
            '7' => t.saved = .{ .cx = t.cx, .cy = t.cy, .pen = t.pen },
            '8' => {
                t.cx = @min(t.saved.cx, t.cols - 1);
                t.cy = @min(t.saved.cy, t.rows - 1);
                t.pen = t.saved.pen;
                t.wrap_pending = false;
            },
            'D' => t.lineFeed(),
            'E' => {
                t.cx = 0;
                t.lineFeed();
            },
            'M' => if (t.cy == t.top) t.scrollDown(1) else if (t.cy > 0) {
                t.cy -= 1;
            },
            'c' => t.fullReset(),
            '(', ')', '*', '+', '#', '%', ' ' => t.st = .esc_inter,
            else => {},
        }
    }

    fn fullReset(t: *Term) void {
        @memset(t.main.cells, .{});
        @memset(t.alt.cells, .{});
        t.use_alt = false;
        t.cx = 0;
        t.cy = 0;
        t.pen = .{};
        t.top = 0;
        t.bot = t.rows - 1;
        t.autowrap = true;
        t.cursor_visible = true;
        t.wrap_pending = false;
    }

    fn csiByte(t: *Term, b: u8) void {
        switch (b) {
            '0'...'9' => {
                t.cur_param = (if (t.cur_param < 0) 0 else t.cur_param) *| 10 +| @as(i32, b - '0');
                if (t.cur_param > 100000) t.cur_param = 100000;
            },
            ';', ':' => t.pushParam(),
            '?', '>', '<', '=' => t.private = b,
            0x20...0x2F => {},
            0x40...0x7E => {
                t.pushParam();
                t.st = .ground;
                t.csi(b);
            },
            else => t.st = .ground,
        }
    }

    fn pushParam(t: *Term) void {
        if (t.np < t.params.len) {
            t.params[t.np] = t.cur_param;
            t.np += 1;
        }
        t.cur_param = -1;
    }

    fn csi(t: *Term, f: u8) void {
        if (t.np == 0) {
            t.params[0] = -1;
            t.np = 1;
        }
        const n1: usize = @intCast(@max(1, t.param(0, 1)));
        const rows = t.rows;
        const cols = t.cols;
        t.wrap_pending = false;
        if (t.private != 0) {
            switch (f) {
                'h', 'l' => {
                    const on = f == 'h';
                    for (0..t.np) |i| switch (t.param(i, 0)) {
                        7 => t.autowrap = on,
                        25 => t.cursor_visible = on,
                        1000, 1002, 1003 => |m| t.mouse = if (on) @intCast(m) else if (t.mouse == m) 0 else t.mouse,
                        1006 => t.mouse_sgr = on,
                        47, 1047, 1049 => t.setAlt(on),
                        else => {},
                    };
                },
                else => {},
            }
            return;
        }
        switch (f) {
            'A' => t.cy -|= @min(n1, t.cy - (if (t.cy >= t.top) t.top else 0)),
            'B' => t.cy = @min(t.cy + n1, if (t.cy <= t.bot) t.bot else rows - 1),
            'C' => t.cx = @min(t.cx + n1, cols - 1),
            'D' => t.cx -|= n1,
            'E' => {
                t.cy = @min(t.cy + n1, rows - 1);
                t.cx = 0;
            },
            'F' => {
                t.cy -|= n1;
                t.cx = 0;
            },
            'G', '`' => t.cx = @min(n1 - 1, cols - 1),
            'd' => t.cy = @min(n1 - 1, rows - 1),
            'H', 'f' => {
                t.cy = @min(@as(usize, @intCast(@max(1, t.param(0, 1)))) - 1, rows - 1);
                t.cx = @min(@as(usize, @intCast(@max(1, t.param(1, 1)))) - 1, cols - 1);
            },
            'J' => switch (t.param(0, 0)) {
                0 => {
                    t.clearRange(t.cy, t.cx, cols);
                    for (t.cy + 1..rows) |y| t.clearRange(y, 0, cols);
                },
                1 => {
                    for (0..t.cy) |y| t.clearRange(y, 0, cols);
                    t.clearRange(t.cy, 0, t.cx + 1);
                },
                2, 3 => for (0..rows) |y| t.clearRange(y, 0, cols),
                else => {},
            },
            'K' => switch (t.param(0, 0)) {
                0 => t.clearRange(t.cy, t.cx, cols),
                1 => t.clearRange(t.cy, 0, t.cx + 1),
                2 => t.clearRange(t.cy, 0, cols),
                else => {},
            },
            'L' => if (t.cy >= t.top and t.cy <= t.bot) {
                const save = t.top;
                t.top = t.cy;
                t.scrollDown(@min(n1, t.bot - t.cy + 1));
                t.top = save;
            },
            'M' => if (t.cy >= t.top and t.cy <= t.bot) {
                const save = t.top;
                t.top = t.cy;
                t.scrollUpNoBack(@min(n1, t.bot - t.cy + 1));
                t.top = save;
            },
            'P' => {
                const row = t.grid().row(t.cy);
                const n = @min(n1, cols - t.cx);
                std.mem.copyForwards(Cell, row[t.cx .. cols - n], row[t.cx + n .. cols]);
                @memset(row[cols - n .. cols], t.blank());
            },
            '@' => {
                const row = t.grid().row(t.cy);
                const n = @min(n1, cols - t.cx);
                std.mem.copyBackwards(Cell, row[t.cx + n .. cols], row[t.cx .. cols - n]);
                @memset(row[t.cx .. t.cx + n], t.blank());
            },
            'X' => t.clearRange(t.cy, t.cx, @min(cols, t.cx + n1)),
            'S' => t.scrollUp(n1),
            'T' => t.scrollDown(n1),
            'r' => {
                const top: usize = @intCast(@max(1, t.param(0, 1)));
                const bot: usize = @intCast(@max(1, t.param(1, @intCast(rows))));
                if (top < bot and bot <= rows) {
                    t.top = top - 1;
                    t.bot = bot - 1;
                }
                t.cx = 0;
                t.cy = 0;
            },
            's' => t.saved = .{ .cx = t.cx, .cy = t.cy, .pen = t.pen },
            'u' => {
                t.cx = @min(t.saved.cx, cols - 1);
                t.cy = @min(t.saved.cy, rows - 1);
            },
            'm' => t.sgr(),
            'n' => if (t.param(0, 0) == 6) {
                var buf: [32]u8 = undefined;
                const s = std.fmt.bufPrint(&buf, "\x1b[{d};{d}R", .{ t.cy + 1, t.cx + 1 }) catch return;
                t.reply(s);
            } else if (t.param(0, 0) == 5) t.reply("\x1b[0n"),
            'c' => t.reply("\x1b[?62;c"),
            else => {},
        }
    }

    fn scrollUpNoBack(t: *Term, n: usize) void {
        const g = t.grid();
        var i: usize = 0;
        while (i < n) : (i += 1) {
            var y = t.top;
            while (y < t.bot) : (y += 1) @memcpy(g.row(y), g.row(y + 1));
            @memset(g.row(t.bot), t.blank());
        }
    }

    fn setAlt(t: *Term, on: bool) void {
        if (on == t.use_alt) return;
        t.use_alt = on;
        if (on) @memset(t.alt.cells, .{});
        t.top = 0;
        t.bot = t.rows - 1;
    }

    fn sgr(t: *Term) void {
        var i: usize = 0;
        while (i < t.np) : (i += 1) {
            const p = t.param(i, 0);
            switch (p) {
                0 => t.pen = .{},
                1 => t.pen.at |= BOLD,
                2 => t.pen.at |= DIM,
                3 => t.pen.at |= ITALIC,
                4 => t.pen.at |= UNDER,
                7 => t.pen.at |= INVERSE,
                9 => t.pen.at |= STRIKE,
                22 => t.pen.at &= ~@as(u8, BOLD | DIM),
                23 => t.pen.at &= ~@as(u8, ITALIC),
                24 => t.pen.at &= ~@as(u8, UNDER),
                27 => t.pen.at &= ~@as(u8, INVERSE),
                29 => t.pen.at &= ~@as(u8, STRIKE),
                30...37 => t.pen.fg = p - 30,
                39 => t.pen.fg = -1,
                40...47 => t.pen.bg = p - 40,
                49 => t.pen.bg = -1,
                90...97 => t.pen.fg = p - 90 + 8,
                100...107 => t.pen.bg = p - 100 + 8,
                38, 48 => {
                    var v: i32 = -1;
                    if (t.param(i + 1, -1) == 5) {
                        v = t.param(i + 2, 0);
                        i += 2;
                    } else if (t.param(i + 1, -1) == 2) {
                        const r = t.param(i + 2, 0) & 255;
                        const g = t.param(i + 3, 0) & 255;
                        const b = t.param(i + 4, 0) & 255;
                        v = 256 + ((r << 16) | (g << 8) | b);
                        i += 4;
                    }
                    if (p == 38) t.pen.fg = v else t.pen.bg = v;
                },
                else => {},
            }
        }
    }

    fn oscEnd(t: *Term) void {
        t.st = .ground;
        const s = t.osc_buf[0..t.osc_len];
        const semi = std.mem.indexOfScalar(u8, s, ';') orelse return;
        const code = std.fmt.parseInt(u32, s[0..semi], 10) catch return;
        const arg = s[semi + 1 ..];
        switch (code) {
            0, 2 => {
                t.title_len = @min(arg.len, t.title.len);
                @memcpy(t.title[0..t.title_len], arg[0..t.title_len]);
            },
            7 => {
                // file://host/path
                var p = arg;
                if (std.mem.startsWith(u8, p, "file://")) {
                    p = p[7..];
                    p = p[std.mem.indexOfScalar(u8, p, '/') orelse return ..];
                }
                t.cwd_len = @min(p.len, t.cwd.len);
                @memcpy(t.cwd[0..t.cwd_len], p[0..t.cwd_len]);
            },
            else => {},
        }
    }
};

/// Columns a code point takes: 0 for combining marks, 2 for East Asian wide and emoji, else 1.
fn width(cp: u21) usize {
    if (cp < 0x300) return 1;
    if ((cp >= 0x300 and cp <= 0x36F) or (cp >= 0x200B and cp <= 0x200F) or (cp >= 0xFE00 and cp <= 0xFE0F) or cp == 0x20E3) return 0;
    if ((cp >= 0x1100 and cp <= 0x115F) or (cp >= 0x2E80 and cp <= 0xA4CF) or (cp >= 0xAC00 and cp <= 0xD7A3) or
        (cp >= 0xF900 and cp <= 0xFAFF) or (cp >= 0xFE30 and cp <= 0xFE6F) or (cp >= 0xFF00 and cp <= 0xFF60) or
        (cp >= 0xFFE0 and cp <= 0xFFE6) or (cp >= 0x1F300 and cp <= 0x1F64F) or (cp >= 0x1F900 and cp <= 0x1F9FF) or
        (cp >= 0x20000 and cp <= 0x3FFFD)) return 2;
    return 1;
}

// ---- JSON snapshot ----

const Buf = struct {
    list: std.ArrayList(u8) = .empty,
    fn s(b: *Buf, text: []const u8) void {
        b.list.appendSlice(A, text) catch {};
    }
    fn num(b: *Buf, v: anytype) void {
        var tmp: [32]u8 = undefined;
        b.s(std.fmt.bufPrint(&tmp, "{d}", .{v}) catch return);
    }
    fn str(b: *Buf, text: []const u8) void {
        b.s("\"");
        for (text) |c| switch (c) {
            '"' => b.s("\\\""),
            '\\' => b.s("\\\\"),
            '\n' => b.s("\\n"),
            '\r' => b.s("\\r"),
            '\t' => b.s("\\t"),
            0...8, 11, 12, 14...31 => {
                var tmp: [8]u8 = undefined;
                b.s(std.fmt.bufPrint(&tmp, "\\u{x:0>4}", .{c}) catch "");
            },
            else => b.list.append(A, c) catch {},
        };
        b.s("\"");
    }
};

fn emitRow(b: *Buf, cells: []const Cell) void {
    var end = cells.len;
    while (end > 0 and cells[end - 1].cp == ' ' and cells[end - 1].bg == -1 and cells[end - 1].at & (INVERSE | UNDER | STRIKE) == 0) end -= 1;
    b.s("[");
    var i: usize = 0;
    var first = true;
    while (i < end) {
        const c = cells[i];
        var j = i;
        var text: std.ArrayList(u8) = .empty;
        defer text.deinit(A);
        while (j < end and cells[j].fg == c.fg and cells[j].bg == c.bg and cells[j].at == c.at) : (j += 1) {
            if (cells[j].cp == 0) continue;
            var enc: [4]u8 = undefined;
            const n = std.unicode.utf8Encode(cells[j].cp, &enc) catch 1;
            if (n == 1 and cells[j].cp > 0x7F) text.append(A, '?') catch {} else text.appendSlice(A, enc[0..n]) catch {};
        }
        if (!first) b.s(",");
        first = false;
        b.s("[");
        b.str(text.items);
        b.s(",");
        b.num(c.fg);
        b.s(",");
        b.num(c.bg);
        b.s(",");
        b.num(c.at);
        b.s("]");
        i = j;
    }
    b.s("]");
}

fn snapshot(t: *Term, back_want: usize, pid: i32, cmd: []const u8) Buf {
    var b = Buf{};
    const nb = @min(back_want, t.back_len);
    b.s("{\"v\":1,\"pid\":");
    b.num(pid);
    b.s(",\"seq\":");
    b.num(t.seq);
    b.s(",\"cols\":");
    b.num(t.cols);
    b.s(",\"rows\":");
    b.num(t.rows);
    b.s(",\"cx\":");
    b.num(t.cx);
    b.s(",\"cy\":");
    b.num(t.cy);
    b.s(",\"cv\":");
    b.s(if (t.cursor_visible) "true" else "false");
    b.s(",\"alt\":");
    b.s(if (t.use_alt) "true" else "false");
    b.s(",\"mouse\":");
    b.num(if (t.mouse_sgr) t.mouse else 0); // only SGR mouse reports are produced by the app, so older encodings count as "no mouse"
    b.s(",\"wid\":");
    b.str(&t.wid);
    b.s(",\"title\":");
    b.str(t.title[0..t.title_len]);
    b.s(",\"cwd\":");
    b.str(t.cwd[0..t.cwd_len]);
    b.s(",\"cmd\":");
    b.str(cmd);
    b.s(",\"sb\":");
    b.num(nb);
    b.s(",\"lines\":[");
    var first = true;
    var k: usize = nb;
    while (k > 0) : (k -= 1) {
        const idx = (t.back_head + scrollback_cap - k) % scrollback_cap;
        if (t.back[idx]) |row| {
            if (!first) b.s(",");
            first = false;
            emitRow(&b, row);
        }
    }
    for (0..t.rows) |y| {
        if (!first) b.s(",");
        first = false;
        emitRow(&b, t.grid().row(y));
    }
    b.s("]}");
    return b;
}

// ---- process, pty, sockets ----

fn cstr(buf: []u8, s: []const u8) [*:0]u8 {
    @memcpy(buf[0..s.len], s);
    buf[s.len] = 0;
    return @ptrCast(buf.ptr);
}

fn winsize(fd: i32) ?std.posix.winsize {
    var ws: std.posix.winsize = undefined;
    if (linux.errno(linux.ioctl(fd, linux.T.IOCGWINSZ, @intFromPtr(&ws))) != .SUCCESS) return null;
    if (ws.col == 0 or ws.row == 0) return null;
    return ws;
}

fn writeAllFd(fd: i32, data: []const u8) bool {
    var off: usize = 0;
    var spins: u32 = 0;
    while (off < data.len) {
        const n = linux.write(fd, data.ptr + off, data.len - off);
        switch (linux.errno(n)) {
            .SUCCESS => off += n,
            .AGAIN => {
                spins += 1;
                if (spins > 200) return false;
                var ts = linux.timespec{ .sec = 0, .nsec = 5_000_000 };
                _ = linux.nanosleep(&ts, null);
            },
            .INTR => {},
            else => return false,
        }
    }
    return true;
}

const Client = struct {
    fd: i32,
    watching: bool = false,
    back: usize = 0,
    sent: u64 = 0,
    buf: [4096]u8 = undefined,
    len: usize = 0,
};

fn sendSnapshot(c: *Client, t: *Term, pid: i32, cmd: []const u8) bool {
    var b = snapshot(t, c.back, pid, cmd);
    defer b.list.deinit(A);
    var hdr: [5]u8 = undefined;
    hdr[0] = 'S';
    std.mem.writeInt(u32, hdr[1..5], @intCast(b.list.items.len), .little);
    c.sent = t.seq;
    return writeAllFd(c.fd, &hdr) and writeAllFd(c.fd, b.list.items);
}

fn die(msg: []const u8) noreturn {
    _ = linux.write(2, msg.ptr, msg.len);
    linux.exit(1);
}

var term_orig: linux.termios = undefined;
var raw_on = false;

fn rawMode() void {
    if (linux.errno(linux.ioctl(0, linux.T.CGETS, @intFromPtr(&term_orig))) != .SUCCESS) return;
    var t = term_orig;
    t.iflag.IGNBRK = false;
    t.iflag.BRKINT = false;
    t.iflag.PARMRK = false;
    t.iflag.ISTRIP = false;
    t.iflag.INLCR = false;
    t.iflag.IGNCR = false;
    t.iflag.ICRNL = false;
    t.iflag.IXON = false;
    t.oflag.OPOST = false;
    t.lflag.ECHO = false;
    t.lflag.ECHONL = false;
    t.lflag.ICANON = false;
    t.lflag.ISIG = false;
    t.lflag.IEXTEN = false;
    t.cflag.PARENB = false;
    t.cflag.CSIZE = .CS8;
    t.cc[@intFromEnum(linux.V.MIN)] = 1;
    t.cc[@intFromEnum(linux.V.TIME)] = 0;
    if (linux.errno(linux.ioctl(0, linux.T.CSETS, @intFromPtr(&t))) == .SUCCESS) raw_on = true;
}

fn restore() void {
    if (raw_on) _ = linux.ioctl(0, linux.T.CSETS, @intFromPtr(&term_orig));
}


/// Gives every window a name of its own that no one can see. Ghostty runs all its windows in one process, so a process id
/// cannot tell two windows apart; the title can, if the wrapper writes an invisible name into it. The name is 24 bits drawn as
/// zero-width characters (U+200B = 0, U+200C = 1) between two word joiners (U+2060) and is appended to every window title the
/// shell sets (OSC 0 and OSC 2), so whoever lists the windows can read it back from the title.
const Tagger = struct {
    id: u24,
    st: enum { ground, esc, head, title, title_esc, pass, pass_esc } = .ground,
    pend: [1100]u8 = undefined,
    n: usize = 0,
    hex: [6]u8 = undefined,
    title_at: usize = 0,

    fn init(id: u24) Tagger {
        var t = Tagger{ .id = id };
        _ = std.fmt.bufPrint(&t.hex, "{x:0>6}", .{id}) catch unreachable;
        return t;
    }

    /// The invisible name as UTF-8.
    fn marker(t: *const Tagger, out: *[80]u8) []const u8 {
        var i: usize = 0;
        const wj = "\xe2\x81\xa0";
        @memcpy(out[i..][0..3], wj);
        i += 3;
        var bit: u5 = 23;
        while (true) {
            @memcpy(out[i..][0..3], if ((t.id >> bit) & 1 == 1) "\xe2\x80\x8c" else "\xe2\x80\x8b");
            i += 3;
            if (bit == 0) break;
            bit -= 1;
        }
        @memcpy(out[i..][0..3], wj);
        i += 3;
        return out[0..i];
    }

    fn flushPend(t: *Tagger, out: *std.ArrayList(u8)) void {
        out.appendSlice(A, t.pend[0..t.n]) catch {};
        t.n = 0;
    }

    fn emit(t: *Tagger, out: *std.ArrayList(u8), st_terminated: bool) void {
        var mb: [80]u8 = undefined;
        const text = t.pend[t.title_at..t.n];
        out.appendSlice(A, "\x1b]2;") catch {};
        out.appendSlice(A, text) catch {};
        out.appendSlice(A, t.marker(&mb)) catch {};
        out.appendSlice(A, if (st_terminated) "\x1b\\" else "\x07") catch {};
        t.n = 0;
        t.st = .ground;
    }


    /// Copies `in` to `out`, adding the name to window titles.
    fn feed(t: *Tagger, in: []const u8, out: *std.ArrayList(u8)) void {
        for (in) |b| t.byte(b, out);
    }

    fn byte(t: *Tagger, b: u8, out: *std.ArrayList(u8)) void {
        switch (t.st) {
            .ground => if (b == 0x1b) {
                t.pend[0] = b;
                t.n = 1;
                t.st = .esc;
            } else out.append(A, b) catch {},
            .esc => if (b == ']') {
                t.pend[t.n] = b;
                t.n += 1;
                t.st = .head;
            } else {
                t.flushPend(out);
                t.st = .ground;
                t.byte(b, out);
            },
            .head => {
                if (b >= '0' and b <= '9' and t.n < 5) {
                    t.pend[t.n] = b;
                    t.n += 1;
                } else if (b == ';' and (std.mem.eql(u8, t.pend[2..t.n], "0") or std.mem.eql(u8, t.pend[2..t.n], "2"))) {
                    t.pend[t.n] = b;
                    t.n += 1;
                    t.title_at = t.n;
                    t.st = .title;
                } else {
                    t.flushPend(out);
                    t.st = .pass;
                    t.byte(b, out);
                }
            },
            .title => switch (b) {
                0x07 => t.emit(out, false),
                0x1b => t.st = .title_esc,
                else => if (t.n < t.pend.len) {
                    t.pend[t.n] = b;
                    t.n += 1;
                } else {
                    t.flushPend(out);
                    t.st = .pass;
                    t.byte(b, out);
                },
            },
            .title_esc => if (b == '\\') t.emit(out, true) else {
                // another escape sequence began inside the title: the title is abandoned
                t.flushPend(out);
                out.append(A, 0x1b) catch {};
                t.st = .ground;
                t.byte(b, out);
            },
            .pass => {
                out.append(A, b) catch {};
                if (b == 0x07) t.st = .ground else if (b == 0x1b) t.st = .pass_esc;
            },
            .pass_esc => {
                out.append(A, b) catch {};
                t.st = if (b == '\\') .ground else if (b == 0x1b) .pass_esc else .pass;
            },
        }
    }
};

pub fn main(init: std.process.Init) !void {
    var args = init.minimal.args.iterate();
    _ = args.skip();
    var argv_list: std.ArrayList([]const u8) = .empty;
    var parse_opts = true;
    while (args.next()) |a| {
        if (parse_opts and std.mem.eql(u8, a, "--")) {
            parse_opts = false;
            continue;
        }
        if (parse_opts and std.mem.eql(u8, a, "-n")) {
            _ = args.next(); // the name is taken from the window title; accepted for readability
            continue;
        }
        if (parse_opts and (std.mem.eql(u8, a, "-h") or std.mem.eql(u8, a, "--help"))) die("usage: homectl-pty [--] [COMMAND ARGS…]\n  runs your shell here and lets homectl mirror this window to your phone\n");
        parse_opts = false;
        try argv_list.append(A, a);
    }
    if (argv_list.items.len == 0) try argv_list.append(A, init.environ_map.get("SHELL") orelse "/bin/sh");

    // not a terminal (a pipe, a script): nothing to mirror, just run the command
    var probe: linux.termios = undefined;
    const interactive = linux.errno(linux.ioctl(0, linux.T.CGETS, @intFromPtr(&probe))) == .SUCCESS;

    const mr = linux.open("/dev/ptmx", .{ .ACCMODE = .RDWR, .NOCTTY = true, .CLOEXEC = true }, 0);
    if (linux.errno(mr) != .SUCCESS) die("cannot open /dev/ptmx\n");
    const master: i32 = @intCast(mr);
    var unlock: c_int = 0;
    _ = linux.ioctl(master, linux.T.IOCSPTLCK, @intFromPtr(&unlock));
    var ptn: c_uint = 0;
    _ = linux.ioctl(master, linux.T.IOCGPTN, @intFromPtr(&ptn));
    var slave_path: [64]u8 = undefined;
    const sp = std.fmt.bufPrint(&slave_path, "/dev/pts/{d}\x00", .{ptn}) catch die("pts\n");

    var ws = winsize(0) orelse std.posix.winsize{ .row = 24, .col = 80, .xpixel = 0, .ypixel = 0 };
    _ = linux.ioctl(master, linux.T.IOCSWINSZ, @intFromPtr(&ws));

    const fr = linux.fork();
    if (linux.errno(fr) != .SUCCESS) die("fork failed\n");
    if (fr == 0) {
        _ = linux.setsid();
        const sr = linux.open(@ptrCast(sp.ptr), .{ .ACCMODE = .RDWR }, 0);
        if (linux.errno(sr) != .SUCCESS) linux.exit(127);
        const slave: i32 = @intCast(sr);
        _ = linux.ioctl(slave, linux.T.IOCSCTTY, 0);
        _ = linux.dup2(slave, 0);
        _ = linux.dup2(slave, 1);
        _ = linux.dup2(slave, 2);
        if (slave > 2) _ = linux.close(slave);
        _ = linux.close(master);

        const argv = try A.alloc(?[*:0]const u8, argv_list.items.len + 1);
        for (argv_list.items, 0..) |a, i| argv[i] = (try A.dupeZ(u8, a)).ptr;
        argv[argv_list.items.len] = null;
        const envz = try A.alloc(?[*:0]const u8, init.environ_map.count() + 2);
        var n: usize = 0;
        var it = init.environ_map.iterator();
        while (it.next()) |e| {
            if (std.mem.eql(u8, e.key_ptr.*, "HOMECTL_WINDOW")) continue;
            envz[n] = (try std.fmt.allocPrintSentinel(A, "{s}={s}", .{ e.key_ptr.*, e.value_ptr.* }, 0)).ptr;
            n += 1;
        }
        envz[n] = (try std.fmt.allocPrintSentinel(A, "HOMECTL_WINDOW={d}", .{linux.getppid()}, 0)).ptr;
        n += 1;
        envz[n] = null;
        const cmd = argv_list.items[0];
        if (std.mem.indexOfScalar(u8, cmd, '/') != null) {
            _ = linux.execve(argv[0].?, @ptrCast(argv.ptr), @ptrCast(envz.ptr));
        } else {
            var pit = std.mem.tokenizeScalar(u8, init.environ_map.get("PATH") orelse "/usr/local/bin:/usr/bin:/bin", ':');
            while (pit.next()) |dir| {
                const full = try std.fmt.allocPrintSentinel(A, "{s}/{s}", .{ dir, cmd }, 0);
                _ = linux.execve(full.ptr, @ptrCast(argv.ptr), @ptrCast(envz.ptr));
            }
        }
        linux.exit(127);
    }
    const child: i32 = @intCast(fr);
    const me: i32 = @intCast(linux.getpid());

    if (interactive) rawMode();
    defer restore();

    // the screen model and the socket
    var term = Term.init(master, ws.col, ws.row);
    // an invisible name for this window, written into every title the shell sets (see Tagger)
    var tag = blk: {
        var r: [3]u8 = undefined;
        _ = linux.getrandom(&r, r.len, 0);
        break :blk Tagger.init(@as(u24, r[0]) << 16 | @as(u24, r[1]) << 8 | r[2]);
    };
    term.wid = tag.hex;
    var tagged: std.ArrayList(u8) = .empty;
    if (interactive) {
        var mb: [80]u8 = undefined;
        _ = writeAllFd(1, "\x1b]2;");
        _ = writeAllFd(1, tag.marker(&mb));
        _ = writeAllFd(1, "\x07");
    }
    var cmdline: std.ArrayList(u8) = .empty;
    for (argv_list.items, 0..) |a, i| {
        if (i > 0) try cmdline.append(A, ' ');
        try cmdline.appendSlice(A, a);
    }

    var sock_path: [160:0]u8 = undefined;
    var listen_fd: i32 = -1;
    const uid = linux.getuid();
    {
        var dirbuf: [128]u8 = undefined;
        const base = if (init.environ_map.get("XDG_RUNTIME_DIR")) |x| std.fmt.bufPrint(&dirbuf, "{s}/homectl", .{x}) catch null else std.fmt.bufPrint(&dirbuf, "/tmp/homectl-{d}", .{uid}) catch null;
        if (base) |bdir| {
            var d1: [140]u8 = undefined;
            _ = linux.mkdir(cstr(&d1, bdir), 0o700);
            var wd: [150]u8 = undefined;
            const wdl = std.fmt.bufPrint(&wd, "{s}/win", .{bdir}) catch "";
            var wz: [160]u8 = undefined;
            _ = linux.mkdir(cstr(&wz, wdl), 0o700);
            const sl = std.fmt.bufPrint(&sock_path, "{s}/{d}.sock", .{ wdl, me }) catch "";
            sock_path[sl.len] = 0;
            const sr = linux.socket(linux.AF.UNIX, linux.SOCK.STREAM | linux.SOCK.CLOEXEC, 0);
            if (linux.errno(sr) == .SUCCESS and sl.len > 0 and sl.len < 100) {
                var sa = linux.sockaddr.un{ .path = [_]u8{0} ** 108 };
                @memcpy(sa.path[0..sl.len], sl);
                _ = linux.unlink(&sock_path);
                const old = linux.syscall1(.umask, 0o177);
                const br = linux.bind(@intCast(sr), @ptrCast(&sa), @sizeOf(linux.sockaddr.un));
                _ = linux.syscall1(.umask, old);
                if (linux.errno(br) == .SUCCESS and linux.errno(linux.listen(@intCast(sr), 4)) == .SUCCESS) listen_fd = @intCast(sr);
            }
        }
    }
    defer if (listen_fd >= 0) {
        _ = linux.unlink(&sock_path);
    };

    var clients: [4]?Client = [_]?Client{null} ** 4;
    var fds: [8]linux.pollfd = undefined;
    var buf: [16384]u8 = undefined;
    var last_push: i64 = 0;
    var stdin_open = true;
    var exit_code: u8 = 0;

    main: while (true) {
        var nf: usize = 0;
        fds[nf] = .{ .fd = if (stdin_open) 0 else -1, .events = linux.POLL.IN, .revents = 0 };
        nf += 1;
        fds[nf] = .{ .fd = master, .events = linux.POLL.IN, .revents = 0 };
        nf += 1;
        fds[nf] = .{ .fd = listen_fd, .events = linux.POLL.IN, .revents = 0 };
        nf += 1;
        for (clients) |mc| {
            fds[nf] = .{ .fd = if (mc) |c| c.fd else -1, .events = linux.POLL.IN, .revents = 0 };
            nf += 1;
        }
        _ = linux.poll(&fds, @intCast(nf), 40);

        // keep the pty the size of the real window
        if (interactive) if (winsize(0)) |w| {
            if (w.col != ws.col or w.row != ws.row) {
                ws = w;
                _ = linux.ioctl(master, linux.T.IOCSWINSZ, @intFromPtr(&ws));
                term.resize(ws.col, ws.row);
            }
        };

        if (fds[0].revents & (linux.POLL.IN | linux.POLL.HUP) != 0) {
            const n = linux.read(0, &buf, buf.len);
            if (linux.errno(n) == .SUCCESS and n > 0) {
                _ = writeAllFd(master, buf[0..n]);
            } else if (linux.errno(n) != .INTR and linux.errno(n) != .AGAIN) {
                stdin_open = false;
                if (interactive) {
                    _ = linux.kill(child, linux.SIG.HUP); // the window went away
                    break :main;
                }
            }
        }
        if (fds[1].revents & (linux.POLL.IN | linux.POLL.HUP) != 0) {
            const n = linux.read(master, &buf, buf.len);
            if (linux.errno(n) != .SUCCESS or n == 0) {
                if (linux.errno(n) != .INTR and linux.errno(n) != .AGAIN) break :main;
            } else {
                tagged.clearRetainingCapacity();
                tag.feed(buf[0..n], &tagged);
                _ = writeAllFd(1, tagged.items);
                term.feed(buf[0..n]);
            }
        }
        if (listen_fd >= 0 and fds[2].revents & linux.POLL.IN != 0) {
            const ar = linux.accept4(listen_fd, null, null, linux.SOCK.CLOEXEC);
            if (linux.errno(ar) == .SUCCESS) {
                const cfd: i32 = @intCast(ar);
                var placed = false;
                for (&clients) |*slot| {
                    if (slot.* == null) {
                        slot.* = Client{ .fd = cfd };
                        placed = true;
                        var tv = linux.timeval{ .sec = 0, .usec = 300_000 };
                        _ = linux.setsockopt(cfd, linux.SOL.SOCKET, linux.SO.SNDTIMEO, @ptrCast(&tv), @sizeOf(linux.timeval));
                        break;
                    }
                }
                if (!placed) _ = linux.close(cfd);
            }
        }
        for (&clients, 0..) |*slot, ci| {
            const c = &(slot.* orelse continue);
            if (fds[3 + ci].revents & (linux.POLL.IN | linux.POLL.HUP | linux.POLL.ERR) == 0) continue;
            const n = linux.read(c.fd, c.buf[c.len..].ptr, c.buf.len - c.len);
            if (linux.errno(n) != .SUCCESS or n == 0) {
                _ = linux.close(c.fd);
                slot.* = null;
                continue;
            }
            c.len += n;
            var drop = false;
            while (c.len >= 5) {
                const plen = std.mem.readInt(u32, c.buf[1..5], .little);
                if (plen > c.buf.len - 5) {
                    drop = true;
                    break;
                }
                if (c.len < 5 + plen) break;
                const payload = c.buf[5..][0..plen];
                switch (c.buf[0]) {
                    'G', 'W' => {
                        c.back = if (plen >= 2) std.mem.readInt(u16, payload[0..2], .little) else 0;
                        c.watching = c.buf[0] == 'W';
                        if (!sendSnapshot(c, &term, me, cmdline.items)) drop = true;
                    },
                    'I' => _ = writeAllFd(master, payload),
                    else => {},
                }
                const used = 5 + plen;
                std.mem.copyForwards(u8, c.buf[0 .. c.len - used], c.buf[used..c.len]);
                c.len -= used;
                if (drop) break;
            }
            if (drop) {
                _ = linux.close(c.fd);
                slot.* = null;
            }
        }

        // push changes to watchers, at most about every 40 ms
        var now: linux.timespec = undefined;
        _ = linux.clock_gettime(.MONOTONIC, &now);
        const ms = now.sec * 1000 + @divTrunc(now.nsec, 1_000_000);
        if (ms - last_push >= 40) {
            for (&clients) |*slot| {
                const c = &(slot.* orelse continue);
                if (c.watching and c.sent != term.seq) {
                    if (!sendSnapshot(c, &term, me, cmdline.items)) {
                        _ = linux.close(c.fd);
                        slot.* = null;
                    }
                }
            }
            last_push = ms;
        }

        var status: u32 = 0;
        const wr = linux.waitpid(child, &status, linux.W.NOHANG);
        if (linux.errno(wr) == .SUCCESS and wr == @as(usize, @intCast(child))) {
            // drain what the command printed last
            while (true) {
                const n = linux.read(master, &buf, buf.len);
                if (linux.errno(n) != .SUCCESS or n == 0) break;
                _ = writeAllFd(1, buf[0..n]);
            }
            exit_code = if (linux.W.IFEXITED(status)) linux.W.EXITSTATUS(status) else 1;
            break :main;
        }
    }
    for (clients) |mc| if (mc) |c| {
        _ = linux.close(c.fd);
    };
    restore();
    if (listen_fd >= 0) _ = linux.unlink(&sock_path); // exit() skips the defers
    linux.exit(exit_code);
}

// ---- tests (zig test pty.zig) ----

fn testTerm(cols: usize, rows: usize) Term {
    return Term.init(-1, cols, rows);
}

test "colours, attributes and reset" {
    var t = testTerm(20, 4);
    t.feed("\x1b[1;31mA\x1b[0mB\x1b[38;2;1;2;3mC\x1b[38;5;200mD");
    const r = t.grid().row(0);
    try std.testing.expectEqual(@as(i32, 1), r[0].fg);
    try std.testing.expectEqual(@as(u8, BOLD), r[0].at);
    try std.testing.expectEqual(@as(i32, -1), r[1].fg);
    try std.testing.expectEqual(@as(i32, 256 + 0x010203), r[2].fg);
    try std.testing.expectEqual(@as(i32, 200), r[3].fg);
}

test "text wraps and scrolls into the scrollback" {
    var t = testTerm(5, 2);
    t.feed("abcdefgh\r\nxyz\r\nlast");
    try std.testing.expect(t.back_len >= 1);
    try std.testing.expectEqual(@as(u21, 'x'), t.grid().row(0)[0].cp);
    try std.testing.expectEqual(@as(u21, 'l'), t.grid().row(1)[0].cp);
}

test "cursor movement and erase" {
    var t = testTerm(10, 3);
    t.feed("hello\x1b[2;3Hxx\x1b[1;1H\x1b[K");
    try std.testing.expectEqual(@as(u21, ' '), t.grid().row(0)[0].cp);
    try std.testing.expectEqual(@as(u21, 'x'), t.grid().row(1)[2].cp);
}

test "wide characters take two cells and UTF-8 is decoded" {
    var t = testTerm(10, 2);
    t.feed("日é");
    try std.testing.expectEqual(@as(u21, 0x65E5), t.grid().row(0)[0].cp);
    try std.testing.expectEqual(@as(u21, 0), t.grid().row(0)[1].cp);
    try std.testing.expectEqual(@as(u21, 'é'), t.grid().row(0)[2].cp);
}

test "the alternate screen leaves the main one untouched" {
    var t = testTerm(10, 2);
    t.feed("main\x1b[?1049hALT\x1b[?1049l");
    try std.testing.expect(!t.use_alt);
    try std.testing.expectEqual(@as(u21, 'm'), t.grid().row(0)[0].cp);
}

test "titles from OSC 0 and 2, ended by BEL or ST" {
    var t = testTerm(10, 2);
    t.feed("\x1b]0;one\x07");
    try std.testing.expectEqualStrings("one", t.title[0..t.title_len]);
    t.feed("\x1b]2;two\x1b\\");
    try std.testing.expectEqualStrings("two", t.title[0..t.title_len]);
}

test "broken and hostile input does not crash" {
    var t = testTerm(8, 3);
    t.feed("\x1b[99999999999999999;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;H\x1b[?\x1b]0;" ++ "x" ** 2000 ++ "\xff\xfe\xc3\x1b[1000A\x1b[1000B\x1b[1000C\x1b[1000D\x1b[0;0r\x1b[3;1r\x1b[5L\x1b[100M\x1b[100P\x1b[100@\x1bc");
    t.resize(3, 2);
    t.feed("still alive");
}

test "random bytes never trip a safety check" {
    var prng = std.Random.DefaultPrng.init(0x686f6d65);
    const rnd = prng.random();
    var t = testTerm(13, 5);
    var buf: [4096]u8 = undefined;
    for (0..300) |i| {
        rnd.bytes(&buf);
        // bias towards escape sequences so the parser's states are actually reached
        const alphabet = "\x1b[;?0123456789mHJKLMPr@]\\";
        for (&buf) |*b| {
            if (rnd.uintLessThan(u8, 6) == 0) b.* = alphabet[rnd.uintLessThan(usize, alphabet.len)];
        }
        t.feed(&buf);
        if (i % 50 == 0) t.resize(3 + rnd.uintLessThan(usize, 30), 2 + rnd.uintLessThan(usize, 12));
    }
}

test "title tagger names the window and survives split input" {
    var tg = Tagger.init(0xA5C3F0);
    var out: std.ArrayList(u8) = .empty;
    defer out.deinit(A);
    // plain text and other escapes pass through untouched
    tg.feed("hi \x1b[31mred\x1b[0m\x1b]7;file:///x\x07!", &out);
    try std.testing.expectEqualStrings("hi \x1b[31mred\x1b[0m\x1b]7;file:///x\x07!", out.items);
    out.clearRetainingCapacity();
    // a title set in two pieces, ended by BEL
    tg.feed("\x1b]0;my", &out);
    tg.feed(" title\x07after", &out);
    var mb: [80]u8 = undefined;
    const want = try std.fmt.allocPrint(A, "\x1b]2;my title{s}\x07after", .{tg.marker(&mb)});
    defer A.free(want);
    try std.testing.expectEqualStrings(want, out.items);
    out.clearRetainingCapacity();
    // ST terminator is kept
    tg.feed("\x1b]2;x\x1b\\", &out);
    const want2 = try std.fmt.allocPrint(A, "\x1b]2;x{s}\x1b\\", .{tg.marker(&mb)});
    defer A.free(want2);
    try std.testing.expectEqualStrings(want2, out.items);
    // the name holds the 24 bits: 0xA5C3F0 = 1010 0101 1100 0011 1111 0000
    const m = tg.marker(&mb);
    try std.testing.expectEqual(@as(usize, 78), m.len);
    var id: u24 = 0;
    var i: usize = 3;
    while (i < 3 + 72) : (i += 3) id = id << 1 | @as(u24, if (m[i + 2] == 0x8c) 1 else 0);
    try std.testing.expectEqual(@as(u24, 0xA5C3F0), id);
}

test "title tagger never eats bytes it does not understand" {
    var tg = Tagger.init(1);
    var out: std.ArrayList(u8) = .empty;
    defer out.deinit(A);
    var prng = std.Random.DefaultPrng.init(7);
    var input: [4096]u8 = undefined;
    for (0..50) |_| {
        prng.random().bytes(&input);
        // sprinkle escapes so the state machine is exercised
        for (0..40) |k| input[prng.random().uintLessThan(usize, input.len)] = if (k % 3 == 0) 0x1b else if (k % 3 == 1) ']' else ';';
        out.clearRetainingCapacity();
        tg.feed(&input, &out);
        try std.testing.expect(out.items.len + 1100 >= input.len);
    }
}

test "mouse mode is tracked for programs that ask for it" {
    var t = testTerm(20, 4);
    try std.testing.expectEqual(@as(u16, 0), t.mouse);
    t.feed("\x1b[?1002h\x1b[?1006h");
    try std.testing.expectEqual(@as(u16, 1002), t.mouse);
    try std.testing.expect(t.mouse_sgr);
    t.feed("\x1b[?1002l");
    try std.testing.expectEqual(@as(u16, 0), t.mouse);
}
