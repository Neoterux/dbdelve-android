//! Against real servers. `#[ignore]`d; each reads `DBDELVE_ANDROID_<ENGINE>`
//! as `host:port:user:password:database` and skips when it is unset.

use std::sync::Arc;
use std::time::{Duration, Instant};

use dbdelve_core::{
    ConnectionConfig, CoreError, Destructive, Engine, Mode, ObjectKind, RunOutcome, Session,
    SslMode, Stop, UserAction,
};

fn config(engine: Engine, variable: &str, ssl_mode: SslMode) -> Option<ConnectionConfig> {
    let value = std::env::var(variable).ok()?;
    let parts: Vec<&str> = value.splitn(5, ':').collect();
    let [host, port, user, password, database] = parts[..] else {
        panic!("{variable} is host:port:user:password:database");
    };
    Some(ConnectionConfig {
        engine,
        host: host.into(),
        port: port.parse().unwrap(),
        user: user.into(),
        password: password.into(),
        database: database.into(),
        ssl_mode,
        ca_pem: None,
        client_cert_pem: None,
        client_key_pem: None,
        tls_dir: String::new(),
    })
}

fn run(session: &Session, text: &str) -> RunOutcome {
    session
        .run(text.into(), 2, Vec::new(), false, None)
        .unwrap()
}

fn done(outcome: RunOutcome) -> dbdelve_core::ResultSet {
    match outcome {
        RunOutcome::Done { result } => result,
        RunOutcome::Stopped { stop, .. } => panic!("stopped: {stop:?}"),
    }
}

/// Read-only refuses a write before sending it; Read-write runs it; the row
/// limit keeps two rows and says it capped; a drop asks first.
fn exercise(session: &Session, create: &str, insert: &str, select: &str, drop: &str) {
    assert!(matches!(
        run(session, create),
        RunOutcome::Stopped {
            stop: Stop::Upgrade {
                mode: Mode::ReadWrite
            },
            ..
        }
    ));
    session.set_mode(Mode::ReadWrite).unwrap();
    // Left over from a run that failed half-way.
    let _ = session.run(
        drop.into(),
        2,
        vec![Destructive::Drop],
        false,
        Some(Mode::Full),
    );
    done(run(session, create));
    done(run(session, insert));
    let result = done(run(session, select));
    assert_eq!(result.rows.len(), 2);
    assert!(result.capped, "three rows past a limit of two");
    assert!(matches!(
        run(session, drop),
        RunOutcome::Stopped {
            stop: Stop::Upgrade { mode: Mode::Full },
            ..
        }
    ));
    let confirmed = session
        .run(drop.into(), 2, Vec::new(), false, Some(Mode::Full))
        .unwrap();
    assert!(matches!(
        confirmed,
        RunOutcome::Stopped {
            stop: Stop::Confirm {
                kind: Destructive::Drop
            },
            ..
        }
    ));
    done(
        session
            .run(
                drop.into(),
                2,
                vec![Destructive::Drop],
                false,
                Some(Mode::Full),
            )
            .unwrap(),
    );
    assert_eq!(
        session.mode(),
        Mode::ReadWrite,
        "a run once leaves the mode where it was"
    );
}

/// Read-only on two layers. The gate refuses a write it cannot read before
/// anything is sent; and the server's hold refuses what the gate reads as
/// a plain read but writes underneath, a function call.
fn held_by_server(session: &Session, setup: &[&str], unreadable_write: &str, writes_inside: &str) {
    session.set_mode(Mode::ReadWrite).unwrap();
    for statement in setup {
        let ran = session
            .run(
                (*statement).into(),
                2,
                vec![Destructive::Drop],
                true,
                Some(Mode::Full),
            )
            .unwrap();
        assert!(
            matches!(ran, RunOutcome::Done { .. }),
            "{statement}: {ran:?}"
        );
    }
    session.set_mode(Mode::ReadOnly).unwrap();

    assert!(
        matches!(
            session
                .run(unreadable_write.into(), 2, vec![], true, None)
                .unwrap(),
            RunOutcome::Stopped {
                stop: Stop::Upgrade {
                    mode: Mode::ReadWrite
                },
                ..
            }
        ),
        "refused before it is sent"
    );

    assert_eq!(
        dbdelve_core::classify(session.engine(), writes_inside.into()).mode,
        Mode::ReadOnly
    );
    match session.run(writes_inside.into(), 2, vec![], false, None) {
        Err(CoreError::Query { reason }) => {
            let reason = reason.to_ascii_lowercase();
            assert!(
                reason.contains("read-only") || reason.contains("read only"),
                "{reason}"
            );
        }
        other => panic!("wrote in Read-only: {other:?}"),
    }
}

