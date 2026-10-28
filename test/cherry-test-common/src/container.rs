//! Container execution settings: the `container:` block of `cherry-test.yaml`
//! and the resolved specification that an adapter request carries.

use std::collections::BTreeMap;
use std::fmt;
use std::path::PathBuf;

use anyhow::{bail, Result};
use serde::de::{self, Deserializer, MapAccess, Visitor};
use serde::{Deserialize, Serialize};

/// Seconds a container tool invocation may run when `timeout` is not configured.
pub const DEFAULT_TIMEOUT_SECS: u64 = 1800;

/// Whether `docker run` pulls the image first.
#[derive(Debug, Clone, Copy, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum PullPolicy {
    #[default]
    Never,
    Missing,
    Always,
}

impl PullPolicy {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Never => "never",
            Self::Missing => "missing",
            Self::Always => "always",
        }
    }
}

/// Network attached to the container.
#[derive(Debug, Clone, Copy, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum NetworkMode {
    #[default]
    None,
    Bridge,
}

impl NetworkMode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::Bridge => "bridge",
        }
    }
}

/// Access mode of the `/workspace` bind mount.
#[derive(Debug, Clone, Copy, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum WorkspaceAccess {
    #[default]
    Ro,
    Rw,
}

/// How a writable path starts.
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum SeedMode {
    Copy,
}

/// The `container:` block, under `defaults` or a package. Every field is optional so that a
/// package block overrides the defaults block field by field.
#[derive(Debug, Clone, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ContainerConfig {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub image: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pull: Option<PullPolicy>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub network: Option<NetworkMode>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cap_drop: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub no_new_privileges: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub read_only_root: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cpus: Option<Quantity>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub memory: Option<Quantity>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pids: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub shm_size: Option<Quantity>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tmpfs: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub timeout: Option<Timeout>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace: Option<WorkspaceAccess>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub writable: Option<Vec<WritableConfig>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mounts: Option<Vec<MountConfig>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub caches: Option<Vec<CacheConfig>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub env: Option<ContainerEnvConfig>,
}

impl ContainerConfig {
    /// Combines the defaults block with a package block; package fields win.
    pub fn merged(defaults: Option<&Self>, package: Option<&Self>) -> Option<Self> {
        match (defaults, package) {
            (None, None) => None,
            (Some(only), None) | (None, Some(only)) => Some(only.clone()),
            (Some(base), Some(top)) => Some(Self {
                image: top.image.clone().or_else(|| base.image.clone()),
                pull: top.pull.or(base.pull),
                network: top.network.or(base.network),
                user: top.user.clone().or_else(|| base.user.clone()),
                cap_drop: top.cap_drop.clone().or_else(|| base.cap_drop.clone()),
                no_new_privileges: top.no_new_privileges.or(base.no_new_privileges),
                read_only_root: top.read_only_root.or(base.read_only_root),
                cpus: top.cpus.clone().or_else(|| base.cpus.clone()),
                memory: top.memory.clone().or_else(|| base.memory.clone()),
                pids: top.pids.or(base.pids),
                shm_size: top.shm_size.clone().or_else(|| base.shm_size.clone()),
                tmpfs: top.tmpfs.clone().or_else(|| base.tmpfs.clone()),
                timeout: top.timeout.or(base.timeout),
                workspace: top.workspace.or(base.workspace),
                writable: top.writable.clone().or_else(|| base.writable.clone()),
                mounts: top.mounts.clone().or_else(|| base.mounts.clone()),
                caches: top.caches.clone().or_else(|| base.caches.clone()),
                env: ContainerEnvConfig::merged(base.env.as_ref(), top.env.as_ref()),
            }),
        }
    }
}

/// Host variables passed into the container and fixed values set inside it.
#[derive(Debug, Clone, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ContainerEnvConfig {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pass: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub set: Option<BTreeMap<String, String>>,
}

impl ContainerEnvConfig {
    fn merged(base: Option<&Self>, top: Option<&Self>) -> Option<Self> {
        match (base, top) {
            (None, None) => None,
            (Some(only), None) | (None, Some(only)) => Some(only.clone()),
            (Some(base), Some(top)) => Some(Self {
                pass: top.pass.clone().or_else(|| base.pass.clone()),
                set: top.set.clone().or_else(|| base.set.clone()),
            }),
        }
    }
}

