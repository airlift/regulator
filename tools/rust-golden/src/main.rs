use std::io::{self, BufRead};

fn decode(value: &str) -> String {
    if value == "-" {
        return String::new();
    }
    String::from_utf8(
        (0..value.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&value[i..i + 2], 16).unwrap())
            .collect(),
    )
    .unwrap()
}

fn main() {
    println!("# Rust regex 1.13.1: id, pattern-hex, input-hex, captures for successive matches");
    for line in io::stdin().lock().lines() {
        let line = line.unwrap();
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        let fields: Vec<_> = line.split('\t').collect();
        assert_eq!(fields.len(), 3);
        let pattern = decode(fields[1]);
        let input = decode(fields[2]);
        let result = match regex::Regex::new(&pattern) {
            Err(_) => "ERROR".to_string(),
            Ok(re) => {
                let matches: Vec<String> = re
                    .captures_iter(&input)
                    .map(|captures| {
                        captures
                            .iter()
                            .map(|m| match m {
                                Some(m) => format!("{}:{}", m.start(), m.end()),
                                None => "-1:-1".to_string(),
                            })
                            .collect::<Vec<_>>()
                            .join(",")
                    })
                    .collect();
                if matches.is_empty() {
                    "NONE".to_string()
                } else {
                    matches.join(";")
                }
            }
        };
        println!("{}\t{}", line, result);
    }
}
