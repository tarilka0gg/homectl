fn main() {
    cc::Build::new().file("csrc/v4l2ctl.c").file("csrc/pty.c").warnings(true).compile("hcshim");
    println!("cargo:rustc-link-lib=util");
    println!("cargo:rerun-if-changed=csrc");
}