/// A workspace-relative path that the job may write, given as a path or as
/// `{path, seed: copy}`.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct WritableConfig {
    pub path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seed: Option<SeedMode>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WritableEntry {
    path: String,
    #[serde(default)]
    seed: Option<SeedMode>,
}

impl<'de> Deserialize<'de> for WritableConfig {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct WritableVisitor;

        impl<'de> Visitor<'de> for WritableVisitor {
            type Value = WritableConfig;

            fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
                formatter.write_str("a path or a map with `path` and `seed`")
            }

            fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
                Ok(WritableConfig {
                    path: value.to_string(),
                    seed: None,
                })
            }

            fn visit_map<A: MapAccess<'de>>(self, map: A) -> Result<Self::Value, A::Error> {
                let entry = WritableEntry::deserialize(de::value::MapAccessDeserializer::new(map))?;
                Ok(WritableConfig {
                    path: entry.path,
                    seed: entry.seed,
                })
            }
        }

        deserializer.deserialize_any(WritableVisitor)
    }
}

/// An extra bind mount. `source` is relative to the workspace root and may point outside it.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct MountConfig {
    pub source: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target: Option<String>,
    #[serde(default = "default_read_only")]
    pub read_only: bool,
}

fn default_read_only() -> bool {
    true
}

/// A named Docker volume kept between runs, such as a dependency cache.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct CacheConfig {
    pub volume: String,
    pub target: String,
}

/// A Docker resource value such as `2`, `1.5` or `2g`, kept as its command-line text.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(transparent)]
pub struct Quantity(pub String);

impl<'de> Deserialize<'de> for Quantity {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct QuantityVisitor;

        impl<'de> Visitor<'de> for QuantityVisitor {
            type Value = Quantity;

            fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
                formatter.write_str("a number or a string")
            }

            fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
                Ok(Quantity(value.to_string()))
            }

            fn visit_u64<E: de::Error>(self, value: u64) -> Result<Self::Value, E> {
                Ok(Quantity(value.to_string()))
            }

            fn visit_i64<E: de::Error>(self, value: i64) -> Result<Self::Value, E> {
                Ok(Quantity(value.to_string()))
            }

            fn visit_f64<E: de::Error>(self, value: f64) -> Result<Self::Value, E> {
                Ok(Quantity(value.to_string()))
            }
        }

        deserializer.deserialize_any(QuantityVisitor)
    }
}

/// A timeout in seconds, written as a number of seconds or a duration such as `1800s`, `30m` or `2h`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Timeout(pub u64);

impl Serialize for Timeout {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&format!("{}s", self.0))
    }
}

impl<'de> Deserialize<'de> for Timeout {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct TimeoutVisitor;

        impl<'de> Visitor<'de> for TimeoutVisitor {
            type Value = Timeout;

            fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
                formatter.write_str("a duration such as 1800s, 30m or 2h")
            }

            fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
                parse_duration(value).map(Timeout).map_err(E::custom)
            }

            fn visit_u64<E: de::Error>(self, value: u64) -> Result<Self::Value, E> {
                parse_duration(&value.to_string())
                    .map(Timeout)
                    .map_err(E::custom)
            }

            fn visit_i64<E: de::Error>(self, value: i64) -> Result<Self::Value, E> {
                parse_duration(&value.to_string())
                    .map(Timeout)
                    .map_err(E::custom)
            }
        }

        deserializer.deserialize_any(TimeoutVisitor)
    }
}

/// Parses `90`, `90s`, `30m` or `2h` into seconds. Zero and values above `i64::MAX` are rejected.
pub fn parse_duration(text: &str) -> Result<u64> {
    let (digits, factor) = match text.as_bytes().last() {
        Some(b's') => (&text[..text.len() - 1], 1),
        Some(b'm') => (&text[..text.len() - 1], 60),
        Some(b'h') => (&text[..text.len() - 1], 3600),
        _ => (text, 1),
    };
    let seconds = if !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit()) {
        digits
            .parse::<u64>()
            .ok()
            .and_then(|value| value.checked_mul(factor))
            .filter(|value| i64::try_from(*value).is_ok())
    } else {
        None
    };
    match seconds {
        Some(0) | None => bail!(
            "invalid duration `{text}`; use a positive number of seconds or a value such as 1800s, 30m or 2h"
        ),
        Some(seconds) => Ok(seconds),
    }
}

