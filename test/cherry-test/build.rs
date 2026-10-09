use std::fs;
use std::path::PathBuf;

fn main() {
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let assets = manifest.join("assets");
    let review_prompt = assets.join("review.md");
    let repo_root = manifest.join("../..");
    let cursor_prompt = repo_root.join(".cursor/prompts/review.md");
    println!(
        "cargo:rerun-if-changed={}",
        manifest.join("build.rs").display()
    );
    println!("cargo:rerun-if-changed={}", review_prompt.display());
    println!("cargo:rerun-if-changed={}", cursor_prompt.display());
    fs::create_dir_all(&assets).expect("create assets dir");
    if !review_prompt.is_file() {
        if cursor_prompt.is_file() {
            fs::copy(&cursor_prompt, &review_prompt).expect("copy review prompt");
        }
    }
}
