const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const speexdsp_sources = [_][]const u8{
        "speexdsp/libspeexdsp/mdf.c",
        "speexdsp/libspeexdsp/preprocess.c",
        "speexdsp/libspeexdsp/fftwrap.c",
        "speexdsp/libspeexdsp/kiss_fft.c",
        "speexdsp/libspeexdsp/kiss_fftr.c",
        "speexdsp/libspeexdsp/filterbank.c",
        "speexdsp/libspeexdsp/smallft.c",
    };

    const wrapper_sources = [_][]const u8{
        "reelthyme_aec.c",
    };

    const c_flags = [_][]const u8{
        "-DFLOATING_POINT",
        "-DUSE_KISS_FFT",
        "-DEXPORT=",
        "-std=c99",
        "-fvisibility=hidden",
    };

    const mod = b.createModule(.{
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });

    mod.addCSourceFiles(.{
        .files = &speexdsp_sources ++ &wrapper_sources,
        .flags = &c_flags,
    });

    // Include paths: speexdsp internal headers + public headers
    mod.addIncludePath(b.path("speexdsp/libspeexdsp"));
    mod.addIncludePath(b.path("speexdsp/include"));
    // For the wrapper's #include "speexdsp/include/speex/..."
    mod.addIncludePath(b.path("."));

    const lib = b.addLibrary(.{
        .linkage = .dynamic,
        .name = "reelthyme_aec",
        .root_module = mod,
    });

    b.installArtifact(lib);
}
