use serde::Serialize;
use serde_json::{json, Value};
use std::cmp::Ordering;
use std::collections::{BTreeMap, BTreeSet};
use std::error::Error;
use std::path::{Path, PathBuf};
use std::{env, fs};

type Outcome<T> = Result<T, Box<dyn Error>>;

#[derive(Serialize)]
struct DeclarationOrder {
    protocol: u32,
    project: String,
    package: String,
    phase: String,
    root: String,
    env: BTreeMap<String, String>,
    options: Value,
    coverage: bool,
    attachments: Vec<String>,
    message: Option<String>,
}

fn main() -> Outcome<()> {
    let arguments: Vec<String> = env::args().skip(1).collect();
    if arguments.first().map(String::as_str) == Some("assets") {
        let output = arguments
            .get(1)
            .map(PathBuf::from)
            .ok_or("usage: cherry-test-parity-vectors assets <output-dir>")?;
        return write_report_shell_assets(&output);
    }
    let output = arguments
        .first()
        .map(PathBuf::from)
        .ok_or("usage: cherry-test-parity-vectors <output-dir>")?;
    fs::create_dir_all(&output)?;
    write_vector_file(&output, "floats.json", &float_vectors())?;
    write_vector_file(&output, "strings.json", &string_vectors())?;
    write_vector_file(&output, "whitespace.json", &whitespace_vectors())?;
    write_vector_file(&output, "ordering.json", &ordering_vectors())?;
    write_vector_file(&output, "serde-json.json", &serde_json_vectors()?)?;
    Ok(())
}

fn write_report_shell_assets(directory: &Path) -> Outcome<()> {
    use cherry_test_common::report_shell;
    fs::create_dir_all(directory)?;
    fs::write(directory.join("report-styles.css"), report_shell::REPORT_HTML_STYLES)?;
    fs::write(directory.join("report-theme.js"), report_shell::REPORT_HTML_THEME_SCRIPT)?;
    fs::write(
        directory.join("report-test-filter.js"),
        report_shell::REPORT_HTML_TEST_FILTER_SCRIPT,
    )?;
    fs::write(directory.join("report-jump.js"), report_shell::REPORT_HTML_JUMP_SCRIPT)?;
    Ok(())
}

fn write_vector_file(directory: &Path, name: &str, value: &Value) -> Outcome<()> {
    let mut bytes = serde_json::to_vec_pretty(value)?;
    bytes.push(b'\n');
    fs::write(directory.join(name), bytes)?;
    Ok(())
}

fn float_vectors() -> Value {
    let entries: Vec<Value> = float_inputs()
        .into_iter()
        .map(|value| {
            json!({
                "bits": format!("{:016x}", value.to_bits()),
                "display": format!("{value}"),
                "fixed": [
                    format!("{value:.0}"),
                    format!("{value:.1}"),
                    format!("{value:.2}"),
                    format!("{value:.3}"),
                ],
            })
        })
        .collect();
    json!({ "floats": entries })
}

fn float_inputs() -> Vec<f64> {
    let mut candidates = vec![
        0.0,
        -0.0,
        f64::NAN,
        f64::INFINITY,
        f64::NEG_INFINITY,
        f64::MIN_POSITIVE,
        f64::MAX,
        f64::MIN,
        f64::EPSILON,
        5e-324,
        1e21,
        1e-7,
        1e15,
        1e16,
        1e17,
        9007199254740993.0,
        123456789012345678.0,
        0.1,
        0.2,
        0.3,
        1.0 / 3.0,
        2.0 / 3.0,
        123456789.125,
        2.675,
        1.005,
        1.115,
        0.045,
        99.95,
        99.995,
        0.0005,
        0.00005,
        1e-5,
        1e-6,
        -0.04,
        -0.05,
        -0.5,
        -1.5,
    ];
    for eighths in -40..=40 {
        candidates.push(f64::from(eighths) / 8.0);
    }
    for found in 1..=64u32 {
        for hit in 0..=found {
            candidates.push((f64::from(hit) / f64::from(found)) * 100.0);
        }
    }
    for complexity in 1..=30u32 {
        for found in 1..=12u32 {
            for hit in 0..=found {
                let coverage = f64::from(hit) / f64::from(found);
                let gap = 1.0 - coverage;
                let complexity = f64::from(complexity);
                candidates.push(complexity * complexity * gap * gap * gap + complexity);
                candidates.push(complexity * gap + complexity / 30.0);
            }
        }
    }
    let mut state = 0x9e37_79b9_7f4a_7c15_u64;
    for _ in 0..1500 {
        state = next_state(state);
        let value = f64::from_bits(state);
        if value.is_finite() {
            candidates.push(value);
        }
    }
    for _ in 0..1500 {
        state = next_state(state);
        let unit = (state >> 11) as f64 / (1u64 << 53) as f64;
        state = next_state(state);
        let exponent = i32::try_from(state % 12).unwrap_or(0) - 4;
        candidates.push(unit * 10f64.powi(exponent));
    }
    let mut seen = BTreeSet::new();
    candidates
        .into_iter()
        .filter(|value| seen.insert(value.to_bits()))
        .collect()
}

