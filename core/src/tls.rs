//! TLS beyond the public roots: a CA of your own (or a server's self-signed
//! certificate) to trust, and a client certificate to present.
//!
//! Verification never weakens: the server's certificate is still checked
//! against a root and the host name, only the set of roots grows. There is no
//! "accept any certificate" here, on purpose: that is Off with extra steps.
//!
//! Postgres takes the material in memory. MySQL's and MongoDB's drivers read
//! it only from files, so for them it is written into the app-private
//! directory the app names, for as long as the session lives, and deleted
//! when it closes.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use rustls::client::WebPkiServerVerifier;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};

use crate::{ConnectionConfig, CoreError, SslMode};

fn refused(reason: impl Into<String>) -> CoreError {
    CoreError::Connect {
        reason: reason.into(),
    }
}

/// The certificates in a PEM text, or why there are none.
fn certificates(pem: &str, what: &str) -> Result<Vec<CertificateDer<'static>>, CoreError> {
    let found = CertificateDer::pem_slice_iter(pem.as_bytes())
        .collect::<Result<Vec<_>, _>>()
        .map_err(|error| refused(format!("The {what} does not read as PEM: {error}.")))?;
    if found.is_empty() {
        return Err(refused(format!(
            "The {what} holds no certificate (a PEM block starting \
             -----BEGIN CERTIFICATE-----)."
        )));
    }
    Ok(found)
}

fn private_key(pem: &str) -> Result<PrivateKeyDer<'static>, CoreError> {
    PrivateKeyDer::from_pem_slice(pem.as_bytes()).map_err(|_| {
        refused(
            "The client key does not read as an unencrypted PEM private key \
             (PKCS #8, PKCS #1 or SEC1). A key with a passphrase must be \
             decrypted first, e.g. `openssl pkey -in key.pem -out plain.pem`.",
        )
    })
}

/// What the connection's TLS settings hold, checked: every PEM parses, a
/// certificate comes with its key, and none of it is set with TLS off.
pub(crate) struct Material {
    pub ca: Option<Vec<CertificateDer<'static>>>,
    pub client: Option<(Vec<CertificateDer<'static>>, PrivateKeyDer<'static>)>,
}

/// A PEM setting, unset when it holds only whitespace.
fn blank(pem: &Option<String>) -> Option<&str> {
    pem.as_deref().map(str::trim).filter(|p| !p.is_empty())
}

pub(crate) fn material(config: &ConnectionConfig) -> Result<Material, CoreError> {
    let (ca, cert, key) = (
        blank(&config.ca_pem),
        blank(&config.client_cert_pem),
        blank(&config.client_key_pem),
    );
    if config.ssl_mode == SslMode::Disable {
        if ca.is_some() || cert.is_some() {
            return Err(refused(
                "A CA or client certificate is set, but TLS is off. Turn TLS on, \
                 or remove them.",
            ));
        }
        return Ok(Material {
            ca: None,
            client: None,
        });
    }
    let client = match (cert, key) {
        (None, None) => None,
        (Some(cert), Some(key)) => {
            Some((certificates(cert, "client certificate")?, private_key(key)?))
        }
        (Some(_), None) => return Err(refused("The client certificate has no private key.")),
        (None, Some(_)) => return Err(refused("A client key is set without its certificate.")),
    };
    Ok(Material {
        ca: ca
            .map(|ca| certificates(ca, "CA certificate"))
            .transpose()?,
        client,
    })
}

/// The Postgres client's TLS: Mozilla's roots, plus the CA when one is set,
/// presenting the client certificate when one is set.
pub(crate) fn client_config(material: &Material) -> Result<rustls::ClientConfig, CoreError> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    for ca in material.ca.iter().flatten() {
        roots
            .add(ca.clone())
            .map_err(|error| refused(format!("The CA certificate cannot be a root: {error}.")))?;
    }
    let verifier = Pinned {
        inner: WebPkiServerVerifier::builder_with_provider(Arc::new(roots), provider.clone())
            .build()
            .map_err(|error| refused(format!("TLS: {error}")))?,
        pinned: material.ca.clone().unwrap_or_default(),
    };
    let builder = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .expect("ring supports the default protocol versions")
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(verifier));
    match &material.client {
        None => Ok(builder.with_no_client_auth()),
        Some((chain, key)) => builder
            .with_client_auth_cert(chain.clone(), key.clone_key())
            .map_err(|error| {
                refused(format!(
                    "The client certificate and key do not go together: {error}."
                ))
            }),
    }
}

/// Mozilla's roots and the CA, as ever; and besides, a server certificate
/// that is byte for byte one the user gave. That is how a self-signed
/// certificate is trusted: OpenSSL marks one `CA:TRUE` by default, and
/// WebPKI then refuses it as a server's own certificate, though OpenSSL's
/// clients accept it. Only the exact certificate passes, and the handshake
/// still proves the server holds its key; nothing else is relaxed.
#[derive(Debug)]
struct Pinned {
    inner: Arc<WebPkiServerVerifier>,
    pinned: Vec<CertificateDer<'static>>,
}

