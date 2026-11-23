use std::collections::{BTreeMap, BTreeSet};
use std::io::Write;
use std::path::{Component, Path, PathBuf};

use anyhow::{bail, ensure, Context, Result};
use cherry_test_common::adapter::{AdapterRequest, AdapterResponse};
use serde::{Deserialize, Serialize};

use super::{formats, process};

const REPORT_LIMIT: u64 = 32 * 1024 * 1024;
const PIT_GOAL: &str = "org.pitest:pitest-maven:1.30.2:mutationCoverage@cherry-test-pit";

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct MutationReport {
    pub tool: String,
    pub status: String,
    pub sources: Vec<String>,
    pub mutants: Vec<Mutant>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Mutant {
    pub status: String,
    pub detected: bool,
    pub source: String,
    pub line: u32,
    #[serde(rename = "class")]
    pub class_name: String,
    pub method: String,
    pub mutator: String,
    pub description: String,
    pub killing_test: Option<String>,
}

fn valid_status(status: &str) -> bool {
    matches!(
        status,
        "KILLED"
            | "SURVIVED"
            | "NO_COVERAGE"
            | "TIMED_OUT"
            | "NON_VIABLE"
            | "MEMORY_ERROR"
            | "RUN_ERROR"
            | "EQUIVALENT"
    )
}

fn safe_source(source: &str) -> bool {
    !source.is_empty()
        && Path::new(source)
            .components()
            .all(|part| matches!(part, Component::Normal(_)))
}

pub fn read_report(output: &Path) -> Result<Option<MutationReport>> {
    let path = output.join("mutation.json");
    if !path.try_exists()? {
        return Ok(None);
    }
    ensure!(
        path.canonicalize()?.starts_with(output.canonicalize()?),
        "mutation report escapes adapter output"
    );
    ensure!(
        std::fs::metadata(&path)?.len() <= REPORT_LIMIT,
        "mutation report exceeds 32 MiB"
    );
    let report: MutationReport = serde_json::from_slice(&std::fs::read(path)?)?;
    validate_report(&report)?;
    Ok(Some(report))
}

fn validate_report(report: &MutationReport) -> Result<()> {
    ensure!(report.tool == "pitest", "unknown mutation tool");
    ensure!(
        matches!(report.status.as_str(), "complete" | "no_changes"),
        "invalid mutation report status"
    );
    let sources: BTreeSet<_> = report.sources.iter().collect();
    ensure!(
        sources.len() == report.sources.len() && sources.iter().all(|source| safe_source(source)),
        "invalid mutation source paths"
    );
    if report.status == "no_changes" {
        ensure!(
            report.sources.is_empty() && report.mutants.is_empty(),
            "no_changes report contains mutation results"
        );
    } else {
        ensure!(
            !report.sources.is_empty() && !report.mutants.is_empty(),
            "mutation analysis produced no mutants"
        );
    }
    for mutant in &report.mutants {
        ensure!(
            valid_status(&mutant.status),
            "unknown PIT mutant status {}",
            mutant.status
        );
        ensure!(
            sources.contains(&mutant.source) && mutant.line > 0,
            "invalid mutant source location"
        );
        ensure!(
            !mutant.class_name.is_empty()
                && !mutant.method.is_empty()
                && !mutant.mutator.is_empty(),
            "incomplete mutant identity"
        );
        ensure!(
            !matches!(mutant.status.as_str(), "SURVIVED" | "NO_COVERAGE") || !mutant.detected,
            "undetected mutant marked detected"
        );
        ensure!(
            !matches!(mutant.status.as_str(), "KILLED" | "EQUIVALENT") || mutant.detected,
            "detected mutant marked undetected"
        );
    }
    Ok(())
}

#[derive(Debug)]
struct Source {
    path: PathBuf,
    relative: String,
    package: String,
    filename: String,
}

fn changed_sources(request: &AdapterRequest) -> Result<Vec<Source>> {
    let base = request
        .options
        .get("mutation_diff_base")
        .and_then(|value| value.as_str())
        .context("PIT mutation_diff_base is required")?;
    let root = request.root.canonicalize()?;
    let cwd = request.cwd.canonicalize()?;
    ensure!(
        request.manifest.parent().context("PIT manifest directory")?.canonicalize()? == cwd,
        "PIT mutation cwd must be the package manifest directory; set mutation.cwd to the directory containing the manifest"
    );
    ensure!(
        cwd.starts_with(&root),
        "mutation package is outside the workspace"
    );
    let mut sources = Vec::new();
    for path in cherry_test_common::git_diff::strict_changed_files_for_base(&cwd, base)? {
        let Ok(relative) = path.strip_prefix(&cwd) else {
            continue;
        };
        let java = relative.starts_with("src/main/java")
            && path.extension().is_some_and(|value| value == "java");
        let kotlin = relative.starts_with("src/main/kotlin")
            && path.extension().is_some_and(|value| value == "kt");
        if !(java || kotlin) || !path.try_exists()? {
            continue;
        }
        let canonical = path.canonicalize()?;
        ensure!(
            canonical.starts_with(&cwd) && canonical.is_file(),
            "changed source escapes package: {}",
            path.display()
        );
        let content = std::fs::read_to_string(&canonical)?;
        let package = source_package(&content)?;
        let filename = canonical
            .file_name()
            .context("source filename")?
            .to_str()
            .context("source filename is not UTF-8")?
            .to_owned();
        let relative = canonical
            .strip_prefix(&root)?
            .to_string_lossy()
            .replace('\\', "/");
        sources.push(Source {
            path: canonical,
            relative,
            package,
            filename,
        });
    }
    sources.sort_by(|left, right| left.relative.cmp(&right.relative));
    sources.dedup_by(|left, right| left.path == right.path);
    Ok(sources)
}

fn source_package(source: &str) -> Result<String> {
    let bytes = source.as_bytes();
    let mut stripped = Vec::with_capacity(bytes.len());
    let mut index = 0;
    let mut depth = 0usize;
    while index < bytes.len() {
        if bytes[index..].starts_with(b"/*") {
            depth += 1;
            index += 2;
        } else if depth > 0 && bytes[index..].starts_with(b"*/") {
            depth -= 1;
            index += 2;
            stripped.push(b' ');
        } else if depth > 0 {
            if bytes[index] == b'\n' {
                stripped.push(b'\n');
            }
            index += 1;
        } else if bytes[index..].starts_with(b"//") {
            while index < bytes.len() && bytes[index] != b'\n' {
                index += 1;
            }
        } else if bytes[index] == b'"' || bytes[index] == b'\'' {
            let quote = bytes[index];
            let triple = quote == b'"' && bytes[index..].starts_with(b"\"\"\"");
            index += if triple { 3 } else { 1 };
            while index < bytes.len() {
                if triple && bytes[index..].starts_with(b"\"\"\"") {
                    index += 3;
                    break;
                }
                if !triple && bytes[index] == quote {
                    index += 1;
                    break;
                }
                if !triple && bytes[index] == b'\\' {
                    index += 1;
                }
                index += 1;
            }
            stripped.push(b' ');
        } else {
            stripped.push(bytes[index]);
            index += 1;
        }
    }
    let stripped = std::str::from_utf8(&stripped)?;
    let pattern = regex::Regex::new(
        r"(?m)^\s*package\s+([\p{L}_$][\p{L}\p{N}_$]*(?:\s*\.\s*[\p{L}_$][\p{L}\p{N}_$]*)*)\s*(?:;|$)",
    )?;
    Ok(pattern
        .captures(stripped)
        .and_then(|captures| captures.get(1))
        .map(|value| value.as_str().split_whitespace().collect())
        .unwrap_or_default())
}

pub fn run(request: &AdapterRequest) -> Result<AdapterResponse> {
    ensure!(
        request.phase == "mutation",
        "PIT supports only the mutation phase"
    );
    ensure!(
        request.execution == cherry_test_common::adapter::ExecutionMode::Local,
        "PIT mutation requires local JVM tools"
    );
    std::fs::create_dir_all(&request.output)?;
    ensure!(
        !request.output.join("mutation.json").exists()
            && !request.output.join("pit-reports").exists(),
        "PIT output directory already contains mutation results"
    );
    let sources = changed_sources(request)?;
    if sources.is_empty() {
        write_report(
            request,
            &MutationReport {
                tool: "pitest".into(),
                status: "no_changes".into(),
                sources: Vec::new(),
                mutants: Vec::new(),
            },
        )?;
        return Ok(AdapterResponse::default());
    }
    let build_tool = request
        .options
        .get("mutation_build_tool")
        .and_then(|value| value.as_str())
        .context("PIT mutation_build_tool is required")?;
    let args = build_args(request, build_tool)?;
    let build = request.output.join("build");
    ensure!(!build.exists(), "PIT build directory must be fresh");
    let (success, targets) = match build_tool {
        "maven" => maven(request, &args, &sources)?,
        "gradle" => gradle(request, &args, &sources)?,
        _ => bail!("PIT requires a Maven or Gradle package"),
    };
    let xml = request.output.join("pit-reports/mutations.xml");
    ensure!(
        xml.is_file(),
        "PIT did not write mutations.xml; inspect mutation logs"
    );
    ensure!(
        xml.canonicalize()?
            .starts_with(request.output.canonicalize()?),
        "PIT XML escapes adapter output"
    );
    ensure!(
        std::fs::metadata(&xml)?.len() <= REPORT_LIMIT,
        "PIT XML exceeds 32 MiB"
    );
    let report = parse_report(&std::fs::read_to_string(xml)?, &sources, &targets)?;
    let failed_mutants = report.mutants.iter().any(|mutant| {
        matches!(
            mutant.status.as_str(),
            "RUN_ERROR" | "MEMORY_ERROR" | "NON_VIABLE"
        )
    });
    write_report(request, &report)?;
    Ok(AdapterResponse {
        success: success && !failed_mutants,
        diagnostics: if !success || failed_mutants {
            vec!["PIT mutation analysis failed; inspect mutation logs and mutant statuses".into()]
        } else {
            Vec::new()
        },
        ..Default::default()
    })
}

fn write_report(request: &AdapterRequest, report: &MutationReport) -> Result<()> {
    validate_report(report)?;
    std::fs::write(
        request.output.join("mutation.json"),
        serde_json::to_vec_pretty(report)?,
    )?;
    Ok(())
}

fn build_args(request: &AdapterRequest, tool: &str) -> Result<Vec<String>> {
    let mut args = Vec::new();
    let mut value = false;
    for argument in &request.args {
        if value {
            args.push(argument.clone());
            value = false;
            continue;
        }
        if matches!(
            argument.as_str(),
            "test-compile" | "testClasses" | "pitest" | "mutationCoverage"
        ) || argument.ends_with(":mutationCoverage")
        {
            continue;
        }
        ensure!(argument.starts_with('-'), "unsupported PIT build argument {argument}; configure flags rather than additional build goals");
        ensure!(
            !["-f", "-pl", "-am", "-rf", "-p", "-b", "-c"]
                .iter()
                .any(|flag| argument.starts_with(flag))
                && ![
                    "--file",
                    "--project-dir",
                    "--projects",
                    "--also-make",
                    "--also-make-dependents",
                    "--resume-from",
                    "--build-file",
                    "--settings-file",
                    "--include-build"
                ]
                .iter()
                .any(|flag| argument == flag || argument.starts_with(&format!("{flag}="))),
            "PIT cannot change the selected package with {argument}"
        );
        ensure!(
            !argument.starts_with("-Dskip")
                && ![
                    "-Dpit.dryRun",
                    "-Dskip",
                    "-Dmaven.test.skip",
                    "-DtargetClasses",
                    "-DreportsDirectory",
                    "-DoutputFormats",
                    "-DfailWhenNoMutations",
                    "-DcrossModule"
                ]
                .iter()
                .any(|flag| argument == flag || argument.starts_with(&format!("{flag}="))),
            "PIT manages mutation selection and execution; remove {argument}"
        );
        value = if tool == "maven" {
            matches!(
                argument.as_str(),
                "-P" | "--activate-profiles" | "-s" | "--settings" | "-gs" | "--global-settings"
            )
        } else {
            matches!(
                argument.as_str(),
                "-I" | "--init-script" | "-g" | "--gradle-user-home"
            )
        };
        args.push(argument.clone());
    }
    ensure!(!value, "PIT build option is missing its value");
    Ok(args)
}

fn wrapper(request: &AdapterRequest, name: &str, fallback: &str) -> String {
    for directory in request.cwd.ancestors() {
        let candidate = directory.join(name);
        if candidate.is_file() {
            return candidate.to_string_lossy().into_owned();
        }
    }
    fallback.into()
}

fn xml_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

fn maven_pit_plugin(node: roxmltree::Node<'_, '_>) -> bool {
    node.has_tag_name("plugin")
        && node
            .children()
            .any(|child| child.has_tag_name("groupId") && child.text() == Some("org.pitest"))
        && node
            .children()
            .any(|child| child.has_tag_name("artifactId") && child.text() == Some("pitest-maven"))
}

fn maven_extend_xml(
    content: &str,
    node: roxmltree::Node<'_, '_>,
    removed: &[&str],
    addition: &str,
) -> Result<String> {
    let range = node.range();
    let mut element = content[range.clone()].to_owned();
    if element.trim_end().ends_with("/>") {
        let end = element.rfind("/>").context("Maven XML empty element")?;
        let name = element[1..]
            .split(|character: char| {
                character.is_whitespace() || character == '/' || character == '>'
            })
            .next()
            .context("Maven XML element name")?
            .to_owned();
        element.replace_range(end..end + 2, &format!(">{addition}</{name}>"));
    } else {
        let mut children: Vec<_> = node
            .children()
            .filter(|child| removed.iter().any(|name| child.has_tag_name(*name)))
            .map(|child| child.range())
            .collect();
        children.reverse();
        for child in children {
            element.replace_range(child.start - range.start..child.end - range.start, "");
        }
        let end = element.rfind("</").context("Maven XML closing element")?;
        element.insert_str(end, addition);
    }
    let mut output = content.to_owned();
    output.replace_range(range, &element);
    Ok(output)
}

fn maven_pom(
    content: &str,
    request: &AdapterRequest,
    targets: &[String],
    effective_executions: &[String],
) -> Result<String> {
    let document = roxmltree::Document::parse(content).context("parse Maven project")?;
    let root = document.root_element();
    ensure!(root.has_tag_name("project"), "expected Maven project");
    for plugin in document
        .descendants()
        .filter(|node| maven_pit_plugin(*node))
    {
        ensure!(
            !plugin
                .descendants()
                .any(|node| node.has_tag_name("execution")
                    && node
                        .children()
                        .any(|child| child.has_tag_name("id")
                            && child.text() == Some("cherry-test-pit"))),
            "Maven PIT execution cherry-test-pit is reserved for mutation runs"
        );
    }
    let mut disabled = content.to_owned();
    let plugins: Vec<_> = document
        .descendants()
        .filter(|node| maven_pit_plugin(*node))
        .collect();
    for plugin in plugins.into_iter().rev() {
        disabled = if let Some(configuration) = plugin
            .children()
            .find(|node| node.has_tag_name("configuration"))
        {
            maven_extend_xml(&disabled, configuration, &["skip"], "<skip>true</skip>")?
        } else {
            maven_extend_xml(
                &disabled,
                plugin,
                &[],
                "<configuration><skip>true</skip></configuration>",
            )?
        };
    }
    let document = roxmltree::Document::parse(&disabled)?;
    let executions: Vec<_> = document
        .descendants()
        .filter(|node| node.has_tag_name("execution") && node.ancestors().any(maven_pit_plugin))
        .collect();
    let mut content = disabled.clone();
    for execution in executions.into_iter().rev() {
        content = maven_extend_xml(&content, execution, &["phase"], "<phase>none</phase>")?;
    }
    let document = roxmltree::Document::parse(&content)?;
    let root = document.root_element();
    let content = content.as_str();
    let build = xml_escape(&request.output.join("build").to_string_lossy());
    let outputs = format!("<directory>{build}</directory><outputDirectory>{build}/classes</outputDirectory><testOutputDirectory>{build}/test-classes</testOutputDirectory>");
    let mut output = if let Some(node) = root.children().find(|node| node.has_tag_name("build")) {
        maven_extend_xml(
            content,
            node,
            &["directory", "outputDirectory", "testOutputDirectory"],
            &outputs,
        )?
    } else {
        maven_extend_xml(content, root, &[], &format!("<build>{outputs}</build>"))?
    };
    let targets = targets
        .iter()
        .map(|target| format!("<param>{}</param>", xml_escape(target)))
        .collect::<String>();
    let reports = xml_escape(&request.output.join("pit-reports").to_string_lossy());
    let document = roxmltree::Document::parse(&output)?;
    let existing_executions: BTreeSet<_> = document
        .root_element()
        .children()
        .find(|node| node.has_tag_name("build"))
        .and_then(|node| node.children().find(|node| node.has_tag_name("plugins")))
        .into_iter()
        .flat_map(|node| node.children().filter(|node| maven_pit_plugin(*node)))
        .flat_map(|node| {
            node.descendants()
                .filter(|node| node.has_tag_name("execution"))
        })
        .filter_map(|node| {
            node.children()
                .find(|node| node.has_tag_name("id"))
                .and_then(|node| node.text())
        })
        .collect();
    let inherited = effective_executions
        .iter()
        .filter(|id| !existing_executions.contains(id.as_str()))
        .map(|id| {
            format!(
                "<execution><id>{}</id><phase>none</phase></execution>",
                xml_escape(id)
            )
        })
        .collect::<String>();
    let execution = format!(
        r#"{inherited}<execution><id>cherry-test-pit</id><configuration><targetClasses combine.self="override">{targets}</targetClasses><outputFormats combine.self="override"><param>XML</param><param>HTML</param></outputFormats><reportsDirectory>{reports}</reportsDirectory><timestampedReports>false</timestampedReports><failWhenNoMutations>true</failWhenNoMutations><crossModule>false</crossModule><skip>false</skip><dryRun>false</dryRun></configuration></execution>"#
    );
    let dependency = "<dependency><groupId>org.pitest</groupId><artifactId>pitest-junit5-plugin</artifactId><version>1.2.3</version></dependency>";
    for step in 0..4 {
        let document = roxmltree::Document::parse(&output)?;
        let build = document
            .root_element()
            .children()
            .find(|node| node.has_tag_name("build"))
            .context("Maven build element")?;
        let plugins = build.children().find(|node| node.has_tag_name("plugins"));
        let plugin = plugins.and_then(|node| node.children().find(|node| maven_pit_plugin(*node)));
        output = match (step, plugins, plugin) {
            (0, None, _) => maven_extend_xml(&output, build, &[], "<plugins/>")?,
            (1, Some(plugins), None) => maven_extend_xml(&output, plugins, &[], "<plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><configuration><skip>true</skip></configuration></plugin>")?,
            (2, _, Some(plugin)) => {
                let addition = format!("<version>1.30.2</version>{}", if plugin.children().any(|node| node.has_tag_name("dependencies")) { String::new() } else { format!("<dependencies>{dependency}</dependencies>") });
                let mut modified = maven_extend_xml(&output, plugin, &["version"], &addition)?;
                if let Some(dependencies) = plugin.children().find(|node| node.has_tag_name("dependencies")) {
                    let dependencies_xml = dependencies.children().filter(|node| node.is_element()).filter(|node| !node.children().any(|child| child.has_tag_name("groupId") && child.text() == Some("org.pitest")) || !node.children().any(|child| child.has_tag_name("artifactId") && child.text() == Some("pitest-junit5-plugin"))).map(|node| &output[node.range()]).collect::<String>();
                    let document = roxmltree::Document::parse(&modified)?;
                    let plugin = document.root_element().children().find(|node| node.has_tag_name("build")).and_then(|node| node.children().find(|node| node.has_tag_name("plugins"))).and_then(|node| node.children().find(|node| maven_pit_plugin(*node))).context("Maven PIT plugin")?;
                    let dependencies = plugin.children().find(|node| node.has_tag_name("dependencies")).context("Maven PIT dependencies")?;
                    modified = maven_extend_xml(&modified, dependencies, &["dependency"], &format!("{dependencies_xml}{dependency}"))?;
                }
                modified
            }
            (3, _, Some(plugin)) => {
                if let Some(executions) = plugin.children().find(|node| node.has_tag_name("executions")) {
                    maven_extend_xml(&output, executions, &[], &execution)?
                } else {
                    maven_extend_xml(&output, plugin, &[], &format!("<executions>{execution}</executions>"))?
                }
            }
            _ => output,
        };
    }
    Ok(output)
}

fn maven_effective_executions(content: &str) -> Result<Vec<String>> {
    let document = roxmltree::Document::parse(content).context("parse effective Maven project")?;
    let root = document.root_element();
    ensure!(
        root.has_tag_name("project"),
        "expected effective Maven project"
    );
    let mut executions = BTreeSet::new();
    for plugin in root
        .children()
        .filter(|node| node.has_tag_name("build"))
        .flat_map(|node| node.children().filter(|node| node.has_tag_name("plugins")))
        .flat_map(|node| node.children().filter(|node| maven_pit_plugin(*node)))
    {
        for execution in plugin
            .children()
            .filter(|node| node.has_tag_name("executions"))
            .flat_map(|node| {
                node.children()
                    .filter(|node| node.has_tag_name("execution"))
            })
        {
            let id = execution
                .children()
                .find(|node| node.has_tag_name("id"))
                .and_then(|node| node.text())
                .context("effective Maven PIT execution has no ID")?
                .trim();
            ensure!(
                !id.is_empty() && id != "cherry-test-pit",
                "Maven PIT execution cherry-test-pit is reserved for mutation runs"
            );
            executions.insert(id.to_owned());
        }
    }
    Ok(executions.into_iter().collect())
}

fn maven_read_executions(
    request: &AdapterRequest,
    program: &str,
    extra: &[String],
) -> Result<Vec<String>> {
    let model = tempfile::Builder::new()
        .prefix(".pitest-effective-pom-")
        .suffix(".xml")
        .tempfile_in(&request.output)?;
    let path = model.path();
    let mut args = vec![
        "-B".into(),
        "-ntp".into(),
        "-N".into(),
        "-f".into(),
        request.manifest.to_string_lossy().into_owned(),
    ];
    args.extend_from_slice(extra);
    args.extend([
        format!(
            "-Dtestit.buildDirectory={}",
            request.output.join("build").display()
        ),
        "org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom".into(),
        format!("-Doutput={}", path.display()),
    ]);
    ensure!(
        process::run(request, program, &args, "mutation-model")?,
        "PIT Maven model resolution failed; inspect mutation-model logs"
    );
    ensure!(
        path.canonicalize()?
            .starts_with(request.output.canonicalize()?),
        "effective Maven output escapes adapter output"
    );
    ensure!(
        std::fs::metadata(&path)?.len() <= REPORT_LIMIT,
        "effective Maven output exceeds 32 MiB"
    );
    maven_effective_executions(&std::fs::read_to_string(path)?)
}

fn maven(
    request: &AdapterRequest,
    extra: &[String],
    sources: &[Source],
) -> Result<(bool, BTreeMap<String, String>)> {
    let original = std::fs::read_to_string(&request.manifest)?;
    let program = wrapper(request, "mvnw", "mvn");
    let effective_executions = maven_read_executions(request, &program, extra)?;
    let directory = request
        .manifest
        .parent()
        .context("Maven manifest directory")?;
    let mut pom = tempfile::Builder::new()
        .prefix(".cherry-test-pit-")
        .suffix(".xml")
        .tempfile_in(directory)?;
    pom.write_all(maven_pom(&original, request, &[], &effective_executions)?.as_bytes())?;
    pom.flush()?;
    let mut args = vec![
        "-B".into(),
        "-ntp".into(),
        "-N".into(),
        "-f".into(),
        pom.path().to_string_lossy().into_owned(),
    ];
    args.extend_from_slice(extra);
    args.push(format!(
        "-Dtestit.buildDirectory={}",
        request.output.join("build").display()
    ));
    let mut compile = args.clone();
    compile.push("test-compile".into());
    ensure!(
        process::run(request, &program, &compile, "mutation-compile")?,
        "PIT source compilation failed; inspect mutation-compile logs"
    );
    let targets = compiled_targets(&[request.output.join("build/classes")], sources)?;
    std::fs::write(
        pom.path(),
        maven_pom(
            &original,
            request,
            &targets.keys().cloned().collect::<Vec<_>>(),
            &effective_executions,
        )?,
    )?;
    args.push(PIT_GOAL.into());
    Ok((process::run(request, &program, &args, "mutation")?, targets))
}

fn gradle(
    request: &AdapterRequest,
    extra: &[String],
    sources: &[Source],
) -> Result<(bool, BTreeMap<String, String>)> {
    let script = request.output.join("pitest.init.gradle");
    let settings = request.output.join("pitest-settings.json");
    std::fs::write(&script, GRADLE_INIT)?;
    let write_settings = |targets: Vec<String>| -> Result<()> {
        std::fs::write(
            &settings,
            serde_json::to_vec(
                &serde_json::json!({"project":request.cwd.canonicalize()?,"build":request.output.join("build"),"reports":request.output.join("pit-reports"),"targets":targets}),
            )?,
        )?;
        Ok(())
    };
    write_settings(Vec::new())?;
    let program = wrapper(request, "gradlew", "gradle");
    let mut args = vec!["--no-daemon".into(), "--console=plain".into()];
    args.extend_from_slice(extra);
    args.extend([
        "--init-script".into(),
        script.to_string_lossy().into_owned(),
        format!("-DcherryTestPitSettings={}", settings.display()),
        format!(
            "-PtestItBuildDir={}",
            request.output.join("build").display()
        ),
    ]);
    let mut compile = args.clone();
    compile.push("testClasses".into());
    ensure!(
        process::run(request, &program, &compile, "mutation-compile")?,
        "PIT source compilation failed; inspect mutation-compile logs"
    );
    let targets = compiled_targets(
        &[
            request.output.join("build/classes/java/main"),
            request.output.join("build/classes/kotlin/main"),
        ],
        sources,
    )?;
    write_settings(targets.keys().cloned().collect())?;
    args.push("pitest".into());
    Ok((process::run(request, &program, &args, "mutation")?, targets))
}

const GRADLE_INIT: &str = r#"initscript {
    repositories { mavenCentral(); maven { url = uri('https://plugins.gradle.org/m2/') } }
    dependencies { classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:1.19.0' }
}
def settings = new groovy.json.JsonSlurper().parse(new File(System.getProperty('cherryTestPitSettings')))
gradle.beforeProject { project ->
    if (project.projectDir.canonicalPath == settings.project) {
        project.layout.buildDirectory.set(new File(settings.build))
        project.afterEvaluate {
            if (project.extensions.findByName('pitest') == null) {
                project.pluginManager.apply(info.solidsoft.gradle.pitest.PitestPlugin)
            }
            project.configurations.matching { it.name.toLowerCase().contains('pitest') }.configureEach {
                resolutionStrategy.deactivateDependencyLocking()
            }
        }
    }
}
gradle.projectsEvaluated {
    gradle.rootProject.allprojects { project ->
        if (project.projectDir.canonicalPath == settings.project) {
            project.layout.buildDirectory.set(new File(settings.build))
            def pit = project.extensions.getByName('pitest')
            if (!pit.targetTests.isPresent()) {
                pit.targetTests.set(['*'] as Set)
            }
            pit.targetClasses.set(settings.targets as Set)
            pit.pitestVersion.set('1.30.2')
            pit.junit5PluginVersion.set('1.2.3')
            pit.outputFormats.set(['XML', 'HTML'] as Set)
            pit.reportDir.set(new File(settings.reports))
            pit.timestampedReports.set(false)
            pit.failWhenNoMutations.set(true)
            project.tasks.named('pitest').configure { enabled = !settings.targets.isEmpty() }
        } else {
            project.tasks.matching { it.name == 'pitest' }.configureEach { enabled = false }
        }
    }
}
"#;

fn compiled_targets(roots: &[PathBuf], sources: &[Source]) -> Result<BTreeMap<String, String>> {
    let mut identities = BTreeMap::new();
    for source in sources {
        ensure!(
            identities
                .insert(
                    (source.package.clone(), source.filename.clone()),
                    source.relative.clone()
                )
                .is_none(),
            "ambiguous changed source package and filename"
        );
    }
    let mut targets = BTreeMap::new();
    for root in roots {
        for path in formats::files(root, "class")? {
            ensure!(
                path.canonicalize()?.starts_with(root.canonicalize()?),
                "compiled class escapes build output"
            );
            let (class_name, filename) = class_source(&std::fs::read(&path)?)?;
            let Some(filename) = filename else { continue };
            let package = class_name
                .rsplit_once('.')
                .map(|(package, _)| package)
                .unwrap_or("");
            if let Some(source) = identities.get(&(package.into(), filename)) {
                ensure!(
                    !class_name.contains(['*', '?', ',', '[', ']']),
                    "class name cannot be represented as an exact PIT target"
                );
                targets.insert(class_name, source.clone());
            }
        }
    }
    for source in sources {
        ensure!(
            targets.values().any(|path| path == &source.relative),
            "no compiled JVM classes found for changed source {}; retain SourceFile debug metadata",
            source.relative
        );
    }
    Ok(targets)
}

struct ClassReader<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl<'a> ClassReader<'a> {
    fn take(&mut self, count: usize) -> Result<&'a [u8]> {
        let end = self
            .position
            .checked_add(count)
            .context("invalid classfile length")?;
        let bytes = self
            .bytes
            .get(self.position..end)
            .context("truncated JVM classfile")?;
        self.position = end;
        Ok(bytes)
    }
    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16> {
        let bytes = self.take(2)?;
        Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
    }
    fn u32(&mut self) -> Result<u32> {
        let bytes = self.take(4)?;
        Ok(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }
    fn skip_attributes(&mut self) -> Result<()> {
        for _ in 0..self.u16()? {
            self.take(2)?;
            let length = self.u32()?;
            self.take(length as usize)?;
        }
        Ok(())
    }
}

fn class_source(bytes: &[u8]) -> Result<(String, Option<String>)> {
    let mut reader = ClassReader { bytes, position: 0 };
    ensure!(reader.u32()? == 0xcafebabe, "invalid JVM classfile magic");
    reader.take(4)?;
    let count = reader.u16()? as usize;
    let mut strings: Vec<Option<&[u8]>> = vec![None; count];
    let mut classes = BTreeMap::new();
    let mut index = 1;
    while index < count {
        match reader.u8()? {
            1 => {
                let length = reader.u16()?;
                strings[index] = Some(reader.take(length as usize)?);
            }
            7 => {
                classes.insert(index, reader.u16()? as usize);
            }
            3 | 4 | 9 | 10 | 11 | 12 | 17 | 18 => {
                reader.take(4)?;
            }
            5 | 6 => {
                reader.take(8)?;
                index += 1;
            }
            8 | 16 | 19 | 20 => {
                reader.take(2)?;
            }
            15 => {
                reader.take(3)?;
            }
            tag => bail!("unknown JVM constant pool tag {tag}"),
        }
        index += 1;
    }
    let text = |index: usize| -> Result<String> {
        Ok(std::str::from_utf8(
            strings
                .get(index)
                .and_then(|value| *value)
                .context("invalid classfile UTF-8 reference")?,
        )?
        .to_owned())
    };
    reader.take(2)?;
    let class_index = reader.u16()? as usize;
    let class_name = text(
        *classes
            .get(&class_index)
            .context("invalid this_class reference")?,
    )?
    .replace('/', ".");
    reader.take(2)?;
    let interfaces = reader.u16()? as usize;
    reader.take(interfaces * 2)?;
    for _ in 0..2 {
        for _ in 0..reader.u16()? {
            reader.take(6)?;
            reader.skip_attributes()?;
        }
    }
    let mut source = None;
    for _ in 0..reader.u16()? {
        let name = text(reader.u16()? as usize)?;
        let length = reader.u32()? as usize;
        if name == "SourceFile" {
            ensure!(
                length == 2 && source.is_none(),
                "invalid SourceFile attribute"
            );
            source = Some(text(reader.u16()? as usize)?);
        } else {
            reader.take(length)?;
        }
    }
    ensure!(reader.position == bytes.len(), "trailing classfile bytes");
    Ok((class_name, source))
}

fn parse_report(
    xml: &str,
    sources: &[Source],
    targets: &BTreeMap<String, String>,
) -> Result<MutationReport> {
    let document = roxmltree::Document::parse(xml).context("parse PIT mutations.xml")?;
    let root = document.root_element();
    ensure!(
        root.has_tag_name("mutations"),
        "expected PIT mutations element"
    );
    let mut mutants = Vec::new();
    for node in root.children().filter(|node| node.is_element()) {
        ensure!(
            node.has_tag_name("mutation"),
            "unexpected PIT report element"
        );
        let field = |name: &str| -> Result<String> {
            let nodes: Vec<_> = node
                .children()
                .filter(|child| child.has_tag_name(name))
                .collect();
            ensure!(nodes.len() == 1, "missing or duplicate PIT {name}");
            Ok(nodes[0].text().unwrap_or("").to_owned())
        };
        let class_name = field("mutatedClass")?;
        let source = targets
            .get(&class_name)
            .with_context(|| format!("PIT mutated class outside changed sources: {class_name}"))?;
        let filename = field("sourceFile")?;
        ensure!(
            Path::new(source)
                .file_name()
                .and_then(|value| value.to_str())
                == Some(filename.as_str()),
            "PIT source filename does not match changed source"
        );
        let line = field("lineNumber")?
            .parse::<u32>()
            .context("invalid PIT line number")?;
        let killing_test = node
            .children()
            .find(|child| child.has_tag_name("killingTest"))
            .and_then(|child| child.text())
            .filter(|text| !text.is_empty())
            .map(str::to_owned);
        mutants.push(Mutant {
            status: node
                .attribute("status")
                .context("PIT mutant status missing")?
                .into(),
            detected: node
                .attribute("detected")
                .context("PIT detected attribute missing")?
                .parse()
                .context("invalid PIT detected attribute")?,
            source: source.clone(),
            line,
            class_name,
            method: field("mutatedMethod")?,
            mutator: field("mutator")?,
            description: field("description")?,
            killing_test,
        });
    }
    let report = MutationReport {
        tool: "pitest".into(),
        status: "complete".into(),
        sources: sources
            .iter()
            .map(|source| source.relative.clone())
            .collect(),
        mutants,
    };
    validate_report(&report)?;
    Ok(report)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Command;

    fn request(root: &Path) -> AdapterRequest {
        AdapterRequest {
            protocol: 1,
            project: "demo".into(),
            package: "prices".into(),
            phase: "mutation".into(),
            root: root.into(),
            manifest: root.join("pom.xml"),
            cwd: root.into(),
            output: root.join("out"),
            execution: cherry_test_common::adapter::ExecutionMode::Local,
            coverage: false,
            strict: true,
            args: Vec::new(),
            env: Default::default(),
            options: BTreeMap::from([
                ("mutation_build_tool".into(), "maven".into()),
                ("mutation_diff_base".into(), "main".into()),
            ]),
            container: None,
            select: None,
        }
    }

    fn git(root: &Path, args: &[&str]) {
        let output = Command::new("git")
            .current_dir(root)
            .args(args)
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
    }

    fn repository() -> tempfile::TempDir {
        let temp = tempfile::tempdir().unwrap();
        git(temp.path(), &["init", "-b", "main"]);
        git(temp.path(), &["config", "user.name", "Cherry Test"]);
        git(
            temp.path(),
            &["config", "user.email", "test@example.invalid"],
        );
        std::fs::create_dir_all(temp.path().join("src/main/java/demo")).unwrap();
        std::fs::write(
            temp.path().join("src/main/java/demo/Price.java"),
            "package demo; class Price {}\n",
        )
        .unwrap();
        git(temp.path(), &["add", "."]);
        git(temp.path(), &["commit", "-m", "initial"]);
        temp
    }

    #[test]
    fn changed_selection_includes_branch_staged_unstaged_and_untracked_sources() {
        let temp = repository();
        let root = temp.path();
        git(root, &["checkout", "-b", "feature"]);
        std::fs::write(
            root.join("src/main/java/demo/Price.java"),
            "package demo; class Price { int x; }\n",
        )
        .unwrap();
        git(root, &["add", "."]);
        git(root, &["commit", "-m", "change"]);
        std::fs::write(
            root.join("src/main/java/demo/Staged.java"),
            "package demo; class Staged {}\n",
        )
        .unwrap();
        git(root, &["add", "."]);
        std::fs::create_dir_all(root.join("src/main/kotlin/unusual/path")).unwrap();
        std::fs::write(
            root.join("src/main/kotlin/unusual/path/Prices.kt"),
            "package other.pkg\nclass Something\nfun price() = 7\n",
        )
        .unwrap();
        std::fs::create_dir_all(root.join("src/test/java/demo")).unwrap();
        std::fs::write(
            root.join("src/test/java/demo/PriceTest.java"),
            "package demo; class PriceTest {}\n",
        )
        .unwrap();
        let sources = changed_sources(&request(root)).unwrap();
        assert_eq!(
            sources
                .iter()
                .map(|source| source.filename.as_str())
                .collect::<Vec<_>>(),
            ["Price.java", "Staged.java", "Prices.kt"]
        );
        assert_eq!(sources[2].package, "other.pkg");
        git(root, &["checkout", "--", "src/main/java/demo/Price.java"]);
        std::fs::remove_file(root.join("src/main/java/demo/Staged.java")).unwrap();
        assert_eq!(changed_sources(&request(root)).unwrap().len(), 2);
    }

    #[test]
    fn changed_selection_rejects_unknown_base_and_non_repository() {
        let temp = repository();
        let mut request = request(temp.path());
        request
            .options
            .insert("mutation_diff_base".into(), "missing-branch".into());
        assert!(changed_sources(&request).is_err());
        let outside = tempfile::tempdir().unwrap();
        request.root = outside.path().into();
        request.cwd = outside.path().into();
        assert!(changed_sources(&request).is_err());
    }

    #[test]
    fn unchanged_sources_produce_explicit_no_changes_without_build_tools() {
        let temp = repository();
        let request = request(temp.path());
        let response = run(&request).unwrap();
        assert!(response.success);
        assert!(response.tests.is_empty());
        let report = read_report(&request.output).unwrap().unwrap();
        assert_eq!(report.status, "no_changes");
        assert!(report.sources.is_empty());
        assert!(!request.output.join("build").exists());
    }

    #[test]
    fn empty_tree_selects_existing_production_files() {
        let temp = repository();
        let mut request = request(temp.path());
        request
            .options
            .insert("mutation_diff_base".into(), "empty-tree".into());
        assert_eq!(changed_sources(&request).unwrap().len(), 1);
    }

    #[test]
    fn package_parser_ignores_comments_and_handles_kotlin_layouts() {
        assert_eq!(
            source_package("/* package wrong; */\npackage foo.bar;\nclass Price {}").unwrap(),
            "foo.bar"
        );
        assert_eq!(
            source_package("@file:JvmName(\"Price\")\npackage foo.bar\nfun price() = 1").unwrap(),
            "foo.bar"
        );
        assert_eq!(
            source_package("class Price {}\n// package wrong;").unwrap(),
            ""
        );
    }

    fn class_bytes(name: &str, source: &str) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend(0xcafebabeu32.to_be_bytes());
        bytes.extend([0, 0, 0, 69]);
        bytes.extend(5u16.to_be_bytes());
        bytes.push(1);
        bytes.extend((name.len() as u16).to_be_bytes());
        bytes.extend(name.as_bytes());
        bytes.extend([7, 0, 1]);
        for value in ["SourceFile", source] {
            bytes.push(1);
            bytes.extend((value.len() as u16).to_be_bytes());
            bytes.extend(value.as_bytes());
        }
        bytes.extend([
            0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 3, 0, 0, 0, 2, 0, 4,
        ]);
        bytes
    }

    #[test]
    fn class_metadata_maps_kotlin_facades_multiple_classes_and_inner_classes() {
        let temp = tempfile::tempdir().unwrap();
        let classes = temp.path().join("classes");
        std::fs::create_dir_all(&classes).unwrap();
        for (name, source) in [
            ("demo/Price", "Prices.kt"),
            ("demo/Price$Nested", "Prices.kt"),
            ("demo/PricesKt", "Prices.kt"),
            ("demo/PriceUnchanged", "Other.kt"),
        ] {
            std::fs::write(
                classes.join(format!("{}.class", name.replace('/', "_"))),
                class_bytes(name, source),
            )
            .unwrap();
        }
        let sources = vec![Source {
            path: temp.path().join("Prices.kt"),
            relative: "src/main/kotlin/Prices.kt".into(),
            package: "demo".into(),
            filename: "Prices.kt".into(),
        }];
        let targets = compiled_targets(&[classes], &sources).unwrap();
        assert_eq!(
            targets.keys().map(String::as_str).collect::<Vec<_>>(),
            ["demo.Price", "demo.Price$Nested", "demo.PricesKt"]
        );
        assert!(class_source(&class_bytes("demo/Price", "Price.java")[..20]).is_err());
    }

    fn xml(status: &str, detected: bool, class: &str) -> String {
        format!("<mutations><mutation status=\"{status}\" detected=\"{detected}\"><sourceFile>Prices.kt</sourceFile><mutatedClass>{class}</mutatedClass><mutatedMethod>price</mutatedMethod><lineNumber>5</lineNumber><mutator>MathMutator</mutator><description>replaced &lt; with &gt;</description><killingTest>PriceTest</killingTest></mutation></mutations>")
    }

    fn parse(xml: &str) -> Result<MutationReport> {
        parse_report(
            xml,
            &[Source {
                path: PathBuf::from("src/main/kotlin/Prices.kt"),
                relative: "src/main/kotlin/Prices.kt".into(),
                package: "demo".into(),
                filename: "Prices.kt".into(),
            }],
            &BTreeMap::from([("demo.PricesKt".into(), "src/main/kotlin/Prices.kt".into())]),
        )
    }

    #[test]
    fn pit_report_preserves_real_mutant_outcomes_and_source_locations() {
        for (status, detected) in [
            ("KILLED", true),
            ("EQUIVALENT", true),
            ("SURVIVED", false),
            ("NO_COVERAGE", false),
            ("TIMED_OUT", true),
            ("RUN_ERROR", false),
        ] {
            let report = parse(&xml(status, detected, "demo.PricesKt")).unwrap();
            assert_eq!(report.mutants[0].status, status);
            assert_eq!(report.mutants[0].line, 5);
            assert_eq!(report.mutants[0].description, "replaced < with >");
        }
    }

    #[test]
    fn pit_report_rejects_unselected_classes_invalid_status_empty_and_unsafe_xml() {
        assert!(parse(&xml("KILLED", true, "demo.Unchanged")).is_err());
        assert!(parse(&xml("SURVIVED", true, "demo.PricesKt")).is_err());
        assert!(parse(&xml("EQUIVALENT", false, "demo.PricesKt")).is_err());
        assert!(parse(&xml("UNKNOWN", false, "demo.PricesKt")).is_err());
        assert!(parse("<mutations/>").is_err());
        assert!(parse("<mutations><mutation>").is_err());
        assert!(
            parse("<!DOCTYPE mutations [<!ENTITY a 'bad'>]><mutations>&a;</mutations>").is_err()
        );
        assert!(parse(
            &xml("KILLED", true, "demo.PricesKt").replace("<lineNumber>5", "<lineNumber>0")
        )
        .is_err());
    }

    #[test]
    fn sidecar_validation_rejects_paths_and_false_no_changes() {
        let temp = tempfile::tempdir().unwrap();
        let mut report = parse(&xml("SURVIVED", false, "demo.PricesKt")).unwrap();
        report.status = "no_changes".into();
        std::fs::write(
            temp.path().join("mutation.json"),
            serde_json::to_vec(&report).unwrap(),
        )
        .unwrap();
        assert!(read_report(temp.path()).is_err());
        report.status = "complete".into();
        report.sources[0] = "../outside.java".into();
        assert!(validate_report(&report).is_err());
    }

    #[test]
    fn temporary_pom_preserves_profiles_and_isolates_pit_execution() {
        let temp = tempfile::tempdir().unwrap();
        let request = request(temp.path());
        let original = r#"<project><build><outputDirectory>old</outputDirectory><plugins><plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><configuration><targetClasses><param>demo.*</param></targetClasses><targetTests><param>demo.PriceTest</param></targetTests></configuration><dependencies><dependency><groupId>org.pitest</groupId><artifactId>pitest-junit5-plugin</artifactId><version>old</version></dependency></dependencies><executions><execution><id>default-cli</id><phase>test-compile</phase><configuration><targetClasses><param>*</param></targetClasses></configuration></execution></executions></plugin></plugins></build><profiles><profile><id>normal</id><activation><activeByDefault>true</activeByDefault></activation><build><directory>other</directory><plugins><plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><configuration><targetClasses><param>demo.*</param></targetClasses></configuration></plugin></plugins></build></profile></profiles></project>"#;
        for original in [
            original,
            "<project><modelVersion>4.0.0</modelVersion></project>",
            "<project><build/><profiles/></project>",
        ] {
            let generated = maven_pom(
                original,
                &request,
                &["demo.Price".into(), "demo.Price$Nested".into()],
                &[],
            )
            .unwrap();
            let doc = roxmltree::Document::parse(&generated).unwrap();
            let profiles: Vec<_> = doc
                .descendants()
                .filter(|node| node.has_tag_name("profile"))
                .collect();
            assert!(profiles.iter().all(|node| node
                .children()
                .any(|child| child.has_tag_name("id") && child.text() == Some("normal"))));
            let execution = doc
                .descendants()
                .find(|node| {
                    node.has_tag_name("execution")
                        && node.children().any(|child| {
                            child.has_tag_name("id") && child.text() == Some("cherry-test-pit")
                        })
                })
                .unwrap();
            let classes = execution
                .descendants()
                .find(|node| node.has_tag_name("targetClasses"))
                .unwrap();
            assert_eq!(classes.attribute("combine.self"), Some("override"));
            assert_eq!(
                classes
                    .children()
                    .filter(|node| node.is_element())
                    .filter_map(|node| node.text())
                    .collect::<Vec<_>>(),
                ["demo.Price", "demo.Price$Nested"]
            );
            let build = doc
                .root_element()
                .children()
                .find(|node| node.has_tag_name("build"))
                .unwrap();
            assert_eq!(
                build
                    .children()
                    .find(|node| node.has_tag_name("outputDirectory"))
                    .unwrap()
                    .text(),
                request.output.join("build/classes").to_str()
            );
            for old in doc.descendants().filter(|node| {
                node.has_tag_name("execution")
                    && node.children().any(|child| {
                        child.has_tag_name("id") && child.text() == Some("default-cli")
                    })
            }) {
                assert_eq!(
                    old.children()
                        .find(|node| node.has_tag_name("phase"))
                        .unwrap()
                        .text(),
                    Some("none")
                );
            }
            assert!(maven_pom(&generated, &request, &[], &[]).is_err());
        }
    }

    #[cfg(unix)]
    #[test]
    fn effective_maven_model_is_removed_after_command_or_parse_failure() {
        use std::os::unix::fs::PermissionsExt;
        let temp = tempfile::tempdir().unwrap();
        let request = request(temp.path());
        std::fs::create_dir(&request.output).unwrap();
        let command = temp.path().join("maven-model");
        for status in [0, 1] {
            std::fs::write(&command, format!("#!/bin/sh\nfor arg do\ncase \"$arg\" in -Doutput=*) output=${{arg#-Doutput=}};; esac\ndone\nprintf '<project><build>' > \"$output\"\nexit {status}\n")).unwrap();
            std::fs::set_permissions(&command, std::fs::Permissions::from_mode(0o700)).unwrap();
            assert!(maven_read_executions(&request, command.to_str().unwrap(), &[]).is_err());
            assert!(std::fs::read_dir(&request.output)
                .unwrap()
                .all(|entry| !entry
                    .unwrap()
                    .file_name()
                    .to_string_lossy()
                    .starts_with(".pitest-effective-pom-")));
        }
    }

    #[test]
    fn inherited_maven_pit_executions_are_neutralized_and_reserved_ids_rejected() {
        let effective = "<project><build><plugins><plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><executions><execution><id>inherited-pit</id><phase>test-compile</phase><configuration><skip>false</skip></configuration></execution></executions></plugin></plugins></build></project>";
        let executions = maven_effective_executions(effective).unwrap();
        assert_eq!(executions, ["inherited-pit"]);
        assert!(
            maven_effective_executions(&effective.replace("inherited-pit", "cherry-test-pit"))
                .is_err()
        );
        let temp = tempfile::tempdir().unwrap();
        let generated = maven_pom(
            "<project/>",
            &request(temp.path()),
            &["demo.Price".into()],
            &executions,
        )
        .unwrap();
        let document = roxmltree::Document::parse(&generated).unwrap();
        let execution = document
            .descendants()
            .find(|node| {
                node.has_tag_name("execution")
                    && node
                        .children()
                        .any(|node| node.has_tag_name("id") && node.text() == Some("inherited-pit"))
            })
            .unwrap();
        assert_eq!(
            execution
                .children()
                .find(|node| node.has_tag_name("phase"))
                .unwrap()
                .text(),
            Some("none")
        );
    }

    #[test]
    fn mutation_cwd_must_match_manifest_before_no_changes() {
        let temp = tempfile::tempdir().unwrap();
        let mut request = request(temp.path());
        let module = temp.path().join("module");
        std::fs::create_dir(&module).unwrap();
        request.manifest = module.join("pom.xml");
        assert!(changed_sources(&request)
            .unwrap_err()
            .to_string()
            .contains("cwd must be the package manifest directory"));
    }

    #[test]
    fn mutation_arguments_cannot_add_extra_build_goals_or_other_packages() {
        let temp = tempfile::tempdir().unwrap();
        let mut request = request(temp.path());
        request.args = vec![
            "-Pmutation".into(),
            "test-compile".into(),
            "org.pitest:pitest-maven:mutationCoverage".into(),
        ];
        assert_eq!(build_args(&request, "maven").unwrap(), ["-Pmutation"]);
        request.args = vec!["install".into()];
        assert!(build_args(&request, "maven").is_err());
        for flag in [
            "-f",
            "-fother.xml",
            "-plother",
            "--projects=other",
            "-am",
            "-rfother",
            "--resume-from=other",
            "-pother",
            "-bother",
            "--build-file=other",
            "-Dpit.dryRun=true",
            "-DskipTests=true",
            "--include-build=other",
        ] {
            request.args = vec![flag.into()];
            assert!(build_args(&request, "maven").is_err(), "{flag}");
            assert!(build_args(&request, "gradle").is_err(), "{flag}");
        }
    }

    #[test]
    fn mutation_uses_nearest_ancestor_wrapper_for_nested_modules() {
        let temp = tempfile::tempdir().unwrap();
        let mut request = request(temp.path());
        request.cwd = temp.path().join("module/nested");
        std::fs::create_dir_all(&request.cwd).unwrap();
        let root_wrapper = temp.path().join("mvnw");
        std::fs::write(&root_wrapper, "").unwrap();
        assert_eq!(
            wrapper(&request, "mvnw", "mvn"),
            root_wrapper.to_string_lossy()
        );
        let nearer_wrapper = temp.path().join("module/mvnw");
        std::fs::write(&nearer_wrapper, "").unwrap();
        assert_eq!(
            wrapper(&request, "mvnw", "mvn"),
            nearer_wrapper.to_string_lossy()
        );
    }

    #[test]
    fn mutation_arguments_preserve_user_profile_selection() {
        let temp = tempfile::tempdir().unwrap();
        let mut request = request(temp.path());
        request.args = vec!["-P".into(), "mutation,!other-profile".into()];
        assert_eq!(build_args(&request, "maven").unwrap(), request.args);
    }
}