#[test]
#[ignore]
fn live_postgres() {
    let Some(config) = config(
        Engine::Postgres,
        "DBDELVE_ANDROID_POSTGRES",
        SslMode::Disable,
    ) else {
        return;
    };
    let session = Session::connect(config, Mode::ReadOnly).unwrap();
    exercise(
        &session,
        "CREATE TABLE android_live (id int PRIMARY KEY, label text)",
        "INSERT INTO android_live VALUES (1, 'a'), (2, NULL), (3, 'é')",
        "SELECT * FROM android_live ORDER BY id",
        "DROP TABLE android_live",
    );

    held_by_server(
        &session,
        &[
            "DROP TABLE IF EXISTS android_held",
            "CREATE TABLE android_held (a int)",
            "CREATE OR REPLACE FUNCTION android_writes() RETURNS int LANGUAGE sql \
             AS 'INSERT INTO android_held VALUES (1) RETURNING 1'",
        ],
        "INSERT INTO android_held OVERRIDING SYSTEM VALUE VALUES (1)",
        "SELECT android_writes()",
    );

    let session = Arc::new(session);
    let cancelling = Arc::clone(&session);
    let started = Instant::now();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(500));
        cancelling.cancel();
    });
    let slept = session.run("SELECT pg_sleep(30)".into(), 10, vec![], false, None);
    assert!(matches!(slept, Err(CoreError::Query { .. })), "{slept:?}");
    assert!(started.elapsed() < Duration::from_secs(10));
}

#[test]
#[ignore]
fn live_mysql() {
    let Some(config) = config(Engine::MySql, "DBDELVE_ANDROID_MYSQL", SslMode::Disable) else {
        return;
    };
    let session = Session::connect(config.clone(), Mode::ReadOnly).unwrap();
    exercise(
        &session,
        "CREATE TABLE android_live (id int PRIMARY KEY, label varchar(10))",
        "INSERT INTO android_live VALUES (1, 'a'), (2, NULL), (3, 'é')",
        "SELECT * FROM android_live ORDER BY id",
        "DROP TABLE android_live",
    );
    let result = done(run(&session, "SELECT 1 AS one, NULL AS nothing"));
    assert_eq!(result.rows, vec![vec![Some("1".into()), None]]);
    assert_eq!(result.columns[0].type_tag, "LONGLONG");

    held_by_server(
        &session,
        &[
            "DROP TABLE IF EXISTS android_held",
            "CREATE TABLE android_held (a int)",
            "DROP FUNCTION IF EXISTS android_writes",
            "CREATE FUNCTION android_writes() RETURNS INT MODIFIES SQL DATA \
             BEGIN INSERT INTO android_held VALUES (1); RETURN 1; END",
        ],
        "UPDATE LOW_PRIORITY IGNORE android_held SET a = 3 ORDER BY a LIMIT 1",
        "SELECT android_writes()",
    );

    let session = Arc::new(session);
    let cancelling = Arc::clone(&session);
    let started = Instant::now();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(500));
        cancelling.cancel();
    });
    let _ = session.run("SELECT SLEEP(30)".into(), 10, vec![], false, None);
    assert!(
        started.elapsed() < Duration::from_secs(10),
        "KILL QUERY stopped the sleep"
    );

    let tables = session.catalog().unwrap();
    assert!(
        tables
            .iter()
            .all(|entry| entry.kind != ObjectKind::Collection)
    );
}

#[test]
#[ignore]
fn live_mysql_tls() {
    let Some(config) = config(Engine::MySql, "DBDELVE_ANDROID_MYSQL", SslMode::VerifyFull) else {
        return;
    };
    // A dev server's self-signed certificate, or a 5.6 that offers only TLS
    // 1.0: either way refused, and never retried in plaintext.
    let refused = Session::connect(config, Mode::ReadOnly)
        .err()
        .expect("refused");
    println!("{refused}");
}