fn next_state(state: u64) -> u64 {
    state
        .wrapping_mul(6_364_136_223_846_793_005)
        .wrapping_add(1_442_695_040_888_963_407)
}

fn string_vectors() -> Value {
    let entries: Vec<Value> = string_inputs()
        .iter()
        .map(|input| {
            json!({
                "input": input,
                "trim": input.trim(),
                "trim_start": input.trim_start(),
                "lines": input.lines().collect::<Vec<_>>(),
                "split_whitespace": input.split_whitespace().collect::<Vec<_>>(),
                "chars_count": input.chars().count(),
                "utf8_len": input.len(),
                "to_uppercase": input.to_uppercase(),
                "to_lowercase": input.to_lowercase(),
                "to_ascii_lowercase": input.to_ascii_lowercase(),
            })
        })
        .collect();
    json!({ "strings": entries })
}

fn string_inputs() -> Vec<String> {
    let mut inputs: Vec<String> = [
        "",
        " ",
        "a",
        " a ",
        "\ta\n",
        "a\r\nb",
        "a\rb",
        "a\r",
        "a\n",
        "a\n\n",
        "\n",
        "\r\n",
        "\r",
        "a\r\r\n",
        "a\n\rb",
        "line1\nline2\r\nline3",
        "  two  words\there ",
        "x\u{0}y",
        "ß",
        "ŉ",
        "ǅ",
        "İ",
        "ı",
        "ﬃ",
        "ΑΣ",
        "ΑΣ Α",
        "Σ",
        "aΣb",
        "aΣ",
        "straße",
        "Ǆ",
        "ǈ",
        "ᾳ",
        "ǰ",
        "\u{10400}",
        "\u{1e900}",
        "ꭰ",
        "Ꭰ",
        "Ω",
        "K",
        "Å",
        "\u{1f600} smile",
        "MiXeD Case 123",
    ]
    .iter()
    .map(|text| text.to_string())
    .collect();
    let probes = [
        0x09u32, 0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x85, 0xa0, 0x1680, 0x180e,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a,
        0x200b, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff,
    ];
    for character in probes
        .iter()
        .filter_map(|code_point| char::from_u32(*code_point))
    {
        inputs.push(format!("{character}x{character}y{character}"));
        inputs.push(format!("{character}{character}"));
    }
    inputs
}

fn whitespace_vectors() -> Value {
    let mut ranges: Vec<[u32; 2]> = Vec::new();
    for character in (0..=u32::from(char::MAX)).filter_map(char::from_u32) {
        if !character.is_whitespace() {
            continue;
        }
        let code_point = u32::from(character);
        match ranges.last_mut() {
            Some(range) if range[1] + 1 == code_point => range[1] = code_point,
            _ => ranges.push([code_point, code_point]),
        }
    }
    json!({ "whitespace_ranges": ranges })
}

