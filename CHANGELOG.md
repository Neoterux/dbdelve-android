# Changelog

Every user-visible change, newest first. The app shows this file under
**What's new**, so write each line for someone using it, not for someone
reading the code. Add to *Unreleased* with each change; a release renames it
to its version and date.

## 0.5.0 (2026-11-19)

### Added

- TLS with your own CA: under Verified, add a CA certificate to trust besides
  the public ones, so a server with an internal or self-signed certificate
  no longer pushes you to TLS Off. On Postgres a self-signed server
  certificate works as its own CA. Errors now say why a certificate was
  refused and what to add.
- Client certificates, for servers that admit users by certificate. The key is
  sealed by the phone's Keystore, like passwords.
- Editing rows. In the rows of a query the builder wrote, or of a table's
  preview, tap a row and the pencil beside a field to change it or set it
  to NULL, then Review: the exact UPDATE statements, each naming its row by
  the whole primary key, run only after you read them and only in
  Read-write (run once or switched), like anything typed. The app checks
  its own statements before showing them and refuses anything but that
  UPDATE.
- History for each connection: every statement sent, exactly as sent, with
  when it ran and its rows, rows affected or error. Tap one to open it in a
  tab; search it, copy from it, or clear it.
- Explain, under the editor: the plan of the statement under the cursor,
  or the plan with timings, which runs it (and asks first, as the statement
  would, if it writes). Your text is left as it is. MongoDB plans show one
  row per stage, with the index used and the documents examined.
- Completion in the editor: the columns of the tables you name, and table
  or collection names, above the key row as you type; after `alias.`, that
  table's columns. A tap puts the name in, quoted where it must be.
- The query builder's filters gain like ignoring case (ILIKE on Postgres),
  in and between, and groups joined by OR; and it can count rows, or count
  them per group.

### Changed

- A running statement keeps running when you leave the app with Back or
  swipe it away from recent apps, and its result is there when you come
  back. Leaving closes the connection once nothing is running on it.
- The notice of a newer version shows what's new in it, from the release's
  own notes, instead of sending you to the browser.
- Saved queries and Save this query share one bookmark menu under the editor,
  making room for History and Explain.
- On MongoDB the query builder's columns come from a sample of 100 documents,
  so fields only some documents hold are there too.

## 0.4.0 (2026-11-19)

### Added

- A query builder beside the SQL editor: choose a table, tap the columns,
  add filters (=, ≠, <, ≤, >, ≥, like, is null, not null), a sort and a
  limit, and see the exact statement before it runs. Run it, open it as SQL
  in a tab, or save it as a saved query whose filter values are asked for at
  each run. From the schema, **Build a query** starts it on that table.
- A tab switcher: the tab count beside the editor opens every query tab as a
  card, with its text and whether it ran, returned an error or hasn't run.

### Changed

- Query tabs are chips that show whether each one ran or failed, and the
  open one stays in view.
- Icons throughout: the bottom bar, the editor's save, saved queries and row
  limit, Grid and Cards in results, copy and share, the kind of each schema
  object, and every action sheet. The bottom bar's Results shows the row
  count, a dot while a statement runs, and ! after an error.
- Cancel is now Stop.
- Each connection shows its engine's logo (PostgreSQL, MySQL, MongoDB), in
  the list and beside its name once connected.

## 0.3.0 (2026-11-19)

### Added

- Tells you when a newer version is out on the Releases page, with a link to
  download it. It checks at most twice a day; **Check for updates** in the
  menu asks right away, and **Check automatically** turns the checks off.

## 0.2.0 (2026-11-19)

### Changed

- Runs on Android 12, 13 and 14 as well as Android 15.

## 0.1.0 (2026-11-19)

The first public build.

### Added

- Connections to PostgreSQL, MySQL 5.6.5 and later, and MongoDB, grouped by
  project, with passwords kept in the Android Keystore.
- Three connection modes: Read-only, Read-write and Full. Every statement is
  checked against the mode before it is sent; going above it asks first, and
  Full asks for the phone's own lock.
- A query editor with syntax colours, query tabs that survive the app being
  closed, and a key row for the symbols a phone keyboard hides.
- Saved queries per connection, with `:name` parameters: running one asks for
  each value (text, number or NULL) and shows the statement it makes first.
- Results as a grid or as one card per row; tap a row for every field, and
  share the results as a CSV file to any app.
- Schema browsing with table sizes and a row preview.
- Users and roles: list them and what they may do; create, change passwords,
  grant, revoke and drop, each shown as the exact statement before it runs.
  Postgres and MySQL passwords are hashed on the phone.
- Swipe between Query, Results, Schema and Users; pull down to refresh;
  actions in sheets within reach of a thumb.
- A read past the row limit is stopped, not downloaded in full.
- Share connections as an encrypted `.dbdelve` file (AES-256-GCM under a
  passphrase), with or without their passwords, through any app.
- Import such a file: after the passphrase, review every connection and change
  its name, host, port, user, database, project, TLS and mode, choose whether
  to keep its password, and decide per connection whether a name already here
  is kept beside it, replaced or skipped. Imported connections start
  Read-only.

### Security

- A statement whose meaning depends on server settings the app cannot see
  (any backslash), and any statement the app cannot read, needs Read-write
  and asks before it runs.
- Server-wide settings (`SET GLOBAL`) need Full.
- TLS certificate checks use patched libraries (RUSTSEC-2026-0381,
  RUSTSEC-2026-0382).
