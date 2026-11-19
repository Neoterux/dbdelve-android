//! A query built by tapping instead of typing: a table, its columns, filters
//! in groups, a count or a grouping, a sort and a limit, written out in the
//! engine's own spelling.
//!
//! The builder writes structure only. Every value the user types becomes a
//! `:name` parameter, which `params::fill` turns into a literal, so nothing
//! typed into a filter is ever spliced into the statement here. What it
//! writes lands in a tab the user can read, and runs through the gate like
//! anything typed.
//!
//! No joins, on purpose: they are where a tap-based builder gets worse than
//! typing.

use crate::Engine;
use crate::mongo_cmd;

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum FilterOp {
    Equals,
    NotEquals,
    Less,
    LessOrEqual,
    Greater,
    GreaterOrEqual,
    /// SQL's `LIKE`; a regular expression on MongoDB.
    Like,
    /// `LIKE` ignoring case: `ILIKE` on Postgres, `LOWER(…) LIKE LOWER(…)`
    /// on MySQL (whose collation may or may not ignore it already), a
    /// regular expression with the `i` option on MongoDB.
    LikeIgnoringCase,
    /// One of [`Filter::values`] values.
    In,
    /// From one value to another, both included.
    Between,
    IsNull,
    NotNull,
}

impl FilterOp {
    /// How many values the user types for it.
    fn values(self, filter: &Filter) -> usize {
        match self {
            FilterOp::IsNull | FilterOp::NotNull => 0,
            FilterOp::Between => 2,
            FilterOp::In => filter.values.max(1) as usize,
            _ => 1,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct Filter {
    pub column: String,
    pub op: FilterOp,
    /// How many values `In` lists, at least one; the others ignore it.
    pub values: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct QuerySpec {
    pub schema: String,
    pub table: String,
    /// Empty means every column. Ignored when counting or grouping, where
    /// the columns are the groups and the count.
    pub columns: Vec<String>,
    /// A row matches when every filter of any one group does: the groups are
    /// OR'd, the filters within one AND'd. Empty groups are skipped.
    pub groups: Vec<Vec<Filter>>,
    /// Count the rows (`COUNT(*)` as `count`), per group when grouping.
    pub count: bool,
    /// Group by these columns: one row per distinct combination.
    pub group_by: Vec<String>,
    /// A column, or `count` when counting.
    pub order_by: Option<String>,
    pub descending: bool,
    pub limit: Option<u32>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct BuiltQuery {
    pub text: String,
    /// The parameters each filter reads, in order: every group's filters,
    /// one after another. None for a filter that takes no value, two for
    /// `Between` (from, to), one per value for `In`.
    pub parameters: Vec<Vec<String>>,
}

/// The column a count is named, and sorted by.
pub const COUNT: &str = "count";

impl QuerySpec {
    fn aggregates(&self) -> bool {
        self.count || !self.group_by.is_empty()
    }
}

pub fn build(engine: Engine, spec: &QuerySpec) -> BuiltQuery {
    let mut taken: Vec<String> = Vec::new();
    let mut name = |base: &str| {
        let name = parameter_name(base, &taken);
        taken.push(name.clone());
        name
    };
    let parameters: Vec<Vec<String>> = spec
        .groups
        .iter()
        .flatten()
        .map(|filter| match filter.op {
            FilterOp::Between => vec![
                name(&format!("{}_from", filter.column)),
                name(&format!("{}_to", filter.column)),
            ],
            op => (0..op.values(filter))
                .map(|_| name(&filter.column))
                .collect(),
        })
        .collect();
    // The parameters again, group by group, as the writers walk them.
    let mut at = 0;
    let grouped: Vec<Vec<(&Filter, &[String])>> = spec
        .groups
        .iter()
        .map(|group| {
            group
                .iter()
                .map(|filter| {
                    at += 1;
                    (filter, parameters[at - 1].as_slice())
                })
                .collect()
        })
        .filter(|group: &Vec<_>| !group.is_empty())
        .collect();
    let text = match engine {
        Engine::Postgres => sql(engine, spec, &grouped, '"'),
        Engine::MySql => sql(engine, spec, &grouped, '`'),
        Engine::Mongo => mongo(spec, &grouped),
    };
    BuiltQuery { text, parameters }
}

type Grouped<'a> = [Vec<(&'a Filter, &'a [String])>];

fn sql(engine: Engine, spec: &QuerySpec, groups: &Grouped, mark: char) -> String {
    let quote = |name: &str| {
        let doubled = name.replace(mark, &format!("{mark}{mark}"));
        format!("{mark}{doubled}{mark}")
    };
    let columns = if spec.aggregates() {
        let mut list: Vec<String> = spec.group_by.iter().map(|c| quote(c)).collect();
        if spec.count {
            list.push(format!("COUNT(*) AS {}", quote(COUNT)));
        }
        list.join(", ")
    } else if spec.columns.is_empty() {
        "*".to_owned()
    } else {
        spec.columns
            .iter()
            .map(|c| quote(c))
            .collect::<Vec<_>>()
            .join(", ")
    };
    let table = if spec.schema.is_empty() {
        quote(&spec.table)
    } else {
        format!("{}.{}", quote(&spec.schema), quote(&spec.table))
    };
    let mut text = format!("SELECT {columns}\nFROM {table}");
    let condition = |(filter, params): &(&Filter, &[String])| {
        let column = quote(&filter.column);
        let p = |at: usize| format!(":{}", params[at]);
        match filter.op {
            FilterOp::Equals => format!("{column} = {}", p(0)),
            FilterOp::NotEquals => format!("{column} <> {}", p(0)),
            FilterOp::Less => format!("{column} < {}", p(0)),
            FilterOp::LessOrEqual => format!("{column} <= {}", p(0)),
            FilterOp::Greater => format!("{column} > {}", p(0)),
            FilterOp::GreaterOrEqual => format!("{column} >= {}", p(0)),
            FilterOp::Like => format!("{column} LIKE {}", p(0)),
            FilterOp::LikeIgnoringCase if engine == Engine::Postgres => {
                format!("{column} ILIKE {}", p(0))
            }
            FilterOp::LikeIgnoringCase => format!("LOWER({column}) LIKE LOWER({})", p(0)),
            FilterOp::In => format!(
                "{column} IN ({})",
                params
                    .iter()
                    .map(|name| format!(":{name}"))
                    .collect::<Vec<_>>()
                    .join(", ")
            ),
            FilterOp::Between => format!("{column} BETWEEN {} AND {}", p(0), p(1)),
            FilterOp::IsNull => format!("{column} IS NULL"),
            FilterOp::NotNull => format!("{column} IS NOT NULL"),
        }
    };
    match groups {
        [] => {}
        [only] => {
            let conditions: Vec<String> = only.iter().map(condition).collect();
            text.push_str("\nWHERE ");
            text.push_str(&conditions.join("\n  AND "));
        }
        several => {
            let groups: Vec<String> = several
                .iter()
                .map(|group| {
                    let conditions: Vec<String> = group.iter().map(condition).collect();
                    format!("({})", conditions.join(" AND "))
                })
                .collect();
            text.push_str("\nWHERE ");
            text.push_str(&groups.join("\n   OR "));
        }
    }
    if !spec.group_by.is_empty() {
        let keys: Vec<String> = spec.group_by.iter().map(|c| quote(c)).collect();
        text.push_str(&format!("\nGROUP BY {}", keys.join(", ")));
    }
    if let Some(column) = &spec.order_by {
        text.push_str(&format!(
            "\nORDER BY {}{}",
            quote(column),
            if spec.descending { " DESC" } else { "" }
        ));
    }
    if let Some(limit) = spec.limit {
        text.push_str(&format!("\nLIMIT {limit}"));
    }
    text
}

// ponytail: a collection or field whose name holds `"` or `\` is written with
// JSON's backslash escape, which the SQL tokenizer `params` finds `:name`s
// with does not read; its values are then left unfilled, the command does not
// parse and the gate asks. A shared MongoDB-aware tokenizer is the fix.
/// `find` while that says it all; a pipeline once there is a sort, a limit,
/// a count or a grouping, since this app's MongoDB commands take no cursor
/// chains.
fn mongo(spec: &QuerySpec, groups: &Grouped) -> String {
    let key = |name: &str| serde_json::Value::from(name).to_string();
    let condition = |(filter, params): &(&Filter, &[String])| {
        let p = |at: usize| format!(":{}", params[at]);
        let value = match filter.op {
            FilterOp::Equals => p(0),
            FilterOp::NotEquals => format!("{{\"$ne\": {}}}", p(0)),
            FilterOp::Less => format!("{{\"$lt\": {}}}", p(0)),
            FilterOp::LessOrEqual => format!("{{\"$lte\": {}}}", p(0)),
            FilterOp::Greater => format!("{{\"$gt\": {}}}", p(0)),
            FilterOp::GreaterOrEqual => format!("{{\"$gte\": {}}}", p(0)),
            FilterOp::Like => format!("{{\"$regex\": {}}}", p(0)),
            FilterOp::LikeIgnoringCase => format!("{{\"$regex\": {}, \"$options\": \"i\"}}", p(0)),
            FilterOp::In => format!(
                "{{\"$in\": [{}]}}",
                params
                    .iter()
                    .map(|name| format!(":{name}"))
                    .collect::<Vec<_>>()
                    .join(", ")
            ),
            FilterOp::Between => format!("{{\"$gte\": {}, \"$lte\": {}}}", p(0), p(1)),
            FilterOp::IsNull => "null".to_owned(),
            FilterOp::NotNull => "{\"$ne\": null}".to_owned(),
        };
        format!("{}: {value}", key(&filter.column))
    };
    // One document per group. A field filtered twice in one group would be a
    // key written twice, of which the server keeps one: those go in `$and`.
    let group = |filters: &Vec<(&Filter, &[String])>| {
        let mut seen: Vec<&str> = Vec::new();
        let repeats = filters.iter().any(|(filter, _)| {
            seen.contains(&filter.column.as_str()) || {
                seen.push(&filter.column);
                false
            }
        });
        let conditions: Vec<String> = filters.iter().map(condition).collect();
        if repeats {
            let each: Vec<String> = conditions.iter().map(|c| format!("{{{c}}}")).collect();
            format!("{{\"$and\": [{}]}}", each.join(", "))
        } else {
            format!("{{{}}}", conditions.join(", "))
        }
    };
    let filter = match groups {
        [] => "{}".to_owned(),
        [only] => group(only),
        several => {
            let each: Vec<String> = several.iter().map(group).collect();
            format!("{{\"$or\": [{}]}}", each.join(", "))
        }
    };
    let projection = (!spec.columns.is_empty()).then(|| {
        let fields: Vec<String> = spec
            .columns
            .iter()
            .map(|c| format!("{}: 1", key(c)))
            .collect();
        format!("{{{}}}", fields.join(", "))
    });
    let collection = mongo_cmd::collection_ref(&spec.table);
    if !spec.aggregates() && spec.order_by.is_none() && spec.limit.is_none() {
        return match projection {
            Some(projection) => format!("{collection}.find({filter}, {projection})"),
            None => format!("{collection}.find({filter})"),
        };
    }
    let mut stages = vec![format!("{{\"$match\": {filter}}}")];
    let sort = spec.order_by.as_ref().map(|column| {
        format!(
            "{{\"$sort\": {{{}: {}}}}}",
            key(column),
            if spec.descending { -1 } else { 1 }
        )
    });
    let limit = spec.limit.map(|limit| format!("{{\"$limit\": {limit}}}"));
    if spec.group_by.is_empty() && spec.count {
        stages.push(format!("{{\"$count\": {}}}", key(COUNT)));
    } else if spec.aggregates() {
        // Grouped under positional keys, then each put back under its own
        // name, so a field's name is only ever a key, never a path.
        // ponytail: a field named with a `.` or a leading `$` reads as a path
        // in `"$field"`; the app offers only plain names to group by.
        let ids: Vec<String> = spec
            .group_by
            .iter()
            .enumerate()
            .map(|(at, column)| format!("\"g{at}\": {}", key(&format!("${column}"))))
            .collect();
        let count = if spec.count {
            format!(", {}: {{\"$sum\": 1}}", key(COUNT))
        } else {
            String::new()
        };
        stages.push(format!(
            "{{\"$group\": {{\"_id\": {{{}}}{count}}}}}",
            ids.join(", ")
        ));
        let mut fields: Vec<String> = vec!["\"_id\": 0".to_owned()];
        fields.extend(
            spec.group_by
                .iter()
                .enumerate()
                .map(|(at, column)| format!("{}: \"$_id.g{at}\"", key(column))),
        );
        // Computed like the rest, not `1`, which would keep it where `$group`
        // put it: ahead of the groups.
        if spec.count {
            fields.push(format!("{}: \"${COUNT}\"", key(COUNT)));
        }
        stages.push(format!("{{\"$project\": {{{}}}}}", fields.join(", ")));
        stages.extend(sort);
        stages.extend(limit);
    } else {
        stages.extend(sort);
        stages.extend(limit);
        if let Some(projection) = projection {
            stages.push(format!("{{\"$project\": {projection}}}"));
        }
    }
    format!("{collection}.aggregate([\n  {}\n])", stages.join(",\n  "))
}

/// A parameter named after its column where the column's name can be one,
/// unique among those already [`taken`].
fn parameter_name(column: &str, taken: &[String]) -> String {
    let mut base: String = column
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() {
                c.to_ascii_lowercase()
            } else {
                '_'
            }
        })
        .collect();
    base = base.trim_matches('_').to_owned();
    if !base.starts_with(|c: char| c.is_ascii_alphabetic()) {
        base = format!("p_{base}").trim_end_matches('_').to_owned();
    }
    if !taken.contains(&base) {
        return base;
    }
    (2..)
        .map(|n| format!("{base}_{n}"))
        .find(|name| !taken.contains(name))
        .expect("an unbounded range has a free name")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gate::{Mode, classify};
    use crate::params::{self, Binding, ParamValue};

    fn spec(filters: Vec<Filter>) -> QuerySpec {
        QuerySpec {
            schema: "public".into(),
            table: "order\"s".into(),
            columns: vec!["id".into(), "total".into()],
            groups: vec![filters],
            count: false,
            group_by: Vec::new(),
            order_by: Some("id".into()),
            descending: true,
            limit: Some(50),
        }
    }

    fn filter(column: &str, op: FilterOp) -> Filter {
        Filter {
            column: column.into(),
            op,
            values: 1,
        }
    }

    #[test]
    fn writes_values_only_as_parameters() {
        let built = build(
            Engine::Postgres,
            &spec(vec![
                filter("status", FilterOp::Equals),
                filter("status", FilterOp::NotEquals),
                filter("deleted at", FilterOp::IsNull),
                filter("1st", FilterOp::Greater),
            ]),
        );
        assert_eq!(
            built.parameters,
            [vec!["status"], vec!["status_2"], vec![], vec!["p_1st"]]
        );
        assert_eq!(
            built.text,
            "SELECT \"id\", \"total\"\nFROM \"public\".\"order\"\"s\"\n\
             WHERE \"status\" = :status\n  AND \"status\" <> :status_2\n  \
             AND \"deleted at\" IS NULL\n  AND \"1st\" > :p_1st\n\
             ORDER BY \"id\" DESC\nLIMIT 50"
        );
        assert_eq!(
            params::parameters(Engine::Postgres, &built.text),
            ["status", "status_2", "p_1st"]
        );
    }

    #[test]
    fn a_filled_value_cannot_escape_its_literal() {
        let built = build(Engine::MySql, &spec(vec![filter("name", FilterOp::Like)]));
        let filled = params::fill(
            Engine::MySql,
            &built.text,
            &[Binding {
                name: "name".into(),
                value: ParamValue::Text {
                    value: "x'; DROP TABLE t; --".into(),
                },
            }],
        )
        .unwrap();
        assert!(filled.contains("`name` LIKE 'x''; DROP TABLE t; --'"));
        assert_eq!(classify(Engine::MySql, &filled).mode, Mode::ReadOnly);
    }

    #[test]
    fn mongo_reads_back_through_the_shell_parser() {
        let plain = QuerySpec {
            table: "order items".into(),
            order_by: None,
            limit: None,
            columns: Vec::new(),
            ..spec(vec![filter("age", FilterOp::Greater)])
        };
        let built = build(Engine::Mongo, &plain);
        assert_eq!(
            built.text,
            "db.getCollection(\"order items\").find({\"age\": {\"$gt\": :age}})"
        );
        let sorted = build(
            Engine::Mongo,
            &QuerySpec {
                table: "orders".into(),
                ..spec(vec![
                    filter("tag", FilterOp::NotNull),
                    filter("n", FilterOp::Less),
                ])
            },
        );
        for text in [built.text, sorted.text] {
            let names = params::parameters(Engine::Mongo, &text);
            let bindings: Vec<Binding> = names
                .iter()
                .map(|name| Binding {
                    name: name.clone(),
                    value: ParamValue::Number { value: "3".into() },
                })
                .collect();
            let filled = params::fill(Engine::Mongo, &text, &bindings).unwrap();
            assert!(mongo_cmd::parse(&filled).is_ok(), "{filled}");
            assert_eq!(
                classify(Engine::Mongo, &filled).mode,
                Mode::ReadOnly,
                "{filled}"
            );
        }
    }

    fn fill_all(engine: Engine, text: &str) -> String {
        let bindings: Vec<Binding> = params::parameters(engine, text)
            .into_iter()
            .map(|name| Binding {
                name,
                value: ParamValue::Number { value: "3".into() },
            })
            .collect();
        params::fill(engine, text, &bindings).unwrap()
    }

    #[test]
    fn writes_in_between_ignoring_case_and_or_groups() {
        let groups = vec![
            vec![
                Filter {
                    values: 3,
                    ..filter("status", FilterOp::In)
                },
                filter("total", FilterOp::Between),
            ],
            vec![filter("name", FilterOp::LikeIgnoringCase)],
        ];
        let spec = QuerySpec {
            groups,
            order_by: None,
            limit: None,
            ..spec(Vec::new())
        };
        let pg = build(Engine::Postgres, &spec);
        assert_eq!(
            pg.parameters,
            [
                vec!["status", "status_2", "status_3"],
                vec!["total_from", "total_to"],
                vec!["name"],
            ]
        );
        assert_eq!(
            pg.text,
            "SELECT \"id\", \"total\"\nFROM \"public\".\"order\"\"s\"\n\
             WHERE (\"status\" IN (:status, :status_2, :status_3) \
             AND \"total\" BETWEEN :total_from AND :total_to)\n   \
             OR (\"name\" ILIKE :name)"
        );
        let my = build(Engine::MySql, &spec);
        assert!(
            my.text.contains("LOWER(`name`) LIKE LOWER(:name)"),
            "{}",
            my.text
        );
        for engine in [Engine::Postgres, Engine::MySql] {
            let filled = fill_all(engine, &build(engine, &spec).text);
            assert_eq!(classify(engine, &filled).mode, Mode::ReadOnly, "{filled}");
        }
        let mongo = build(
            Engine::Mongo,
            &QuerySpec {
                columns: Vec::new(),
                ..spec
            },
        );
        assert_eq!(
            mongo.text,
            "db.getCollection(\"order\\\"s\").find({\"$or\": [{\"status\": {\"$in\": [:status, :status_2, :status_3]}, \
             \"total\": {\"$gte\": :total_from, \"$lte\": :total_to}}, \
             {\"name\": {\"$regex\": :name, \"$options\": \"i\"}}]})"
        );
    }

    #[test]
    fn counts_and_groups() {
        let counted = QuerySpec {
            count: true,
            group_by: vec!["status".into()],
            order_by: Some(COUNT.into()),
            ..spec(vec![filter("total", FilterOp::Greater)])
        };
        assert_eq!(
            build(Engine::Postgres, &counted).text,
            "SELECT \"status\", COUNT(*) AS \"count\"\nFROM \"public\".\"order\"\"s\"\n\
             WHERE \"total\" > :total\nGROUP BY \"status\"\nORDER BY \"count\" DESC\nLIMIT 50"
        );
        let only_count = QuerySpec {
            group_by: Vec::new(),
            order_by: None,
            limit: None,
            ..counted.clone()
        };
        assert_eq!(
            build(Engine::MySql, &only_count).text,
            "SELECT COUNT(*) AS `count`\nFROM `public`.`order\"s`\nWHERE `total` > :total"
        );
        let mongo = QuerySpec {
            table: "orders".into(),
            ..counted
        };
        assert_eq!(
            build(Engine::Mongo, &mongo).text,
            "db.orders.aggregate([\n  {\"$match\": {\"total\": {\"$gt\": :total}}},\n  \
             {\"$group\": {\"_id\": {\"g0\": \"$status\"}, \"count\": {\"$sum\": 1}}},\n  \
             {\"$project\": {\"_id\": 0, \"status\": \"$_id.g0\", \"count\": \"$count\"}},\n  \
             {\"$sort\": {\"count\": -1}},\n  {\"$limit\": 50}\n])"
        );
        assert_eq!(
            build(
                Engine::Mongo,
                &QuerySpec {
                    table: "orders".into(),
                    ..only_count
                }
            )
            .text,
            "db.orders.aggregate([\n  {\"$match\": {\"total\": {\"$gt\": :total}}},\n  {\"$count\": \"count\"}\n])"
        );
    }

    #[test]
    fn a_field_filtered_twice_in_one_group_keeps_both_on_mongo() {
        let both = QuerySpec {
            table: "orders".into(),
            order_by: None,
            limit: None,
            columns: Vec::new(),
            ..spec(vec![
                filter("n", FilterOp::Greater),
                filter("n", FilterOp::Less),
            ])
        };
        let text = build(Engine::Mongo, &both).text;
        assert_eq!(
            text,
            "db.orders.find({\"$and\": [{\"n\": {\"$gt\": :n}}, {\"n\": {\"$lt\": :n_2}}]})"
        );
        let filled = fill_all(Engine::Mongo, &text);
        assert!(mongo_cmd::parse(&filled).is_ok(), "{filled}");
    }
}
