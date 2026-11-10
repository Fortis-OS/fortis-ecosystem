//! API errors: `{ "error": { "code": "...", "message": "..." } }` (API conventions).

use axum::extract::{FromRequest, Request};
use axum::http::{HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::de::DeserializeOwned;
use serde_json::json;

use fortis_core::db::DbError;

#[derive(Debug)]
pub struct ApiError {
    pub status: StatusCode,
    pub code: &'static str,
    pub message: String,
    pub retry_after: Option<u64>,
}

impl ApiError {
    pub fn new(status: StatusCode, code: &'static str, message: impl Into<String>) -> Self {
        Self {
            status,
            code,
            message: message.into(),
            retry_after: None,
        }
    }
    pub fn bad_request(m: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, "bad_request", m)
    }
    pub fn unauthorized() -> Self {
        Self::new(StatusCode::UNAUTHORIZED, "unauthorized", "Unauthorized")
    }
    pub fn invalid_password() -> Self {
        Self::new(
            StatusCode::UNAUTHORIZED,
            "invalid_password",
            "Invalid master password",
        )
    }
    pub fn forbidden(m: impl Into<String>) -> Self {
        Self::new(StatusCode::FORBIDDEN, "forbidden", m)
    }
    pub fn not_found() -> Self {
        Self::new(StatusCode::NOT_FOUND, "not_found", "Not found")
    }
    pub fn conflict(m: impl Into<String>) -> Self {
        Self::new(StatusCode::CONFLICT, "conflict", m)
    }
    pub fn too_large() -> Self {
        Self::new(
            StatusCode::PAYLOAD_TOO_LARGE,
            "too_large",
            "Request too large",
        )
    }
    pub fn locked() -> Self {
        Self::new(StatusCode::LOCKED, "vault_locked", "Vault is locked")
    }
    pub fn uninitialised() -> Self {
        Self::new(
            StatusCode::TOO_EARLY,
            "vault_uninitialised",
            "No vault yet — initialise it first",
        )
    }
    pub fn rate_limited(retry_after_secs: u64) -> Self {
        Self {
            retry_after: Some(retry_after_secs.max(1)),
            ..Self::new(
                StatusCode::TOO_MANY_REQUESTS,
                "rate_limited",
                "Too many attempts",
            )
        }
    }
    pub fn unavailable(m: impl Into<String>) -> Self {
        Self::new(StatusCode::SERVICE_UNAVAILABLE, "unavailable", m)
    }
    /// Details go to the log only.
    pub fn internal(detail: impl std::fmt::Display) -> Self {
        log::error!("internal error: {detail}");
        Self::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal",
            "Internal error",
        )
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = json!({ "error": { "code": self.code, "message": self.message } });
        let mut resp = (self.status, axum::Json(body)).into_response();
        if let Some(s) = self.retry_after
            && let Ok(v) = HeaderValue::from_str(&s.to_string())
        {
            resp.headers_mut().insert(header::RETRY_AFTER, v);
        }
        resp
    }
}

impl From<DbError> for ApiError {
    fn from(e: DbError) -> Self {
        match e {
            DbError::InvalidPassword => ApiError::invalid_password(),
            DbError::AlreadyExists => ApiError::conflict("A vault already exists"),
            DbError::Uninitialised => ApiError::uninitialised(),
            DbError::NotFound => ApiError::not_found(),
            DbError::Validation(v) => ApiError::bad_request(v.0),
            other => ApiError::internal(other),
        }
    }
}

pub type ApiResult<T> = Result<T, ApiError>;

/// `Json` extractor whose rejections are API errors (400 / 413).
pub struct ApiJson<T>(pub T);

impl<S: Send + Sync, T: DeserializeOwned> FromRequest<S> for ApiJson<T> {
    type Rejection = ApiError;

    async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
        match axum::Json::<T>::from_request(req, state).await {
            Ok(axum::Json(v)) => Ok(ApiJson(v)),
            Err(rej) if rej.status() == StatusCode::PAYLOAD_TOO_LARGE => Err(ApiError::too_large()),
            Err(rej) => Err(ApiError::bad_request(rej.body_text())),
        }
    }
}
