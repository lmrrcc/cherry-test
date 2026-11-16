use crate::discover::ProjectGroup;
use crate::durations::{percentile_75, Durations};

/// `--shard I/N`: run the packages assigned to shard `index` (1-based) of `count`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Shard {
    pub index: u32,
    pub count: u32,
}

impl std::str::FromStr for Shard {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let error = || "expected I/N with 1 <= I <= N, such as 1/4".to_string();
        let (index, count) = value.split_once('/').ok_or_else(error)?;
        let index: u32 = index.parse().map_err(|_| error())?;
        let count: u32 = count.parse().map_err(|_| error())?;
        if index == 0 || count == 0 || index > count {
            return Err(error());
        }
        Ok(Shard { index, count })
    }
}

impl std::fmt::Display for Shard {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}/{}", self.index, self.count)
    }
}

/// The 0-based shard of every package, given each package's recorded total in configuration order.
///
/// With no recorded total, packages are dealt round-robin. Otherwise the longest package goes first to the
/// least loaded shard (lowest shard on ties); packages without a record count as the 75th percentile of
/// recorded ones. Equal inputs always give equal assignments.
pub fn assign(totals: &[Option<u64>], count: u32) -> Vec<u32> {
    let count = count.max(1);
    let known: Vec<u64> = totals.iter().flatten().copied().collect();
    if known.is_empty() {
        return (0..totals.len())
            .map(|position| (position % count as usize) as u32)
            .collect();
    }
    let fallback = percentile_75(&known);
    let estimates: Vec<u64> = totals
        .iter()
        .map(|total| total.unwrap_or(fallback))
        .collect();
    let mut order: Vec<usize> = (0..totals.len()).collect();
    order.sort_by(|a, b| estimates[*b].cmp(&estimates[*a]).then(a.cmp(b)));
    let mut loads = vec![0u64; count as usize];
    let mut shards = vec![0u32; totals.len()];
    for position in order {
        let mut target = 0;
        for (shard, load) in loads.iter().enumerate() {
            if *load < loads[target] {
                target = shard;
            }
        }
        loads[target] = loads[target].saturating_add(estimates[position]);
        shards[position] = target as u32;
    }
    shards
}

/// Keeps the packages of `shard`, in configuration order; projects left without packages are dropped.
pub fn select(
    projects: Vec<ProjectGroup>,
    shard: Shard,
    durations: &Durations,
) -> Vec<ProjectGroup> {
    let totals: Vec<Option<u64>> = projects
        .iter()
        .flat_map(|project| {
            project
                .members
                .iter()
                .map(|member| durations.package_total(&project.id, &member.name))
        })
        .collect();
    let shards = assign(&totals, shard.count);
    let mut position = 0;
    projects
        .into_iter()
        .filter_map(|project| {
            let members: Vec<_> = project
                .members
                .into_iter()
                .filter(|_| {
                    let keep = shards[position] + 1 == shard.index;
                    position += 1;
                    keep
                })
                .collect();
            (!members.is_empty()).then_some(ProjectGroup {
                id: project.id,
                members,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;
    use std::time::Duration;

    use super::*;
    use crate::discover::WorkspaceMember;
    use crate::plan::JobKey;
    use cherry_test_core::PackageRuntime;

    fn projects() -> Vec<ProjectGroup> {
        [("a", vec!["a1", "a2"]), ("b", vec!["b1", "b2", "b3"])]
            .into_iter()
            .map(|(id, names)| ProjectGroup {
                id: id.into(),
                members: names
                    .into_iter()
                    .map(|name| WorkspaceMember {
                        name: name.into(),
                        manifest_path: PathBuf::from(format!("{id}/{name}/Cargo.toml")),
                        project_id: id.into(),
                        runtime: PackageRuntime::Rust,
                    })
                    .collect(),
            })
            .collect()
    }

    fn names(projects: &[ProjectGroup]) -> Vec<String> {
        projects
            .iter()
            .flat_map(|project| project.members.iter().map(|member| member.name.clone()))
            .collect()
    }

    #[test]
    fn parses_one_based_shards() {
        assert_eq!("2/4".parse(), Ok(Shard { index: 2, count: 4 }));
        assert_eq!(Shard { index: 1, count: 3 }.to_string(), "1/3");
        for invalid in ["0/4", "5/4", "1/0", "1", "a/b", "1/2/3", "-1/2"] {
            assert!(invalid.parse::<Shard>().is_err(), "{invalid}");
        }
    }

    #[test]
    fn deals_packages_round_robin_without_durations() {
        assert_eq!(assign(&[None, None, None, None, None], 2), [0, 1, 0, 1, 0]);
        let durations = Durations::default();
        let first = select(projects(), Shard { index: 1, count: 2 }, &durations);
        let second = select(projects(), Shard { index: 2, count: 2 }, &durations);
        assert_eq!(names(&first), ["a1", "b1", "b3"]);
        assert_eq!(names(&second), ["a2", "b2"]);
        assert!(select(projects(), Shard { index: 6, count: 6 }, &durations).is_empty());
    }

    #[test]
    fn packs_the_longest_packages_first_onto_the_lightest_shard() {
        assert_eq!(
            assign(&[Some(10), Some(70), Some(30), Some(30), Some(20)], 2),
            [0, 0, 1, 1, 1]
        );
        assert_eq!(assign(&[Some(5), None, Some(1)], 3), [0, 1, 2]);
        let mut durations = Durations::default();
        for (package, millis) in [("a1", 100), ("a2", 900), ("b1", 400), ("b2", 450)] {
            durations.record(
                &JobKey::new(
                    if package.starts_with('a') { "a" } else { "b" },
                    package,
                    "unit",
                ),
                Duration::from_millis(millis),
            );
        }
        durations.record(
            &JobKey::new("a", "a1", "integration"),
            Duration::from_millis(500),
        );
        let first = select(projects(), Shard { index: 1, count: 2 }, &durations);
        let second = select(projects(), Shard { index: 2, count: 2 }, &durations);
        assert_eq!(names(&first), ["a2", "b2"]);
        assert_eq!(names(&second), ["a1", "b1", "b3"]);
        let mut all = names(&first);
        all.extend(names(&second));
        all.sort();
        assert_eq!(all, ["a1", "a2", "b1", "b2", "b3"]);
    }
}
