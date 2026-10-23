//! homectl-adopt — runs beside the daemon and moves terminals that are already open into homectl-pty, so they get mirrored too.
//!
//! Every few seconds it looks for fish shells of this user that sit idle at their prompt (nothing running in the foreground, no
//! keyboard input for a while) and are not yet inside the wrapper, borrows the terminal's pty master with pidfd_getfd, and types
//! `homectl-attach` into it. The fish function (packaging/shell/homectl.fish) then swaps the shell for one inside homectl-pty.
//! Each shell is tried once. Needs the kernel to let the same user duplicate fds of its own processes (yama ptrace_scope 0).
//! Off with HOMECTL_NO_TERMINAL=1 or HOMECTL_NO_ADOPT=1. `--once` scans a single time and prints what it did.
const std = @import("std");
const linux = std.os.linux;

const A = std.heap.page_allocator;
const idle_secs = 8;
const SYS_pidfd_open = 434;
const SYS_pidfd_getfd = 438;

fn readFile(path: [:0]const u8, buf: []u8) []u8 {
    const fd = linux.open(path, .{ .ACCMODE = .RDONLY, .CLOEXEC = true }, 0);
    if (linux.errno(fd) != .SUCCESS) return buf[0..0];
    defer _ = linux.close(@intCast(fd));
    var len: usize = 0;
    while (len < buf.len) {
        const n = linux.read(@intCast(fd), buf.ptr + len, buf.len - len);
        if (linux.errno(n) != .SUCCESS or n == 0) break;
        len += n;
    }
    return buf[0..len];
}

fn list(path: [:0]const u8, out: *std.ArrayList([]u8)) void {
    const fd = linux.open(path, .{ .ACCMODE = .RDONLY, .DIRECTORY = true, .CLOEXEC = true }, 0);
    if (linux.errno(fd) != .SUCCESS) return;
    defer _ = linux.close(@intCast(fd));
    var buf: [8192]u8 = undefined;
    while (true) {
        const n = linux.getdents64(@intCast(fd), &buf, buf.len);
        if (linux.errno(n) != .SUCCESS or n == 0) return;
        var off: usize = 0;
        while (off < n) {
            const d: *align(1) linux.dirent64 = @ptrCast(&buf[off]);
            const name = std.mem.sliceTo(@as([*:0]u8, @ptrCast(&d.name)), 0);
            if (name.len > 0 and name[0] != '.') out.append(A, A.dupe(u8, name) catch return) catch return;
            off += d.reclen;
        }
    }
}

fn pathz(comptime fmt: []const u8, args: anytype) [:0]u8 {
    return std.fmt.allocPrintSentinel(A, fmt, args, 0) catch @panic("oom");
}

fn isNum(s: []const u8) bool {
    if (s.len == 0) return false;
    for (s) |c| if (c < '0' or c > '9') return false;
    return true;
}

/// Fields of /proc/PID/stat after the command name: state ppid pgrp session tty_nr tpgid.
const Stat = struct { pgrp: i64, tty: i64, tpgid: i64 };

fn procStat(pid: []const u8) ?Stat {
    var buf: [1024]u8 = undefined;
    const s = readFile(pathz("/proc/{s}/stat", .{pid}), &buf);
    const rp = std.mem.lastIndexOfScalar(u8, s, ')') orelse return null;
    var it = std.mem.tokenizeScalar(u8, s[rp + 1 ..], ' ');
    _ = it.next() orelse return null; // state
    _ = it.next() orelse return null; // ppid
    const pgrp = std.fmt.parseInt(i64, it.next() orelse return null, 10) catch return null;
    _ = it.next() orelse return null; // session
    const tty = std.fmt.parseInt(i64, it.next() orelse return null, 10) catch return null;
    const tpgid = std.fmt.parseInt(i64, it.next() orelse return null, 10) catch return null;
    return .{ .pgrp = pgrp, .tty = tty, .tpgid = tpgid };
}

fn ownedByMe(pid: []const u8) bool {
    var stx: linux.Statx = undefined;
    if (linux.errno(linux.statx(linux.AT.FDCWD, pathz("/proc/{s}", .{pid}), 0, .{ .UID = true }, &stx)) != .SUCCESS) return false;
    return stx.uid == linux.getuid();
}

fn hasEnv(pid: []const u8, name: []const u8) bool {
    var buf: [65536]u8 = undefined;
    const e = readFile(pathz("/proc/{s}/environ", .{pid}), &buf);
    var it = std.mem.splitScalar(u8, e, 0);
    while (it.next()) |kv| if (kv.len > name.len and kv[name.len] == '=' and std.mem.startsWith(u8, kv, name)) return true;
    return false;
}

/// Seconds since the keyboard last gave this pty input (the slave's atime), or null if unknown.
fn idleFor(pts: i64) ?i64 {
    var stx: linux.Statx = undefined;
    if (linux.errno(linux.statx(linux.AT.FDCWD, pathz("/dev/pts/{d}", .{pts}), 0, .{ .ATIME = true }, &stx)) != .SUCCESS) return null;
    var ts: linux.timespec = undefined;
    _ = linux.clock_gettime(.REALTIME, &ts);
    return @as(i64, ts.sec) - stx.atime.sec;
}

