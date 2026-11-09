//! Root-confined file browsing and uploads (security §7.3, API §5).

use std::path::{Component, Path, PathBuf};

use axum::body::Body;
use axum::extract::{Multipart, Query, State};
use axum::http::header;
use axum::response::IntoResponse;
use percent_encoding::{NON_ALPHANUMERIC, utf8_percent_encode};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::io::AsyncWriteExt;
use tokio_util::io::ReaderStream;

use crate::config::expand;
use crate::error::{ApiError, ApiResult};
use crate::state::Shared;

/// Configured roots that exist, canonicalised.
pub fn roots(st: &Shared) -> Vec<PathBuf> {
    st.config
        .files
        .roots
        .iter()
        .filter_map(|r| std::fs::canonicalize(expand(&st.paths.home, r)).ok())
        .collect()
}

fn hidden(name: &std::ffi::OsStr) -> bool {
    name.to_string_lossy().starts_with('.')
}

/// Canonicalise `requested` and require it to sit inside a root, with no
/// hidden components and nothing from Fortis' own directories.
pub fn resolve(st: &Shared, requested: &str) -> ApiResult<PathBuf> {
    let p = Path::new(requested);
    if !p.is_absolute() {
        return Err(ApiError::bad_request("path must be absolute"));
    }
    let real = std::fs::canonicalize(p).map_err(|_| ApiError::not_found())?;
    let root = roots(st)
        .into_iter()
        .find(|r| real.starts_with(r))
        .ok_or_else(|| ApiError::forbidden("path is outside the allowed folders"))?;
    let rel = real.strip_prefix(&root).expect("starts_with checked");
    if rel
        .components()
        .any(|c| matches!(c, Component::Normal(n) if hidden(n)))
    {
        return Err(ApiError::forbidden("hidden paths are not served"));
    }
    for private in [&st.paths.config_dir, &st.paths.data_dir] {
        if let Ok(pc) = std::fs::canonicalize(private)
            && real.starts_with(&pc)
        {
            return Err(ApiError::forbidden("path is not served"));
        }
    }
    Ok(real)
}

