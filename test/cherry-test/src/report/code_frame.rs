#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct FrameLine {
    pub number: Option<u32>,
    pub text: String,
    pub marked: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum ReasonPart {
    Text(String),
    Frame {
        lines: Vec<FrameLine>,
        source: Option<String>,
    },
}

/// Splits a failure reason into plain text and the `>  7 | code` frames test runners print.
pub(crate) fn split_reason(reason: &str) -> Vec<ReasonPart> {
    let lines: Vec<&str> = reason.lines().collect();
    let mut parts = Vec::new();
    let mut text: Vec<&str> = Vec::new();
    let mut frame: Vec<FrameLine> = Vec::new();
    for (index, line) in lines.iter().enumerate() {
        if let Some(frame_line) = parse_frame_line(line) {
            if frame.is_empty() {
                push_text(&mut parts, &mut text);
            }
            frame.push(frame_line);
            continue;
        }
        if !frame.is_empty() {
            let source = lines
                .get(index..)
                .and_then(|rest| rest.iter().find_map(|next| frame_source(next)));
            parts.push(ReasonPart::Frame {
                lines: std::mem::take(&mut frame),
                source,
            });
        }
        text.push(line);
    }
    if !frame.is_empty() {
        parts.push(ReasonPart::Frame {
            lines: frame,
            source: None,
        });
    }
    push_text(&mut parts, &mut text);
    parts
}

fn push_text(parts: &mut Vec<ReasonPart>, text: &mut Vec<&str>) {
    let joined = text.join("\n");
    text.clear();
    let trimmed = joined.trim_matches('\n');
    if !trimmed.trim().is_empty() {
        parts.push(ReasonPart::Text(trimmed.to_string()));
    }
}

fn parse_frame_line(line: &str) -> Option<FrameLine> {
    let trimmed = line.trim_start();
    let (marked, rest) = match trimmed.strip_prefix('>') {
        Some(rest) => (true, rest.trim_start()),
        None => (false, trimmed),
    };
    let (gutter, code) = rest.split_once('|')?;
    let gutter = gutter.trim();
    let code = code.strip_prefix(' ').unwrap_or(code);
    if gutter.is_empty() {
        let is_pointer =
            !marked && code.contains('^') && code.chars().all(|c| c == ' ' || c == '^');
        return is_pointer.then(|| FrameLine {
            number: None,
            text: code.to_string(),
            marked: false,
        });
    }
    let number = gutter.parse::<u32>().ok()?;
    Some(FrameLine {
        number: Some(number),
        text: code.to_string(),
        marked,
    })
}

fn frame_source(line: &str) -> Option<String> {
    let location = line.trim_start().strip_prefix("at ")?;
    let location = location
        .rsplit_once('(')
        .map(|(_, inner)| inner.trim_end_matches(')'))
        .unwrap_or(location);
    let mut pieces = location.rsplitn(3, ':');
    let column = pieces.next()?;
    let row = pieces.next()?;
    let path = pieces.next()?;
    (column.parse::<u32>().is_ok() && row.parse::<u32>().is_ok()).then(|| path.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    const PLAYWRIGHT: &str = "Error: page.goto: Page crashed

   6 |   test(`shows the value`, async ({ page }) => {
>  7 |     await page.goto(url);
     |                ^
   8 |   });
    at /opt/mini/polyglot/browser/functional.spec.ts:7:16";

    fn frame(parts: Vec<ReasonPart>) -> (Vec<FrameLine>, Option<String>) {
        parts
            .into_iter()
            .find_map(|part| match part {
                ReasonPart::Frame { lines, source } => Some((lines, source)),
                ReasonPart::Text(_) => None,
            })
            .unwrap()
    }

    #[test]
    fn keeps_the_message_before_the_frame_as_text() {
        let parts = split_reason(PLAYWRIGHT);
        assert_eq!(
            parts[0],
            ReasonPart::Text("Error: page.goto: Page crashed".into())
        );
    }

    #[test]
    fn reads_line_numbers_and_code_with_its_indentation() {
        let (lines, _) = frame(split_reason(PLAYWRIGHT));
        assert_eq!(lines[0].number, Some(6));
        assert_eq!(
            lines[0].text,
            "  test(`shows the value`, async ({ page }) => {"
        );
    }

    #[test]
    fn marks_the_failing_line() {
        let (lines, _) = frame(split_reason(PLAYWRIGHT));
        assert!(lines[1].marked);
        assert!(!lines[0].marked);
    }

    #[test]
    fn keeps_the_column_pointer_without_a_number() {
        let (lines, _) = frame(split_reason(PLAYWRIGHT));
        assert_eq!(lines[2].number, None);
        assert_eq!(lines[2].text.trim(), "^");
    }

    #[test]
    fn takes_the_source_path_from_the_stack_line() {
        let (_, source) = frame(split_reason(PLAYWRIGHT));
        assert_eq!(
            source.as_deref(),
            Some("/opt/mini/polyglot/browser/functional.spec.ts")
        );
    }

    #[test]
    fn reads_the_source_inside_parentheses() {
        let reason = "boom\n> 3 | throw err\n    at run (src/app.test.ts:3:9)";
        let (_, source) = frame(split_reason(reason));
        assert_eq!(source.as_deref(), Some("src/app.test.ts"));
    }

    #[test]
    fn keeps_the_stack_line_as_text_after_the_frame() {
        let parts = split_reason(PLAYWRIGHT);
        assert_eq!(
            parts.last(),
            Some(&ReasonPart::Text(
                "    at /opt/mini/polyglot/browser/functional.spec.ts:7:16".into()
            ))
        );
    }

    #[test]
    fn leaves_a_reason_without_a_frame_as_one_text_part() {
        let reason = "assertion `left == right` failed\n  left: 1800\n right: 1500";
        assert_eq!(split_reason(reason), vec![ReasonPart::Text(reason.into())]);
    }

    #[test]
    fn does_not_treat_a_table_cell_as_a_frame() {
        let reason = "expected | actual";
        assert_eq!(split_reason(reason), vec![ReasonPart::Text(reason.into())]);
    }

    #[test]
    fn splits_two_frames_with_text_between_them() {
        let reason = "first\n> 1 | a()\n    at x.ts:1:1\nsecond\n> 2 | b()\n    at x.ts:2:1";
        let frames = split_reason(reason)
            .into_iter()
            .filter(|part| matches!(part, ReasonPart::Frame { .. }))
            .count();
        assert_eq!(frames, 2);
    }
}
