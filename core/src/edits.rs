//! Row editing: the statement that writes a changed value back to the one
//! row it came from, named by its primary key, and the check that the
//! statement is that and nothing else.
//!
//! The desktop's primary-key `UPDATE`, review and write gate, cut down to
//! what a phone needs. The app knows which table a result came from only when
//! it wrote the query itself (the builder's, or a table's preview), and a row
//! is named only by its whole primary key, which matches one row at most.
//! The statement is shown before it runs, held where the UI cannot change it,
//! and goes through the gate like anything typed: it needs Read-write.

use bson::Bson;
use sqlparser::ast::{AssignmentTarget, BinaryOperator, Expr, Statement, TableFactor, Value};
use sqlparser::dialect::{Dialect, MySqlDialect, PostgreSqlDialect};
use sqlparser::parser::Parser;

use crate::gate::{self, Mode, Verdict};
use crate::mongo_cmd::{self, Call};
use crate::users::Prepared;
use crate::{CoreError, Engine};

/// The table a result came from and its primary key, in key order.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct EditTarget {
    pub schema: String,
    pub table: String,
    pub key: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum NewValue {
    /// Written as a string literal; the server converts it to the column's
    /// type as it would a typed one.
    Text {
        value: String,
    },
    /// MongoDB only: a JSON value (Extended JSON for types), written as that
    /// value rather than as a string.
    Json {
        value: String,
    },
    Null,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct CellEdit {
    pub column: String,
    pub value: NewValue,
}

/// One key column's value as the server sent it in the result.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct KeyValue {
    pub column: String,
    pub value: String,
    /// The result column's type tag: on MongoDB it says whether `_id` was a
    /// string or a value written as Extended JSON.
    pub type_tag: String,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct RowEdit {
    pub key: Vec<KeyValue>,
    pub changes: Vec<CellEdit>,
}

fn refused(reason: impl Into<String>) -> CoreError {
    CoreError::Query {
        reason: reason.into(),
    }
}

fn identifier(name: &str, mark: char) -> String {
    format!(
        "{mark}{}{mark}",
        name.replace(mark, &format!("{mark}{mark}"))
    )
}

/// The statements that write `rows` back: on Postgres and MySQL one
/// statement for all of them, which the server applies whole or not at all;
/// on MongoDB one `updateOne` per row, run in order.
pub fn prepare(
    engine: Engine,
    target: &EditTarget,
    rows: &[RowEdit],
) -> Result<Vec<Prepared>, CoreError> {
    if target.key.is_empty() {
        return Err(refused(format!(
            "{} has no primary key, so a row cannot be named for sure.",
            target.table
        )));
    }
    if rows.is_empty() || rows.iter().all(|row| row.changes.is_empty()) {
        return Err(refused("Nothing has changed."));
    }
    for row in rows {
        let named: Vec<&str> = row.key.iter().map(|key| key.column.as_str()).collect();
        if named != target.key.iter().map(String::as_str).collect::<Vec<_>>() {
            return Err(refused(
                "A row's key does not match the table's primary key.",
            ));
        }
        if let Some(change) = row
            .changes
            .iter()
            .find(|change| target.key.contains(&change.column))
        {
            return Err(refused(format!(
                "{} is part of the primary key, which names the row; it is not edited here.",
                change.column
            )));
        }
    }
    let rows: Vec<&RowEdit> = rows.iter().filter(|row| !row.changes.is_empty()).collect();
    match engine {
        Engine::Postgres | Engine::MySql => {
            let text = sql(engine, target, &rows)?;
            check_sql(engine, target, &text)?;
            Ok(vec![Prepared::new(text.clone(), text, Verdict::WRITE)])
        }
        Engine::Mongo => rows
            .iter()
            .map(|row| {
                let text = mongo(target, row)?;
                check_mongo(target, &text)?;
                Ok(Prepared::new(text.clone(), text, Verdict::WRITE))
            })
            .collect(),
    }
}

fn sql(engine: Engine, target: &EditTarget, rows: &[&RowEdit]) -> Result<String, CoreError> {
    let mark = if engine == Engine::MySql { '`' } else { '"' };
    // A backslash is refused, not escaped: whether it escapes depends on
    // server settings the app cannot see (see the gate).
    let literal = |text: &str| -> Result<String, CoreError> {
        if text.contains('\\') || text.contains('\0') {
            return Err(refused(
                "A value with a backslash cannot be written here: whether it escapes \
                 depends on a server setting the app cannot see. Write that UPDATE in \
                 the editor.",
            ));
        }
        Ok(format!("'{}'", text.replace('\'', "''")))
    };
    let table = if target.schema.is_empty() {
        identifier(&target.table, mark)
    } else {
        format!(
            "{}.{}",
            identifier(&target.schema, mark),
            identifier(&target.table, mark)
        )
    };
    let mut statements = Vec::new();
    for row in rows {
        let assignments = row
            .changes
            .iter()
            .map(|change| {
                let value = match &change.value {
                    NewValue::Text { value } => literal(value)?,
                    NewValue::Null => "NULL".to_owned(),
                    NewValue::Json { .. } => {
                        return Err(refused("A JSON value is MongoDB's; give SQL text or NULL."));
                    }
                };
                Ok(format!("{} = {value}", identifier(&change.column, mark)))
            })
            .collect::<Result<Vec<_>, CoreError>>()?;
        let predicate = row
            .key
            .iter()
            .map(|key| {
                Ok(format!(
                    "{} = {}",
                    identifier(&key.column, mark),
                    literal(&key.value)?
                ))
            })
            .collect::<Result<Vec<_>, CoreError>>()?;
        statements.push(format!(
            "UPDATE {table} SET {} WHERE {};",
            assignments.join(", "),
            predicate.join(" AND ")
        ));
    }
    // Postgres runs one submission of several statements as one
    // transaction; MySQL needs it said.
    if engine == Engine::MySql && statements.len() > 1 {
        statements.insert(0, "START TRANSACTION;".to_owned());
        statements.push("COMMIT;".to_owned());
    }
    Ok(statements.join("\n"))
}

/// The write gate: the text is parsed back and must be exactly the edit it
/// claims to be. `UPDATE` of the target table only (and, on MySQL, the
/// transaction around several), every value a literal, the row named by an
/// equality on each key column with a literal, nothing else. And the gate
/// must read it as a plain write. Anything else is refused before it can be
/// shown, let alone run.
fn check_sql(engine: Engine, target: &EditTarget, text: &str) -> Result<(), CoreError> {
    let wrote = |what: &str| {
        refused(format!(
            "DBDelve refused to run a statement it wrote itself: {what}. Nothing was sent."
        ))
    };
    let dialect: Box<dyn Dialect> = match engine {
        Engine::MySql => Box::new(MySqlDialect {}),
        _ => Box::new(PostgreSqlDialect {}),
    };
    let statements =
        Parser::parse_sql(dialect.as_ref(), text).map_err(|_| wrote("it does not parse"))?;
    let mark = if engine == Engine::MySql { '`' } else { '"' };
    let table = if target.schema.is_empty() {
        identifier(&target.table, mark)
    } else {
        format!(
            "{}.{}",
            identifier(&target.schema, mark),
            identifier(&target.table, mark)
        )
    };
    let key: Vec<String> = target.key.iter().map(|k| identifier(k, mark)).collect();
    let literal = |expr: &Expr| {
        matches!(
            expr,
            Expr::Value(value) if matches!(value.value, Value::SingleQuotedString(_) | Value::Null)
        )
    };
    let last = statements.len().saturating_sub(1);
    let mut updates = 0;
    for (at, statement) in statements.iter().enumerate() {
        match statement {
            Statement::StartTransaction { .. }
                if engine == Engine::MySql && at == 0 && last > 1 => {}
            Statement::Commit { .. } if engine == Engine::MySql && at == last && last > 1 => {}
            Statement::Update(update) => {
                updates += 1;
                let TableFactor::Table {
                    name, alias: None, ..
                } = &update.table.relation
                else {
                    return Err(wrote("it does not update a plain table"));
                };
                if name.to_string() != table || !update.table.joins.is_empty() {
                    return Err(wrote("it updates another table"));
                }
                if update.from.is_some()
                    || update.returning.is_some()
                    || update.limit.is_some()
                    || !update.order_by.is_empty()
                {
                    return Err(wrote("it is more than an UPDATE … SET … WHERE"));
                }
                if update.assignments.is_empty() {
                    return Err(wrote("it sets nothing"));
                }
                for assignment in &update.assignments {
                    let AssignmentTarget::ColumnName(column) = &assignment.target else {
                        return Err(wrote("it sets a tuple"));
                    };
                    if key.contains(&column.to_string()) {
                        return Err(wrote("it changes the primary key"));
                    }
                    if !literal(&assignment.value) {
                        return Err(wrote("a value it sets is not a literal"));
                    }
                }
                let mut named = Vec::new();
                let mut stack = vec![
                    update
                        .selection
                        .as_ref()
                        .ok_or_else(|| wrote("it has no WHERE"))?,
                ];
                while let Some(expr) = stack.pop() {
                    match expr {
                        Expr::BinaryOp {
                            left,
                            op: BinaryOperator::And,
                            right,
                        } => {
                            stack.push(right);
                            stack.push(left);
                        }
                        Expr::BinaryOp {
                            left,
                            op: BinaryOperator::Eq,
                            right,
                        } if literal(right)
                            && !matches!(**right, Expr::Value(ref v) if v.value == Value::Null) =>
                        {
                            let Expr::Identifier(column) = &**left else {
                                return Err(wrote(
                                    "its WHERE compares something other than a column",
                                ));
                            };
                            named.push(column.to_string());
                        }
                        _ => return Err(wrote("its WHERE is not the primary key")),
                    }
                }
                named.sort();
                let mut expected = key.clone();
                expected.sort();
                if named != expected {
                    return Err(wrote("its WHERE does not name exactly the primary key"));
                }
            }
            _ => return Err(wrote("it is not an UPDATE")),
        }
    }
    if updates == 0 {
        return Err(wrote("it updates nothing"));
    }
    let verdict = gate::classify(engine, text);
    if verdict.mode != Mode::ReadWrite || !verdict.destructive.is_empty() {
        return Err(wrote("the gate does not read it as a plain write"));
    }
    Ok(())
}

/// A field this app writes by name: not the key, not a path, not an operator.
fn writable_field(name: &str) -> bool {
    !name.is_empty() && !name.contains('.') && !name.starts_with('$') && name != "_id"
}

/// A JSON value as BSON, refusing what `$set` would not store as typed:
/// a document with an operator key that is not Extended JSON.
fn json_value(text: &str) -> Result<Bson, CoreError> {
    let value: serde_json::Value = json5::from_str(text)
        .map_err(|error| refused(format!("{text} is not a JSON value: {error}")))?;
    let bson = Bson::try_from(value).map_err(|error| refused(error.to_string()))?;
    fn operators(value: &Bson) -> bool {
        match value {
            Bson::Document(document) => document
                .iter()
                .any(|(key, value)| key.starts_with('$') || operators(value)),
            Bson::Array(items) => items.iter().any(operators),
            _ => false,
        }
    }
    if operators(&bson) {
        return Err(refused(
            "A value cannot hold an operator ($…); Extended JSON types such as {\"$date\": …} are read as types.",
        ));
    }
    Ok(bson)
}

fn mongo(target: &EditTarget, row: &RowEdit) -> Result<String, CoreError> {
    let [key] = row.key.as_slice() else {
        return Err(refused("A MongoDB document is named by its _id alone."));
    };
    if key.column != "_id" {
        return Err(refused("A MongoDB document is named by its _id alone."));
    }
    // A string `_id` arrives as itself; anything else as Extended JSON. The
    // type is the result column's, from its first value, so in a collection
    // that mixes them a string read as JSON fails, and is the string.
    let id = if key.type_tag == "string" {
        Bson::String(key.value.clone())
    } else {
        json_value(&key.value).unwrap_or_else(|_| Bson::String(key.value.clone()))
    };
    let mut set = bson::Document::new();
    for change in &row.changes {
        if !writable_field(&change.column) {
            return Err(refused(format!(
                "{} is not a field this app writes: a name with a dot or a leading $ reads as a path.",
                change.column
            )));
        }
        let value = match &change.value {
            NewValue::Text { value } => Bson::String(value.clone()),
            NewValue::Json { value } => json_value(value)?,
            NewValue::Null => Bson::Null,
        };
        set.insert(change.column.clone(), value);
    }
    let json = |value: Bson| value.into_relaxed_extjson().to_string();
    Ok(format!(
        "{}.updateOne({{\"_id\": {}}}, {{\"$set\": {}}})",
        mongo_cmd::collection_ref(&target.table),
        json(id),
        json(Bson::Document(set))
    ))
}

/// The MongoDB half of the write gate: an `updateOne` of the target
/// collection, by `_id` alone, setting fields and nothing else.
fn check_mongo(target: &EditTarget, text: &str) -> Result<(), CoreError> {
    let wrote = |what: &str| {
        refused(format!(
            "DBDelve refused to run a command it wrote itself: {what}. Nothing was sent."
        ))
    };
    let command = mongo_cmd::parse(text).map_err(|_| wrote("it does not parse"))?;
    if command.collection.as_deref() != Some(target.table.as_str()) || command.explain.is_some() {
        return Err(wrote("it updates another collection"));
    }
    let Call::UpdateOne { filter, update } = &command.call else {
        return Err(wrote("it is not an updateOne"));
    };
    if filter.keys().collect::<Vec<_>>() != ["_id"] {
        return Err(wrote("it does not name one document by _id"));
    }
    if update.keys().collect::<Vec<_>>() != ["$set"] {
        return Err(wrote("it does more than $set"));
    }
    if mongo_cmd::classify(text) != Verdict::WRITE {
        return Err(wrote("the gate does not read it as a plain write"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn target(key: &[&str]) -> EditTarget {
        EditTarget {
            schema: "public".into(),
            table: "order\"s".into(),
            key: key.iter().map(|k| (*k).into()).collect(),
        }
    }

    fn row(key: &[(&str, &str)], changes: Vec<CellEdit>) -> RowEdit {
        RowEdit {
            key: key
                .iter()
                .map(|(column, value)| KeyValue {
                    column: (*column).into(),
                    value: (*value).into(),
                    type_tag: String::new(),
                })
                .collect(),
            changes,
        }
    }

    fn text(column: &str, value: &str) -> CellEdit {
        CellEdit {
            column: column.into(),
            value: NewValue::Text {
                value: value.into(),
            },
        }
    }

    #[test]
    fn writes_one_update_per_row_by_the_whole_key() {
        let rows = [
            row(
                &[("id", "1"), ("line", "2")],
                vec![
                    text("note", "O'Brien"),
                    CellEdit {
                        column: "gone".into(),
                        value: NewValue::Null,
                    },
                ],
            ),
            row(&[("id", "3"), ("line", "1")], vec![text("note", "x")]),
        ];
        let pg = prepare(Engine::Postgres, &target(&["id", "line"]), &rows).unwrap();
        assert_eq!(
            pg[0].text,
            "UPDATE \"public\".\"order\"\"s\" SET \"note\" = 'O''Brien', \"gone\" = NULL WHERE \"id\" = '1' AND \"line\" = '2';\n\
             UPDATE \"public\".\"order\"\"s\" SET \"note\" = 'x' WHERE \"id\" = '3' AND \"line\" = '1';"
        );
        assert_eq!(pg[0].verdict, Verdict::WRITE);
        let my = prepare(Engine::MySql, &target(&["id", "line"]), &rows).unwrap();
        assert!(
            my[0]
                .text
                .starts_with("START TRANSACTION;\nUPDATE `public`.`order\"s` SET"),
            "{}",
            my[0].text
        );
        assert!(my[0].text.ends_with("\nCOMMIT;"));
    }

    #[test]
    fn refuses_what_would_not_name_one_row() {
        let edit = [row(&[("id", "1")], vec![text("note", "x")])];
        assert!(
            prepare(Engine::Postgres, &target(&[]), &edit).is_err(),
            "no key"
        );
        assert!(
            prepare(Engine::Postgres, &target(&["id", "line"]), &edit).is_err(),
            "partial key"
        );
        let key_edit = [row(&[("id", "1")], vec![text("id", "2")])];
        assert!(
            prepare(Engine::Postgres, &target(&["id"]), &key_edit).is_err(),
            "changing the key"
        );
        let backslash = [row(&[("id", "1")], vec![text("note", "a\\b")])];
        assert!(prepare(Engine::MySql, &target(&["id"]), &backslash).is_err());
    }

    #[test]
    fn the_write_gate_refuses_anything_but_the_edit() {
        let t = target(&["id"]);
        let good = "UPDATE \"public\".\"order\"\"s\" SET \"a\" = 'x' WHERE \"id\" = '1';";
        assert!(check_sql(Engine::Postgres, &t, good).is_ok());
        for bad in [
            "DELETE FROM \"public\".\"order\"\"s\" WHERE \"id\" = '1';",
            "UPDATE \"public\".\"other\" SET \"a\" = 'x' WHERE \"id\" = '1';",
            "UPDATE \"public\".\"order\"\"s\" SET \"a\" = 'x' WHERE \"id\" = '1' OR 1 = 1;",
            "UPDATE \"public\".\"order\"\"s\" SET \"a\" = 'x';",
            "UPDATE \"public\".\"order\"\"s\" SET \"a\" = now() WHERE \"id\" = '1';",
            "UPDATE \"public\".\"order\"\"s\" SET \"id\" = '2' WHERE \"id\" = '1';",
            "UPDATE \"public\".\"order\"\"s\" SET \"a\" = 'x' WHERE \"id\" = '1'; DROP TABLE t;",
            "UPDATE \"public\".\"order\"\"s\" SET \"a\" = 'x' WHERE \"id\" = '1' RETURNING *;",
        ] {
            assert!(check_sql(Engine::Postgres, &t, bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn mongo_sets_fields_by_id_typed_as_given() {
        let t = EditTarget {
            schema: "dev".into(),
            table: "orders".into(),
            key: vec!["_id".into()],
        };
        let oid = RowEdit {
            key: vec![KeyValue {
                column: "_id".into(),
                value: "{\"$oid\":\"65a1b2c3d4e5f60718293a4b\"}".into(),
                type_tag: "objectid".into(),
            }],
            changes: vec![
                text("status", "paid"),
                CellEdit {
                    column: "total".into(),
                    value: NewValue::Json {
                        value: "12.5".into(),
                    },
                },
                CellEdit {
                    column: "note".into(),
                    value: NewValue::Null,
                },
            ],
        };
        let prepared = prepare(Engine::Mongo, &t, std::slice::from_ref(&oid)).unwrap();
        assert_eq!(
            prepared[0].text,
            "db.orders.updateOne({\"_id\": {\"$oid\":\"65a1b2c3d4e5f60718293a4b\"}}, \
             {\"$set\": {\"status\":\"paid\",\"total\":12.5,\"note\":null}})"
        );
        let mut operator = oid.clone();
        operator.changes = vec![CellEdit {
            column: "x".into(),
            value: NewValue::Json {
                value: "{$gt: 1}".into(),
            },
        }];
        assert!(prepare(Engine::Mongo, &t, &[operator]).is_err());
        let mut path = oid;
        path.changes = vec![text("a.b", "x")];
        assert!(prepare(Engine::Mongo, &t, &[path]).is_err());
    }
}