impl ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        server_name: &ServerName<'_>,
        ocsp: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        match self
            .inner
            .verify_server_cert(end_entity, intermediates, server_name, ocsp, now)
        {
            Err(_)
                if self
                    .pinned
                    .iter()
                    .any(|pin| pin.as_ref() == end_entity.as_ref()) =>
            {
                Ok(ServerCertVerified::assertion())
            }
            other => other,
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls12_signature(message, cert, dss)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls13_signature(message, cert, dss)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.inner.supported_verify_schemes()
    }
}

/// A certificate failure, with what to do about it in this app's terms.
pub(crate) fn explained(message: &str) -> String {
    let hint = if message.contains("CaUsedAsEndEntity") {
        "The server's certificate is self-signed and marked as a CA, which \
         TLS libraries other than OpenSSL refuse as a server's own. On \
         Postgres, give that exact certificate as the CA and it is trusted as \
         is. On MySQL and MongoDB, issue the server a certificate from a CA \
         of your own and give that CA instead."
    } else if message.contains("UnknownIssuer") {
        "The server's certificate is not signed by a public CA. If it is your \
         own or your company's, choose Custom CA and give that CA's \
         certificate (or the server's own, if it is self-signed)."
    } else if message.contains("NotValidForName") {
        "The certificate is valid, but not for the host name entered. Connect \
         by a name the certificate lists (or the IP address it lists)."
    } else if message.contains("Expired") {
        "The server's certificate has expired."
    } else if message.contains("CertificateRequired") || message.contains("certificate required") {
        "The server wants a client certificate. Add one under TLS."
    } else {
        return message.to_owned();
    };
    format!("{message}\n\n{hint}")
}

/// Files a driver reads its TLS material from, deleted when dropped: the
/// session holds this for as long as it can open side connections.
#[derive(Default)]
pub(crate) struct Files {
    pub ca: Option<PathBuf>,
    pub cert: Option<PathBuf>,
    pub key: Option<PathBuf>,
    /// Certificate and key in one file, as MongoDB's driver reads them.
    pub cert_key: Option<PathBuf>,
}

impl Drop for Files {
    fn drop(&mut self) {
        for path in [&self.ca, &self.cert, &self.key, &self.cert_key]
            .into_iter()
            .flatten()
        {
            let _ = fs::remove_file(path);
        }
    }
}

static NEXT: AtomicU64 = AtomicU64::new(0);

/// Writes the connection's PEMs where the app said, readable by this app only.
pub(crate) fn files(config: &ConnectionConfig) -> Result<Files, CoreError> {
    let mut files = Files::default();
    let ca = config
        .ca_pem
        .as_deref()
        .map(str::trim)
        .filter(|p| !p.is_empty());
    let cert = config
        .client_cert_pem
        .as_deref()
        .map(str::trim)
        .filter(|p| !p.is_empty());
    let key = config
        .client_key_pem
        .as_deref()
        .map(str::trim)
        .filter(|p| !p.is_empty());
    if config.ssl_mode == SslMode::Disable || (ca.is_none() && cert.is_none()) {
        return Ok(files);
    }
    if config.tls_dir.is_empty() {
        return Err(refused(
            "No directory to hand the TLS files to the driver in.",
        ));
    }
    let dir = Path::new(&config.tls_dir);
    fs::create_dir_all(dir).map_err(|error| refused(format!("TLS files: {error}")))?;
    let stem = format!(
        "{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    );
    let write = |suffix: &str, text: &str| -> Result<PathBuf, CoreError> {
        let path = dir.join(format!("{stem}-{suffix}.pem"));
        write_private(&path, text).map_err(|error| refused(format!("TLS files: {error}")))?;
        Ok(path)
    };
    if let Some(ca) = ca {
        files.ca = Some(write("ca", ca)?);
    }
    if let (Some(cert), Some(key)) = (cert, key) {
        files.cert = Some(write("cert", cert)?);
        files.key = Some(write("key", key)?);
        files.cert_key = Some(write("cert-key", &format!("{cert}\n{key}\n"))?);
    }
    Ok(files)
}

#[cfg(unix)]
fn write_private(path: &Path, text: &str) -> std::io::Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(path)?;
    file.write_all(text.as_bytes())
}

#[cfg(not(unix))]
fn write_private(path: &Path, text: &str) -> std::io::Result<()> {
    fs::write(path, text)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Engine;

    fn config(ssl_mode: SslMode) -> ConnectionConfig {
        ConnectionConfig {
            engine: Engine::Postgres,
            host: "h".into(),
            port: 1,
            user: String::new(),
            password: String::new(),
            database: String::new(),
            ssl_mode,
            ca_pem: None,
            client_cert_pem: None,
            client_key_pem: None,
            tls_dir: String::new(),
        }
    }

    #[test]
    fn refuses_what_does_not_add_up() {
        let mut off = config(SslMode::Disable);
        off.ca_pem = Some("x".into());
        assert!(material(&off).is_err(), "a CA with TLS off");

        let mut garbage = config(SslMode::VerifyFull);
        garbage.ca_pem = Some("not a certificate".into());
        assert!(material(&garbage).is_err());

        let mut keyless = config(SslMode::VerifyFull);
        keyless.client_cert_pem =
            Some("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----".into());
        assert!(material(&keyless).is_err());

        let mut blank = config(SslMode::VerifyFull);
        blank.ca_pem = Some("  \n".into());
        assert!(material(&blank).unwrap().ca.is_none(), "blank is unset");
    }
}