fn ordering_vectors() -> Value {
    let strings: Vec<&str> = vec![
        "",
        "a",
        "A",
        "b",
        "aa",
        "a\u{0}",
        "é",
        "e\u{301}",
        "\u{e000}",
        "\u{ffff}",
        "\u{10000}",
        "\u{1f600}",
        "z",
        "_",
        "-",
        ".",
        "/",
        "0",
        "9",
        "ab",
        "a b",
        "Z",
        "~",
        "\u{7f}",
        "\u{80}",
        "\u{7ff}",
        "\u{800}",
        "\u{fffd}",
        "\u{10ffff}",
    ];
    let paths: Vec<&str> = vec![
        "a/b",
        "a.b",
        "a-b",
        "a b",
        "a/b/c",
        "a/b.c",
        "a/",
        "a",
        "./a",
        "a//b",
        "a/./b",
        "a/../b",
        "/a",
        "/a/b",
        "b",
        "a/b/",
        "ab",
        "a/bc",
        "a/b c",
        "src/lib.rs",
        "src/lib/mod.rs",
        "src-lib.rs",
        "src/a.rs",
        "src.rs",
        "é/a",
        "\u{10000}/a",
        "\u{e000}/a",
        "",
        ".",
        "..",
        "a/b/../c",
        "./",
        "/",
        "//a",
        "/./a",
        "../a",
        "a/.",
        "a/..",
        "..a",
        ".a",
        "a/.b",
        "src/report/html.rs",
        "src/report.rs",
        "src/report-html.rs",
        "tests/a.rs",
        "tests.rs",
    ];
    let mut sorted_strings = strings.clone();
    sorted_strings.sort();
    let mut sorted_paths = paths.clone();
    sorted_paths.sort_by(|left, right| Path::new(left).cmp(Path::new(right)));
    json!({
        "strings": {
            "input": strings,
            "sorted": sorted_strings,
            "compare": pairwise(&strings, |left, right| left.cmp(right)),
        },
        "paths": {
            "input": paths,
            "sorted": sorted_paths,
            "compare": pairwise(&paths, |left, right| Path::new(left).cmp(Path::new(right))),
        },
    })
}

fn pairwise(items: &[&str], compare: impl Fn(&str, &str) -> Ordering) -> Vec<[i64; 3]> {
    let mut results = Vec::new();
    for (left_index, left) in items.iter().enumerate() {
        for (right_index, right) in items.iter().enumerate() {
            let ordering = match compare(left, right) {
                Ordering::Less => -1,
                Ordering::Equal => 0,
                Ordering::Greater => 1,
            };
            results.push([
                i64::try_from(left_index).unwrap_or(-1),
                i64::try_from(right_index).unwrap_or(-1),
                ordering,
            ]);
        }
    }
    results
}

fn serde_json_vectors() -> Outcome<Value> {
    let control_characters: String = (0u32..0x20)
        .chain([0x7f])
        .filter_map(char::from_u32)
        .collect();
    let values = vec![
        json!(null),
        json!(true),
        json!(false),
        json!(0),
        json!(-1),
        json!(i64::MIN),
        json!(i64::MAX),
        json!(u64::MAX),
        json!(0.0),
        json!(-0.0),
        json!(1.0),
        json!(1.5),
        json!(0.1),
        json!(1e21),
        json!(1e-7),
        json!(1e15),
        json!(1e16),
        json!(1e17),
        json!(123456789012345678.0),
        json!(5e-324),
        json!(f64::MAX),
        json!(f64::MIN_POSITIVE),
        json!(1.0 / 3.0),
        json!(100.0),
        json!(-2.5e-5),
        json!(0.000001),
        json!(0.00001),
        json!(""),
        json!("plain"),
        json!(control_characters),
        json!("quote\"backslash\\slash/"),
        json!("\u{2028}\u{2029}\u{7f}é\u{1f600}"),
        json!([]),
        json!({}),
        json!([1, [2, []], {}]),
        json!({"b": 1, "a": {"d": [], "c": {}}, "B": "x", "_": null, "é": 1, "\u{10000}": 2, "\u{e000}": 3}),
        json!({
            "tests": [{"name": "t1", "status": "passed", "duration_ms": 12, "message": null}],
            "success": true,
            "coverage": ["/abs/path/coverage.lcov"],
            "diagnostics": [],
        }),
    ];
    let mut entries = Vec::new();
    for value in &values {
        entries.push(encoding_vector(value, value)?);
    }
    let declaration_order = DeclarationOrder {
        protocol: 1,
        project: "mini".to_string(),
        package: "mini-rs-api".to_string(),
        phase: "unit".to_string(),
        root: "/workspace/root".to_string(),
        env: BTreeMap::from([
            (
                "CHERRY_TEST_RUN_ID".to_string(),
                "20261012-101500-0a1b2c".to_string(),
            ),
            ("CHERRY_TEST_PHASE".to_string(), "unit".to_string()),
        ]),
        options: json!({"zeta": 1, "alpha": [true, null]}),
        coverage: true,
        attachments: vec![],
        message: None,
    };
    entries.push(encoding_vector(
        &declaration_order,
        &serde_json::to_value(&declaration_order)?,
    )?);
    Ok(json!({ "values": entries }))
}

fn encoding_vector(value: &impl Serialize, sorted: &Value) -> Outcome<Value> {
    Ok(json!({
        "compact": serde_json::to_string(value)?,
        "pretty": serde_json::to_string_pretty(value)?,
        "sorted_compact": serde_json::to_string(sorted)?,
    }))
}
