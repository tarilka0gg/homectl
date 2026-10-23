const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const exe = b.addExecutable(.{
        .name = "homectl-send",
        .root_module = b.createModule(.{
            .root_source_file = b.path("send.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    b.installArtifact(exe);

    const pty = b.addExecutable(.{
        .name = "homectl-pty",
        .root_module = b.createModule(.{
            .root_source_file = b.path("pty.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    b.installArtifact(pty);
}