pub async fn list_roots(State(st): State<Shared>) -> axum::Json<Value> {
    axum::Json(json!({ "roots": roots(&st) }))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PathQuery {
    path: Option<String>,
}

pub async fn list(
    State(st): State<Shared>,
    Query(q): Query<PathQuery>,
) -> ApiResult<axum::Json<Value>> {
    let dir = match q.path.filter(|p| !p.is_empty()) {
        Some(p) => resolve(&st, &p)?,
        None => roots(&st)
            .into_iter()
            .next()
            .ok_or_else(ApiError::not_found)?,
    };
    if !dir.is_dir() {
        return Err(ApiError::bad_request("not a directory"));
    }
    let mut entries = Vec::new();
    let mut rd = tokio::fs::read_dir(&dir)
        .await
        .map_err(|_| ApiError::not_found())?;
    while let Ok(Some(e)) = rd.next_entry().await {
        if hidden(&e.file_name()) {
            continue;
        }
        let Ok(md) = e.metadata().await else { continue };
        let modified = md
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs())
            .unwrap_or(0);
        entries.push(json!({
            "name": e.file_name().to_string_lossy(),
            "is_dir": md.is_dir(),
            "size": if md.is_dir() { 0 } else { md.len() },
            "modified": modified,
        }));
    }
    entries.sort_by(|a, b| {
        (
            !a["is_dir"].as_bool().unwrap_or(false),
            a["name"].as_str().unwrap_or("").to_lowercase(),
        )
            .cmp(&(
                !b["is_dir"].as_bool().unwrap_or(false),
                b["name"].as_str().unwrap_or("").to_lowercase(),
            ))
    });
    Ok(axum::Json(json!({ "path": dir, "entries": entries })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DownloadQuery {
    path: String,
}

pub async fn download(
    State(st): State<Shared>,
    Query(q): Query<DownloadQuery>,
) -> ApiResult<impl IntoResponse> {
    let p = resolve(&st, &q.path)?;
    if p.is_dir() {
        return Err(ApiError::bad_request("cannot download a directory"));
    }
    let file = tokio::fs::File::open(&p)
        .await
        .map_err(|_| ApiError::not_found())?;
    let name = p
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| "download".into());
    let disposition = format!(
        "attachment; filename*=UTF-8''{}",
        utf8_percent_encode(&name, NON_ALPHANUMERIC)
    );
    log::info!("file download: {}", p.display());
    Ok((
        [
            (header::CONTENT_TYPE, "application/octet-stream".to_string()),
            (header::CONTENT_DISPOSITION, disposition),
        ],
        Body::from_stream(ReaderStream::new(file)),
    ))
}

/// Reduce an uploaded name to a safe single component (security §7.3).
pub fn sanitize_filename(raw: &str) -> String {
    let last = raw.rsplit(['/', '\\']).next().unwrap_or("");
    let cleaned: String = last.chars().filter(|c| !c.is_control()).collect();
    let mut name = cleaned.trim().trim_start_matches('.').trim().to_string();
    while name.len() > 200 {
        name.pop();
    }
    if name.is_empty() {
        "upload.bin".into()
    } else {
        name
    }
}

/// `name.ext`, `name (1).ext`, `name (2).ext`, …
pub fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let candidate = dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let (stem, ext) = match name.rsplit_once('.') {
        Some((s, e)) if !s.is_empty() => (s.to_string(), format!(".{e}")),
        _ => (name.to_string(), String::new()),
    };
    (1..)
        .map(|n| dir.join(format!("{stem} ({n}){ext}")))
        .find(|p| !p.exists())
        .expect("infinite iterator")
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DropQuery {
    dest: Option<String>,
}

pub async fn drop_file(
    State(st): State<Shared>,
    Query(q): Query<DropQuery>,
    mut mp: Multipart,
) -> ApiResult<axum::Json<Value>> {
    let dir = match q.dest.as_deref().unwrap_or("drop") {
        "drop" => st.paths.drop_dir(),
        "camera" => expand(&st.paths.home, &st.config.files.camera_dir),
        _ => return Err(ApiError::bad_request("dest must be drop or camera")),
    };
    tokio::fs::create_dir_all(&dir)
        .await
        .map_err(ApiError::internal)?;
    let max = st.config.max_upload_bytes() as u64;

    while let Some(mut field) = mp
        .next_field()
        .await
        .map_err(|e| ApiError::bad_request(e.to_string()))?
    {
        if field.name() != Some("file") {
            continue;
        }
        let name = sanitize_filename(field.file_name().unwrap_or("upload.bin"));
        let tmp = dir.join(format!(
            ".fortis-upload-{}.part",
            fortis_core::crypto::random_hex(8)
        ));
        let mut f = tokio::fs::File::create(&tmp)
            .await
            .map_err(ApiError::internal)?;
        let mut size: u64 = 0;
        let result: ApiResult<()> = async {
            while let Some(chunk) = field
                .chunk()
                .await
                .map_err(|e| ApiError::bad_request(e.to_string()))?
            {
                size += chunk.len() as u64;
                if size > max {
                    return Err(ApiError::too_large());
                }
                f.write_all(&chunk).await.map_err(ApiError::internal)?;
            }
            f.sync_all().await.map_err(ApiError::internal)?;
            Ok(())
        }
        .await;
        if let Err(e) = result {
            let _ = tokio::fs::remove_file(&tmp).await;
            return Err(e);
        }
        let dest = unique_path(&dir, &name);
        tokio::fs::rename(&tmp, &dest)
            .await
            .map_err(ApiError::internal)?;
        let saved = dest
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        log::info!("received file {saved} ({size} bytes)");
        return Ok(axum::Json(json!({ "saved_as": saved, "size": size })));
    }
    Err(ApiError::bad_request("no `file` field"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::test_support;

    #[test]
    fn filenames() {
        assert_eq!(sanitize_filename("../../etc/passwd"), "passwd");
        assert_eq!(sanitize_filename("..\\..\\x.txt"), "x.txt");
        assert_eq!(sanitize_filename(".bashrc"), "bashrc");
        assert_eq!(sanitize_filename("a\nb\0c.pdf"), "abc.pdf");
        assert_eq!(sanitize_filename("..."), "upload.bin");
        assert_eq!(sanitize_filename(""), "upload.bin");
    }

    #[test]
    fn unique_names() {
        let t = tempfile::tempdir().unwrap();
        std::fs::write(t.path().join("doc.pdf"), "").unwrap();
        std::fs::write(t.path().join("doc (1).pdf"), "").unwrap();
        assert_eq!(
            unique_path(t.path(), "doc.pdf"),
            t.path().join("doc (2).pdf")
        );
        assert_eq!(unique_path(t.path(), "new.txt"), t.path().join("new.txt"));
    }

    #[test]
    fn confinement() {
        let t = tempfile::tempdir().unwrap();
        let st = test_support::state(t.path(), |_| {});
        let docs = t.path().join("Documents");
        std::fs::create_dir_all(docs.join(".secret")).unwrap();
        std::fs::write(docs.join("ok.txt"), "x").unwrap();
        std::fs::write(docs.join(".secret/key"), "x").unwrap();
        std::fs::write(t.path().join("outside.txt"), "x").unwrap();
        std::os::unix::fs::symlink(t.path().join("outside.txt"), docs.join("link.txt")).unwrap();

        assert!(resolve(&st, docs.join("ok.txt").to_str().unwrap()).is_ok());
        assert_eq!(
            resolve(&st, t.path().join("outside.txt").to_str().unwrap())
                .unwrap_err()
                .code,
            "forbidden"
        );
        // Symlink escaping the root is caught after canonicalisation.
        assert_eq!(
            resolve(&st, docs.join("link.txt").to_str().unwrap())
                .unwrap_err()
                .code,
            "forbidden"
        );
        // `..` traversal.
        let trav = format!("{}/../outside.txt", docs.display());
        assert_eq!(resolve(&st, &trav).unwrap_err().code, "forbidden");
        assert_eq!(
            resolve(&st, docs.join(".secret/key").to_str().unwrap())
                .unwrap_err()
                .code,
            "forbidden"
        );
        assert_eq!(
            resolve(&st, "relative/path").unwrap_err().code,
            "bad_request"
        );
        assert_eq!(resolve(&st, "/etc/shadow").unwrap_err().code, "forbidden");
    }
}