const Master = struct { pid: []const u8, fd: []const u8 };

/// The process that holds the master side of /dev/pts/N (the terminal emulator, tmux, …).
fn findMaster(pts: i64, pids: []const []u8) ?Master {
    for (pids) |pid| {
        if (!ownedByMe(pid)) continue;
        var fds: std.ArrayList([]u8) = .empty;
        list(pathz("/proc/{s}/fd", .{pid}), &fds);
        for (fds.items) |fd| {
            var lb: [64]u8 = undefined;
            const n = linux.readlink(pathz("/proc/{s}/fd/{s}", .{ pid, fd }), &lb, lb.len);
            if (linux.errno(n) != .SUCCESS or !std.mem.endsWith(u8, lb[0..n], "ptmx")) continue;
            var ib: [512]u8 = undefined;
            const info = readFile(pathz("/proc/{s}/fdinfo/{s}", .{ pid, fd }), &ib);
            const k = std.mem.indexOf(u8, info, "tty-index:") orelse continue;
            const rest = std.mem.trim(u8, info[k + 10 ..], " \t");
            const end = std.mem.indexOfAny(u8, rest, "\n \t") orelse rest.len;
            if ((std.fmt.parseInt(i64, rest[0..end], 10) catch continue) == pts) return .{ .pid = pid, .fd = fd };
        }
    }
    return null;
}

fn typeInto(m: Master, text: []const u8) bool {
    const ppid = std.fmt.parseInt(i32, m.pid, 10) catch return false;
    const pfd = std.fmt.parseInt(i32, m.fd, 10) catch return false;
    const pidfd = linux.syscall2(@enumFromInt(SYS_pidfd_open), @intCast(ppid), 0);
    if (linux.errno(pidfd) != .SUCCESS) return false;
    defer _ = linux.close(@intCast(pidfd));
    const got = linux.syscall3(@enumFromInt(SYS_pidfd_getfd), pidfd, @intCast(pfd), 0);
    if (linux.errno(got) != .SUCCESS) return false;
    defer _ = linux.close(@intCast(got));
    return linux.errno(linux.write(@intCast(got), text.ptr, text.len)) == .SUCCESS;
}

var tried: std.AutoHashMap(i64, void) = undefined;

fn scan(verbose: bool) void {
    var pids: std.ArrayList([]u8) = .empty;
    list("/proc", &pids);
    var live: std.ArrayList([]u8) = .empty;
    for (pids.items) |p| if (isNum(p)) live.append(A, p) catch return;
    for (live.items) |pid| {
        var cb: [32]u8 = undefined;
        if (!std.mem.eql(u8, std.mem.trim(u8, readFile(pathz("/proc/{s}/comm", .{pid}), &cb), "\n"), "fish")) continue;
        const key = std.fmt.parseInt(i64, pid, 10) catch continue;
        if (tried.contains(key)) continue;
        const st = procStat(pid) orelse continue;
        if (st.tty == 0 or (st.tty >> 8) & 0xff != 136) continue; // not on a pty
        if (st.pgrp != st.tpgid) continue; // something else runs in the foreground
        if (!ownedByMe(pid) or hasEnv(pid, "HOMECTL_WINDOW") or hasEnv(pid, "HOMECTL_NO_MIRROR")) continue;
        const pts = (st.tty & 0xff) | ((st.tty >> 12) & 0xfff00);
        const idle = idleFor(pts) orelse continue;
        if (idle < idle_secs) continue; // someone may be typing
        tried.put(key, {}) catch return;
        const m = findMaster(pts, live.items) orelse {
            if (verbose) std.debug.print("fish {s} on pts/{d}: no pty master found\n", .{ pid, pts });
            continue;
        };
        const ok = typeInto(m, "homectl-attach\r");
        if (verbose) std.debug.print("fish {s} on pts/{d}: master in process {s} fd {s}: {s}\n", .{ pid, pts, m.pid, m.fd, if (ok) "adopted" else "could not type" });
    }
}

pub fn main(init: std.process.Init) !void {
    if (std.c.getenv("HOMECTL_NO_TERMINAL") != null or std.c.getenv("HOMECTL_NO_ADOPT") != null) return;
    var once = false;
    var args = init.minimal.args.iterate();
    _ = args.skip();
    while (args.next()) |a| {
        if (std.mem.eql(u8, a, "--once")) once = true;
        if (std.mem.eql(u8, a, "-h") or std.mem.eql(u8, a, "--help")) {
            std.debug.print("usage: homectl-adopt [--once]\n  moves idle fish terminals into homectl-pty so they can be mirrored\n", .{});
            return;
        }
    }
    tried = std.AutoHashMap(i64, void).init(A);
    while (true) {
        scan(once);
        if (once) return;
        var ts = linux.timespec{ .sec = 3, .nsec = 0 };
        _ = linux.nanosleep(&ts, null);
    }
}