#[test]
#[ignore]
fn live_mongo() {
    let Some(config) = config(Engine::Mongo, "DBDELVE_ANDROID_MONGO", SslMode::Disable) else {
        return;
    };
    let session = Session::connect(config, Mode::ReadOnly).unwrap();
    exercise(
        &session,
        "db.android_live.insertOne({_id: 0, label: 'seed'})",
        "db.android_live.insertMany([{_id: 1, label: 'a'}, {_id: 2, label: null}, {_id: 3}])",
        "db.android_live.find({_id: {$gt: 0}})",
        "db.android_live.drop()",
    );
    assert!(matches!(
        run(&session, "db.android_live.deleteMany({})"),
        RunOutcome::Stopped {
            stop: Stop::Upgrade { mode: Mode::Full },
            ..
        }
    ));
    let preview = session.preview("dev".into(), "odd.name".into(), 10);
    assert_eq!(preview, r#"db.getCollection("odd.name").find({})"#);
}

/// Creates a user with a password hashed on this side, logs in as it, grants
/// it something the server then reports, and drops it: the whole loop, on
/// the server's own terms.
fn manage_users(
    admin: &Session,
    mut login: ConnectionConfig,
    grant: UserAction,
    expect_grant: &str,
    revoke: UserAction,
) {
    use dbdelve_core::{Account, UserAction};
    let account = Account {
        name: "droid_live".into(),
        host: String::new(),
    };
    let password = "pa ss'w\"rd";
    let run = |action: UserAction, confirmed: Vec<Destructive>| {
        let prepared = admin.prepare_user(action).unwrap();
        admin
            .run_prepared(prepared, confirmed, Some(Mode::Full))
            .unwrap()
    };
    // Left over from a run that failed half-way; absent is fine.
    if let Ok(prepared) = admin.prepare_user(UserAction::Drop {
        account: account.clone(),
    }) {
        let _ = admin.run_prepared(prepared, vec![Destructive::Drop], Some(Mode::Full));
    }

    // Read-only refuses it before anything is sent.
    let prepared = admin
        .prepare_user(UserAction::Create {
            account: account.clone(),
            password: password.into(),
            can_login: true,
        })
        .unwrap();
    assert!(matches!(
        admin.run_prepared(prepared, vec![], None).unwrap(),
        RunOutcome::Stopped {
            stop: Stop::Upgrade { mode: Mode::Full },
            ..
        }
    ));
    done(run(
        UserAction::Create {
            account: account.clone(),
            password: password.into(),
            can_login: true,
        },
        vec![],
    ));

    login.user = account.name.clone();
    login.password = password.into();
    // Authentication is the point; on MySQL the new user may not open the
    // database yet. Postgres would default to one named after the user.
    if login.engine == Engine::MySql {
        login.database = String::new();
    }
    let session = Session::connect(login.clone(), Mode::ReadOnly).expect("logs in as the new user");
    drop(session);

    done(run(grant, vec![]));
    let grants = admin.user_grants(account.clone()).unwrap();
    assert!(
        grants.iter().any(|line| line.contains(expect_grant)),
        "{grants:?}"
    );
    assert!(
        grants.iter().all(|line| !line.contains("PASSWORD")),
        "no hash shown: {grants:?}"
    );
    assert!(
        admin
            .users()
            .unwrap()
            .iter()
            .any(|user| user.account.name == account.name && user.can_login && !user.superuser)
    );
    done(run(revoke, vec![]));
    let left = admin.user_grants(account.clone()).unwrap();
    assert!(
        left.iter().all(|line| !line.contains(expect_grant)),
        "{left:?}"
    );

    // Dropping asks, and then runs.
    let prepared = admin
        .prepare_user(UserAction::Drop {
            account: account.clone(),
        })
        .unwrap();
    assert!(matches!(
        admin
            .run_prepared(prepared, vec![], Some(Mode::Full))
            .unwrap(),
        RunOutcome::Stopped {
            stop: Stop::Confirm {
                kind: Destructive::Drop
            },
            ..
        }
    ));
    done(run(
        UserAction::Drop {
            account: account.clone(),
        },
        vec![Destructive::Drop],
    ));
    assert!(Session::connect(login, Mode::ReadOnly).is_err(), "gone");
    assert_eq!(
        admin.mode(),
        Mode::ReadOnly,
        "a run once leaves the mode where it was"
    );
}

#[test]
#[ignore]
fn live_users_postgres() {
    use dbdelve_core::{Account, Scope, UserAction};
    let Some(config) = config(
        Engine::Postgres,
        "DBDELVE_ANDROID_POSTGRES",
        SslMode::Disable,
    ) else {
        return;
    };
    let admin = Session::connect(config.clone(), Mode::ReadOnly).unwrap();
    let me = admin.users().unwrap();
    assert!(
        me.iter()
            .any(|user| user.account.name == "postgres" && user.superuser && user.can_login),
        "{me:?}"
    );
    admin.set_mode(Mode::ReadWrite).unwrap();
    let _ = admin.run(
        "CREATE TABLE IF NOT EXISTS android_granted (a int)".into(),
        1,
        vec![],
        false,
        None,
    );
    admin.set_mode(Mode::ReadOnly).unwrap();
    manage_users(
        &admin,
        config,
        UserAction::Grant {
            account: Account {
                name: "droid_live".into(),
                host: String::new(),
            },
            privileges: vec!["SELECT".into(), "INSERT".into()],
            scope: Scope::Schema {
                name: "public".into(),
            },
        },
        "public.android_granted: INSERT, SELECT",
        UserAction::Revoke {
            account: Account {
                name: "droid_live".into(),
                host: String::new(),
            },
            privileges: vec!["SELECT".into(), "INSERT".into()],
            scope: Scope::Schema {
                name: "public".into(),
            },
        },
    );
}

#[test]
#[ignore]
fn live_users_mysql() {
    use dbdelve_core::{Account, Scope, UserAction};
    let Some(config) = config(Engine::MySql, "DBDELVE_ANDROID_MYSQL", SslMode::Disable) else {
        return;
    };
    let admin = Session::connect(config.clone(), Mode::ReadOnly).unwrap();
    manage_users(
        &admin,
        config,
        UserAction::Grant {
            account: Account {
                name: "droid_live".into(),
                host: String::new(),
            },
            privileges: vec!["SELECT".into(), "SHOW VIEW".into()],
            scope: Scope::Schema { name: "dev".into() },
        },
        "GRANT SELECT, SHOW VIEW ON `dev`.* TO 'droid_live'@'%'",
        UserAction::Revoke {
            account: Account {
                name: "droid_live".into(),
                host: String::new(),
            },
            privileges: vec!["SELECT".into(), "SHOW VIEW".into()],
            scope: Scope::Schema { name: "dev".into() },
        },
    );
}

#[test]
#[ignore]
fn live_users_mongo() {
    use dbdelve_core::{Account, UserAction};
    let Some(config) = config(Engine::Mongo, "DBDELVE_ANDROID_MONGO", SslMode::Disable) else {
        return;
    };
    let admin = Session::connect(config.clone(), Mode::ReadOnly).unwrap();
    manage_users(
        &admin,
        config,
        UserAction::GrantRole {
            account: Account {
                name: "droid_live".into(),
                host: String::new(),
            },
            role: "read".into(),
            role_database: String::new(),
        },
        "dev: ",
        UserAction::RevokeRole {
            account: Account {
                name: "droid_live".into(),
                host: String::new(),
            },
            role: "read".into(),
            role_database: String::new(),
        },
    );
}

fn timed(session: &Session, text: &str, limit: u32) -> (dbdelve_core::ResultSet, Duration) {
    let started = Instant::now();
    let result = done(
        session
            .run(text.into(), limit, vec![], false, None)
            .unwrap(),
    );
    (result, started.elapsed())
}

#[test]
#[ignore]
fn live_postgres_stops_at_the_limit() {
    let Some(config) = config(
        Engine::Postgres,
        "DBDELVE_ANDROID_POSTGRES",
        SslMode::Disable,
    ) else {
        return;
    };
    let session = Session::connect(config, Mode::ReadOnly).unwrap();
    // Twenty million rows: drained, this takes many seconds.
    let (result, took) = timed(&session, "SELECT g FROM generate_series(1, 20000000) g", 10);
    assert_eq!(result.rows.len(), 10);
    assert!(result.capped);
    assert!(
        took < Duration::from_secs(4),
        "stopped, not drained: {took:?}"
    );
    done(run(&session, "SELECT 1"));

    // Inside a transaction the user opened, a cancel would abort all of it:
    // the read is drained instead, and the transaction carries on.
    done(run(&session, "BEGIN"));
    let (result, _) = timed(&session, "SELECT g FROM generate_series(1, 300000) g", 10);
    assert!(result.capped);
    let after = session.run("SELECT 1".into(), 10, vec![], false, None);
    assert!(
        matches!(after, Ok(RunOutcome::Done { .. })),
        "still usable: {after:?}"
    );
    done(run(&session, "ROLLBACK"));
}

#[test]
#[ignore]
fn live_mysql_stops_at_the_limit() {
    let Some(config) = config(Engine::MySql, "DBDELVE_ANDROID_MYSQL", SslMode::Disable) else {
        return;
    };
    let session = Session::connect(config, Mode::ReadOnly).unwrap();
    // A cross join of the catalog with itself, twice: millions of rows.
    let (result, took) = timed(
        &session,
        "SELECT a.ORDINAL_POSITION FROM information_schema.COLUMNS a, \
         information_schema.COLUMNS b, information_schema.COLUMNS c",
        10,
    );
    assert_eq!(result.rows.len(), 10);
    assert!(result.capped);
    assert!(
        took < Duration::from_secs(8),
        "killed, not drained: {took:?}"
    );
    let after = done(run(&session, "SELECT 1"));
    assert_eq!(after.rows, vec![vec![Some("1".into())]]);
}

/// TLS past the public roots. `DBDELVE_ANDROID_TLS_CERTS` names a directory
/// holding `ca.pem` (a private CA), `client.pem` and `client.key` (a client
/// certificate it signed, for the user `tlsuser`). Each server variable is
/// `host:port:user:password:database`, and its server presents a
/// certificate that CA signed for the host, except `…_SELF_SIGNED`, whose
/// server presents `self.pem`, a self-signed certificate, from that directory.
fn tls_case(engine: Engine, variable: &str) -> Option<(ConnectionConfig, std::path::PathBuf)> {
    // A directory per server: the tests run at once, and each counts its own.
    let certs = std::path::PathBuf::from(std::env::var("DBDELVE_ANDROID_TLS_CERTS").ok()?);
    let mut config = config(engine, variable, SslMode::VerifyFull)?;
    config.tls_dir = std::env::temp_dir()
        .join(format!("dbdelve-tls-{}-{variable}", std::process::id()))
        .to_string_lossy()
        .into_owned();
    Some((config, certs))
}

fn read(path: &std::path::Path) -> Option<String> {
    Some(std::fs::read_to_string(path).unwrap())
}

fn connects(config: &ConnectionConfig) -> Result<Arc<Session>, CoreError> {
    Session::connect(config.clone(), Mode::ReadOnly)
}

/// Public roots alone refuse the private CA's certificate; with the CA it
/// connects, and the files written for the driver are gone once it closes.
fn trusts_only_with_the_ca(config: ConnectionConfig, ca: &std::path::Path) {
    let refused = connects(&config)
        .err()
        .expect("the private CA is not a public root");
    assert!(matches!(refused, CoreError::Connect { .. }), "{refused:?}");
    let with_ca = ConnectionConfig {
        ca_pem: read(ca),
        ..config.clone()
    };
    let session = connects(&with_ca).unwrap();
    done(run(
        &session,
        if config.engine == Engine::Mongo {
            "db.t.find({})"
        } else {
            "SELECT 1"
        },
    ));
    drop(session);
    let left = std::fs::read_dir(&config.tls_dir)
        .map(|dir| dir.count())
        .unwrap_or(0);
    assert_eq!(left, 0, "TLS files outlived their session");
}

/// A user the server lets in only by certificate: refused without one, in
/// with the CA's client certificate.
fn client_certificate(config: ConnectionConfig, certs: &std::path::Path) {
    let with_ca = ConnectionConfig {
        user: "tlsuser".into(),
        password: String::new(),
        ca_pem: read(&certs.join("ca.pem")),
        ..config
    };
    assert!(connects(&with_ca).is_err(), "no client certificate");
    let with_cert = ConnectionConfig {
        client_cert_pem: read(&certs.join("client.pem")),
        client_key_pem: read(&certs.join("client.key")),
        ..with_ca
    };
    let session = connects(&with_cert).unwrap();
    done(run(&session, "SELECT current_user"));
}

#[test]
#[ignore]
fn live_postgres_tls_private_ca() {
    let Some((config, certs)) = tls_case(Engine::Postgres, "DBDELVE_ANDROID_POSTGRES_TLS") else {
        return;
    };
    trusts_only_with_the_ca(config.clone(), &certs.join("ca.pem"));
    client_certificate(config, &certs);
}

#[test]
#[ignore]
fn live_postgres_tls_self_signed() {
    let Some((config, certs)) = tls_case(Engine::Postgres, "DBDELVE_ANDROID_POSTGRES_SELF_SIGNED")
    else {
        return;
    };
    trusts_only_with_the_ca(config, &certs.join("self.pem"));
}

#[test]
#[ignore]
fn live_mysql_tls_private_ca() {
    let Some((config, certs)) = tls_case(Engine::MySql, "DBDELVE_ANDROID_MYSQL_TLS") else {
        return;
    };
    trusts_only_with_the_ca(config.clone(), &certs.join("ca.pem"));
    client_certificate(config, &certs);
}

#[test]
#[ignore]
fn live_mongo_tls_private_ca() {
    let Some((config, certs)) = tls_case(Engine::Mongo, "DBDELVE_ANDROID_MONGO_TLS") else {
        return;
    };
    trusts_only_with_the_ca(config, &certs.join("ca.pem"));
}

/// Runs a statement in Full, whatever it is: test setup, not the gate's test.
fn setup(session: &Session, text: &str) {
    let outcome = session
        .run(
            text.into(),
            1000,
            vec![
                Destructive::Drop,
                Destructive::UnfilteredDelete,
                Destructive::Truncate,
            ],
            true,
            Some(Mode::Full),
        )
        .unwrap();
    assert!(
        matches!(outcome, RunOutcome::Done { .. }),
        "{text}: {outcome:?}"
    );
}

/// The builder's statement, filled with `values` (a text, or a number when it
/// reads as one) in parameter order, and run.
fn built(
    session: &Session,
    spec: &dbdelve_core::QuerySpec,
    values: &[&str],
) -> dbdelve_core::ResultSet {
    use dbdelve_core::{Binding, ParamValue};
    let engine = session.engine();
    let query = dbdelve_core::build_query(engine, spec.clone());
    let names: Vec<String> = query.parameters.iter().flatten().cloned().collect();
    assert_eq!(names.len(), values.len(), "{}", query.text);
    let bindings = names
        .into_iter()
        .zip(values)
        .map(|(name, value)| Binding {
            name,
            value: if value.parse::<f64>().is_ok() {
                ParamValue::Number {
                    value: (*value).into(),
                }
            } else {
                ParamValue::Text {
                    value: (*value).into(),
                }
            },
        })
        .collect();
    let filled = dbdelve_core::fill_parameters(engine, query.text, bindings).unwrap();
    match session
        .run(filled.clone(), 1000, Vec::new(), false, None)
        .unwrap()
    {
        RunOutcome::Done { result } => result,
        stopped => panic!("{filled}: {stopped:?}"),
    }
}

/// IN, BETWEEN, a case-insensitive match and OR groups, a count, and a count
/// per group, run on the server; and on MongoDB, a field only one document
/// in a hundred holds still shows among the builder's columns.
fn builder_runs(session: &Session, schema: &str, table: &str, starts_with_an: &str) {
    use dbdelve_core::{Filter, FilterOp, QuerySpec};
    let filter = |column: &str, op, values| Filter {
        column: column.into(),
        op,
        values,
    };
    let base = QuerySpec {
        schema: schema.into(),
        table: table.into(),
        columns: vec!["id".into()],
        groups: Vec::new(),
        count: false,
        group_by: Vec::new(),
        order_by: Some("id".into()),
        descending: false,
        limit: None,
    };
    let either = QuerySpec {
        groups: vec![
            vec![
                filter("status", FilterOp::In, 2),
                filter("total", FilterOp::Between, 0),
            ],
            vec![filter("name", FilterOp::LikeIgnoringCase, 0)],
        ],
        ..base.clone()
    };
    let ids: Vec<String> = built(
        session,
        &either,
        &["paid", "shipped", "15", "45", starts_with_an],
    )
    .rows
    .into_iter()
    .map(|row| row[0].clone().unwrap())
    .collect();
    assert_eq!(ids, ["1", "2", "3", "4"]);

    let per_status = QuerySpec {
        count: true,
        group_by: vec!["status".into()],
        order_by: Some("status".into()),
        ..base.clone()
    };
    let result = built(session, &per_status, &[]);
    let names: Vec<&str> = result.columns.iter().map(|c| c.name.as_str()).collect();
    assert_eq!(names, ["status", "count"]);
    let rows: Vec<(String, String)> = result
        .rows
        .into_iter()
        .map(|row| (row[0].clone().unwrap(), row[1].clone().unwrap()))
        .collect();
    let expected = [("open", "2"), ("paid", "2"), ("shipped", "1")];
    assert_eq!(rows, expected.map(|(a, b)| (a.to_owned(), b.to_owned())));

    let all = QuerySpec {
        count: true,
        order_by: None,
        ..base
    };
    assert_eq!(built(session, &all, &[]).rows, [[Some("5".to_owned())]]);
}

#[test]
#[ignore]
fn live_builder_postgres() {
    let Some(config) = config(
        Engine::Postgres,
        "DBDELVE_ANDROID_POSTGRES",
        SslMode::Disable,
    ) else {
        return;
    };
    let session = Session::connect(config, Mode::ReadOnly).unwrap();
    setup(&session, "DROP TABLE IF EXISTS android_built");
    setup(
        &session,
        "CREATE TABLE android_built (id int PRIMARY KEY, status text, total numeric, name text)",
    );
    setup(
        &session,
        "INSERT INTO android_built VALUES (1, 'paid', 10, 'Ana'), (2, 'open', 20, 'ana'), \
         (3, 'paid', 30, 'Bob'), (4, 'shipped', 40, 'Cy'), (5, 'open', 50, 'Dee')",
    );
    builder_runs(&session, "public", "android_built", "an%");
    setup(&session, "DROP TABLE android_built");
}

#[test]
#[ignore]
fn live_builder_mysql() {
    let Some(config) = config(Engine::MySql, "DBDELVE_ANDROID_MYSQL", SslMode::Disable) else {
        return;
    };
    let database = config.database.clone();
    let session = Session::connect(config, Mode::ReadOnly).unwrap();
    setup(&session, "DROP TABLE IF EXISTS android_built");
    // A binary collation, which does not ignore case by itself.
    setup(
        &session,
        "CREATE TABLE android_built (id int PRIMARY KEY, status varchar(20), total decimal(10,2), \
         name varchar(20) CHARACTER SET utf8 COLLATE utf8_bin)",
    );
    setup(
        &session,
        "INSERT INTO android_built VALUES (1, 'paid', 10, 'Ana'), (2, 'open', 20, 'ana'), \
         (3, 'paid', 30, 'Bob'), (4, 'shipped', 40, 'Cy'), (5, 'open', 50, 'Dee')",
    );
    builder_runs(&session, &database, "android_built", "an%");
    setup(&session, "DROP TABLE android_built");
}

#[test]
#[ignore]
fn live_builder_mongo() {
    let Some(config) = config(Engine::Mongo, "DBDELVE_ANDROID_MONGO", SslMode::Disable) else {
        return;
    };
    let database = config.database.clone();
    let session = Session::connect(config, Mode::ReadOnly).unwrap();
    setup(&session, "db.android_built.drop()");
    setup(
        &session,
        "db.android_built.insertMany([{_id: 1, id: 1, status: 'paid', total: 10, name: 'Ana'}, \
         {_id: 2, id: 2, status: 'open', total: 20, name: 'ana'}, {_id: 3, id: 3, status: 'paid', total: 30, name: 'Bob'}, \
         {_id: 4, id: 4, status: 'shipped', total: 40, name: 'Cy'}, {_id: 5, id: 5, status: 'open', total: 50, name: 'Dee'}])",
    );
    builder_runs(&session, &database, "android_built", "^an");

    // One document in a hundred has `rare`; one document never was enough.
    setup(&session, "db.android_sampled.drop()");
    let many: Vec<String> = (0..99).map(|n| format!("{{n: {n}}}")).collect();
    setup(
        &session,
        &format!("db.android_sampled.insertMany([{}])", many.join(", ")),
    );
    setup(
        &session,
        "db.android_sampled.insertOne({n: 99, rare: true})",
    );
    let columns: Vec<String> = session
        .columns(database, "android_sampled".into())
        .unwrap()
        .into_iter()
        .map(|column| column.name)
        .collect();
    assert_eq!(columns, ["_id", "n", "rare"]);
    setup(&session, "db.android_sampled.drop()");
    setup(&session, "db.android_built.drop()");
}
