fn main() {
    cc::Build::new().file("csrc/v4l2ctl.c").warnings(true).compile("v4l2ctl");
    println!("cargo:rerun-if-changed=csrc/v4l2ctl.c");
}