/// The container settings resolved for one package phase. Host paths are absolute;
/// targets are paths inside the container.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
pub struct ContainerSpec {
    pub name: String,
    pub run: String,
    pub job: String,
    pub image: String,
    pub pull: PullPolicy,
    pub network: NetworkMode,
    pub user: Option<String>,
    pub cap_drop: Vec<String>,
    pub no_new_privileges: bool,
    pub read_only_root: bool,
    pub cpus: Option<String>,
    pub memory: Option<String>,
    pub pids: Option<u64>,
    pub shm_size: Option<String>,
    pub tmpfs: Vec<String>,
    pub timeout_secs: u64,
    pub workspace: WorkspaceAccess,
    pub mount_root: PathBuf,
    pub outputs: Vec<PathBuf>,
    pub writable: Vec<WritableMount>,
    pub mounts: Vec<BindMount>,
    pub caches: Vec<CacheVolume>,
    pub env_pass: Vec<String>,
    pub env_set: BTreeMap<String, String>,
}

/// A fresh per-job host directory mounted over a workspace path.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
pub struct WritableMount {
    pub origin: PathBuf,
    pub source: PathBuf,
    pub target: String,
    pub seed: bool,
}

/// An extra host directory mounted into the container.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
pub struct BindMount {
    pub source: PathBuf,
    pub target: String,
    pub read_only: bool,
}

/// A named volume mounted into the container.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
pub struct CacheVolume {
    pub volume: String,
    pub target: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn block(yaml: &str) -> ContainerConfig {
        serde_yaml::from_str(yaml).unwrap()
    }

    #[test]
    fn package_overrides_defaults_field_by_field() {
        let defaults = block(
            "image: alpine:3.20\nnetwork: bridge\ntimeout: 30m\ntmpfs: [/tmp]\nenv:\n  pass: [HOME_TOKEN]\n  set: {A: one}\n",
        );
        let package = block("network: none\ntmpfs: [/run]\nenv:\n  set: {B: two}\n");
        let merged = ContainerConfig::merged(Some(&defaults), Some(&package)).unwrap();
        assert_eq!(merged.image.as_deref(), Some("alpine:3.20"));
        assert_eq!(merged.network, Some(NetworkMode::None));
        assert_eq!(merged.timeout, Some(Timeout(1800)));
        assert_eq!(merged.tmpfs, Some(vec!["/run".to_string()]));
        let env = merged.env.unwrap();
        assert_eq!(env.pass, Some(vec!["HOME_TOKEN".to_string()]));
        assert_eq!(
            env.set,
            Some(BTreeMap::from([("B".to_string(), "two".to_string())]))
        );
        assert_eq!(
            ContainerConfig::merged(None, Some(&package)),
            Some(package.clone())
        );
        assert_eq!(ContainerConfig::merged(None, None), None);
    }

    #[test]
    fn parses_durations() {
        assert_eq!(parse_duration("90").unwrap(), 90);
        assert_eq!(parse_duration("1800s").unwrap(), 1800);
        assert_eq!(parse_duration("30m").unwrap(), 1800);
        assert_eq!(parse_duration("2h").unwrap(), 7200);
        assert_eq!(block("timeout: 45").timeout, Some(Timeout(45)));
    }

    #[test]
    fn rejects_invalid_durations() {
        for text in [
            "",
            "0",
            "0s",
            "m",
            "1.5h",
            "-5s",
            "10d",
            "99999999999999999999h",
            "9223372036854775808",
        ] {
            let error = parse_duration(text).unwrap_err();
            assert_eq!(
                error.to_string(),
                format!("invalid duration `{text}`; use a positive number of seconds or a value such as 1800s, 30m or 2h")
            );
        }
    }

    #[test]
    fn parses_writable_entries_and_quantities() {
        let config = block(
            "writable: [target, {path: build, seed: copy}]\ncpus: 1.5\nmemory: 2g\nshm_size: 268435456\n",
        );
        assert_eq!(
            config.writable.unwrap(),
            vec![
                WritableConfig {
                    path: "target".into(),
                    seed: None
                },
                WritableConfig {
                    path: "build".into(),
                    seed: Some(SeedMode::Copy)
                },
            ]
        );
        assert_eq!(config.cpus, Some(Quantity("1.5".into())));
        assert_eq!(config.memory, Some(Quantity("2g".into())));
        assert_eq!(config.shm_size, Some(Quantity("268435456".into())));
    }
}
