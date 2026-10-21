//! homectl-send IP:PORT TOKEN FILE — upload a file to a homectl daemon's inbox.
//! Static, dependency-free (raw syscalls); cross-compiles for Termux/Android
//! (zig build -Dtarget=aarch64-linux-musl). IPv4 only.
const std = @import("std");
const linux = std.os.linux;

fn die(msg: []const u8) noreturn {
    _ = linux.write(2, msg.ptr, msg.len);
    linux.exit(1);
}

fn writeAll(fd: i32, data: []const u8) void {
    var off: usize = 0;
    while (off < data.len) {
        const n = linux.write(fd, data.ptr + off, data.len - off);
        if (linux.errno(n) != .SUCCESS or n == 0) die("write failed\n");
        off += n;
    }
}

pub fn main(init: std.process.Init) !void {
    var args = init.minimal.args.iterate();
    _ = args.skip();
    const hostport = args.next() orelse die("usage: homectl-send IP:PORT TOKEN FILE\n");
    const token = args.next() orelse die("missing TOKEN\n");
    const path = args.next() orelse die("missing FILE\n");

    const colon = std.mem.lastIndexOfScalar(u8, hostport, ':') orelse die("expected IP:PORT\n");
    const addr = std.Io.net.Ip4Address.parse(hostport[0..colon], std.fmt.parseInt(u16, hostport[colon + 1 ..], 10) catch die("bad port\n")) catch die("bad IP\n");

    // File name = last path component, URL-encoded.
    const base = if (std.mem.lastIndexOfScalar(u8, path, '/')) |i| path[i + 1 ..] else path;
    var enc: [512]u8 = undefined;
    var el: usize = 0;
    for (base) |c| {
        const plain = std.ascii.isAlphanumeric(c) or c == '.' or c == '-' or c == '_';
        if (el + 3 > enc.len) die("file name too long\n");
        if (plain) {
            enc[el] = c;
            el += 1;
        } else {
            _ = std.fmt.bufPrint(enc[el..][0..3], "%{X:0>2}", .{c}) catch unreachable;
            el += 3;
        }
    }

    var pathz: [1024:0]u8 = undefined;
    if (path.len >= pathz.len) die("path too long\n");
    @memcpy(pathz[0..path.len], path);
    pathz[path.len] = 0;
    const ffd_r = linux.open(&pathz, .{ .ACCMODE = .RDONLY }, 0);
    if (linux.errno(ffd_r) != .SUCCESS) die("cannot open file\n");
    const ffd: i32 = @intCast(ffd_r);
    var stx: linux.Statx = undefined;
    if (linux.errno(linux.statx(ffd, "", linux.AT.EMPTY_PATH, .{ .SIZE = true }, &stx)) != .SUCCESS) die("stat failed\n");

    const sr = linux.socket(linux.AF.INET, linux.SOCK.STREAM, 0);
    if (linux.errno(sr) != .SUCCESS) die("socket failed\n");
    const sock: i32 = @intCast(sr);
    var sa = linux.sockaddr.in{ .port = std.mem.nativeToBig(u16, addr.port), .addr = @bitCast(addr.bytes) };
    if (linux.errno(linux.connect(sock, @ptrCast(&sa), @sizeOf(linux.sockaddr.in))) != .SUCCESS) die("connect failed\n");

    var hdr: [1400]u8 = undefined;
    const head = std.fmt.bufPrint(&hdr, "POST /api/files/{s} HTTP/1.1\r\nHost: {s}\r\nAuthorization: Bearer {s}\r\nContent-Length: {d}\r\nConnection: close\r\n\r\n", .{ enc[0..el], hostport, token, stx.size }) catch die("header too long\n");
    writeAll(sock, head);

    var buf: [65536]u8 = undefined;
    while (true) {
        const n = linux.read(ffd, &buf, buf.len);
        if (linux.errno(n) != .SUCCESS) die("read failed\n");
        if (n == 0) break;
        writeAll(sock, buf[0..n]);
    }

    const rn = linux.read(sock, &buf, buf.len);
    if (linux.errno(rn) != .SUCCESS or rn < 12) die("no response\n");
    const resp = buf[0..rn];
    _ = linux.write(1, resp.ptr, @min(resp.len, std.mem.indexOfScalar(u8, resp, '\r') orelse resp.len));
    _ = linux.write(1, "\n", 1);
    if (!std.mem.startsWith(u8, resp, "HTTP/1.1 201")) linux.exit(1);
}
