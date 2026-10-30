//! Request extractors whose rejections are `Error`s, so they render as problem documents like
//! every other error. Handlers use these instead of axum's `Json` and `Path`, which reject with
//! plain text.

use axum::extract::rejection::{JsonRejection, PathRejection};
use axum::extract::{FromRequest, FromRequestParts};
use axum::http::StatusCode;

use crate::error::{Error, FieldError};

/// Path parameters. A value that doesn't parse (`/challenges/abc`) is a 404: no such resource.
#[derive(FromRequestParts)]
#[from_request(via(axum::extract::Path), rejection(Error))]
pub struct Path<T>(pub T);

impl From<PathRejection> for Error {
    fn from(rejection: PathRejection) -> Error {
        match rejection {
            PathRejection::FailedToDeserializePathParams(_) => Error::NotFound,
            // The route and the handler's parameters disagree: a bug, not a bad request.
            r => Error::Unexpected(anyhow::anyhow!(r.body_text())),
        }
    }
}

/// A JSON request body. Needs `Content-Type: application/json` (or another `+json` type).
/// Malformed JSON is a 400. JSON that doesn't fit `T` (a missing or unknown member, a wrong type)
/// is a 422 whose message names the member.
#[derive(FromRequest)]
#[from_request(via(axum::Json), rejection(Error))]
pub struct JsonBody<T>(pub T);

impl From<JsonRejection> for Error {
    fn from(rejection: JsonRejection) -> Error {
        match rejection {
            JsonRejection::JsonDataError(e) => {
                Error::Validation(vec![FieldError::body(e.body_text())])
            }
            JsonRejection::JsonSyntaxError(e) => Error::BadRequest(e.body_text()),
            JsonRejection::MissingJsonContentType(_) => {
                Error::UnsupportedMediaType("Expected Content-Type: application/json".into())
            }
            r if r.status() == StatusCode::PAYLOAD_TOO_LARGE => Error::PayloadTooLarge,
            r => Error::BadRequest(r.body_text()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::extract::Request;
    use axum::http::header;
    use serde::Deserialize;

    #[derive(Debug, Deserialize)]
    #[serde(deny_unknown_fields)]
    #[allow(dead_code)]
    struct Input {
        name: String,
        tags: Vec<Tag>,
    }

    #[derive(Debug, Deserialize)]
    #[serde(deny_unknown_fields)]
    #[allow(dead_code)]
    struct Tag {
        #[serde(rename = "a/b")]
        value: u8,
    }

    async fn extract(content_type: Option<&str>, body: &str) -> Result<Input, Error> {
        let mut req = Request::builder().method("POST").uri("/");
        if let Some(ct) = content_type {
            req = req.header(header::CONTENT_TYPE, ct);
        }
        let req = req.body(Body::from(body.to_owned())).unwrap();
        JsonBody::<Input>::from_request(req, &()).await.map(|b| b.0)
    }

    fn field_errors(result: Result<Input, Error>) -> Vec<(Option<String>, String)> {
        match result {
            Err(Error::Validation(errors)) => {
                errors.into_iter().map(|e| (e.pointer, e.message)).collect()
            }
            other => panic!("expected a validation error, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn unparseable_path_parameter_is_not_found() {
        use axum::Router;
        use axum::response::IntoResponse;
        use axum::routing::get;
        use tower::ServiceExt;

        let app = Router::new().route(
            "/things/{id}",
            get(|Path(id): Path<i32>| async move { id.to_string() }),
        );
        let get = |uri: &'static str| {
            let app = app.clone();
            async move {
                let req = Request::get(uri).body(Body::empty()).unwrap();
                app.oneshot(req).await.unwrap().into_response().status()
            }
        };
        assert_eq!(get("/things/12").await, StatusCode::OK);
        assert_eq!(get("/things/abc").await, StatusCode::NOT_FOUND);
        assert_eq!(get("/things/99999999999").await, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn accepts_json() {
        let body = r#"{"name": "x", "tags": [{"a/b": 1}]}"#;
        assert_eq!(
            extract(Some("application/json"), body).await.unwrap().name,
            "x"
        );
        let body = r#"{"name": "x", "tags": []}"#;
        assert!(
            extract(Some("application/merge-patch+json; charset=utf-8"), body)
                .await
                .is_ok()
        );
    }

    #[tokio::test]
    async fn needs_a_json_content_type() {
        for ct in [
            None,
            Some("text/plain"),
            Some("application/x-www-form-urlencoded"),
        ] {
            let result = extract(ct, r#"{"name": "x", "tags": []}"#).await;
            assert!(
                matches!(result, Err(Error::UnsupportedMediaType(_))),
                "{ct:?}"
            );
        }
    }

    #[tokio::test]
    async fn malformed_json_is_a_bad_request() {
        for body in ["", "{", r#"{"name": "x", "tags": []} x"#, "{name: 1}"] {
            let result = extract(Some("application/json"), body).await;
            assert!(
                matches!(result, Err(Error::BadRequest(_))),
                "{body:?}: {result:?}"
            );
        }
    }

    #[tokio::test]
    async fn misfit_json_is_a_validation_error_naming_the_member() {
        let ct = Some("application/json");
        for (body, member) in [
            (r#"{"name": 1, "tags": []}"#, "name"),
            (r#"{"name": "x", "tags": [{"a/b": 300}]}"#, "tags[0].a/b"),
            (r#"{"name": "x", "tags": [], "extra": 1}"#, "extra"),
            (r#"{"tags": []}"#, "name"),
        ] {
            let errors = field_errors(extract(ct, body).await);
            assert_eq!(errors.len(), 1, "{body}");
            assert_eq!(errors[0].0, None, "{body}");
            assert!(errors[0].1.contains(member), "{body}: {}", errors[0].1);
        }
    }
}
