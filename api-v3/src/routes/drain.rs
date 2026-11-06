//! Middleware that reads the rest of a request body that the handler left unread.

use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use axum::body::{Body, Bytes};
use axum::extract::Request;
use axum::middleware::Next;
use axum::response::Response;
use futures_util::StreamExt;
use http_body::{Body as _, Frame, SizeHint};
use tokio::time::timeout;

/// How long `drain_on_drop` goes on reading a body after its request is done with it.
const DRAIN_TIMEOUT: Duration = Duration::from_secs(5);

/// Middleware for routes that take a large body. If the request finishes without reading its
/// body to the end (a 401 from `auth::resolve`, a 415, or a 422 partway through), a spawned task
/// reads and discards the rest, for up to `DRAIN_TIMEOUT`, while the response goes out.
///
/// Without this, the connection closes with the body unread, and the reset can reach the client
/// (or the reverse proxy) before the response does, resulting in an ECONNRESET or an HTTP 502
/// instead of the intended error response.
pub async fn drain_on_drop(req: Request, next: Next) -> Response {
    let req = req.map(|body| Body::new(DrainOnDrop { body, ended: false }));
    next.run(req).await
}

struct DrainOnDrop {
    body: Body,
    /// Whether `body` has returned its last frame. Hyper's `is_end_stream` can't tell us this,
    /// because it stays false for a chunked body even after the body ends.
    ended: bool,
}

impl http_body::Body for DrainOnDrop {
    type Data = Bytes;
    type Error = axum::Error;

    fn poll_frame(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Bytes>, axum::Error>>> {
        let frame = std::task::ready!(Pin::new(&mut self.body).poll_frame(cx));
        if frame.is_none() {
            self.ended = true;
        }
        Poll::Ready(frame)
    }

    fn is_end_stream(&self) -> bool {
        self.ended || self.body.is_end_stream()
    }

    fn size_hint(&self) -> SizeHint {
        self.body.size_hint()
    }
}

impl Drop for DrainOnDrop {
    fn drop(&mut self) {
        if self.ended || self.body.is_end_stream() {
            return;
        }
        let mut chunks = std::mem::take(&mut self.body).into_data_stream();
        tokio::spawn(async move {
            // stops at the end of the body, at the first error, or at the timeout.
            let _ = timeout(DRAIN_TIMEOUT, async {
                while let Some(Ok(_)) = chunks.next().await {}
            })
            .await;
        });
    }
}

#[cfg(test)]
mod tests {
    use std::io;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use http_body_util::BodyExt;

    use super::*;

    /// A body of `n` chunks, and how many of them have been read.
    fn counted(n: usize) -> (DrainOnDrop, Arc<AtomicUsize>) {
        let read = Arc::new(AtomicUsize::new(0));
        let counter = read.clone();
        let chunks = futures_util::stream::iter(0..n).map(move |_| {
            counter.fetch_add(1, Ordering::SeqCst);
            Ok::<_, io::Error>(Bytes::from_static(b"{}\n"))
        });
        let body = DrainOnDrop {
            body: Body::from_stream(chunks),
            ended: false,
        };
        (body, read)
    }

    #[tokio::test]
    async fn reads_the_rest_of_a_body_dropped_unfinished() {
        let (mut body, read) = counted(10);
        body.frame().await.unwrap().unwrap();
        drop(body);
        timeout(Duration::from_secs(5), async {
            while read.load(Ordering::SeqCst) < 10 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("the rest of the body is read");
    }

    #[tokio::test]
    async fn knows_when_a_body_was_read_to_the_end() {
        let (mut body, _) = counted(3);
        while body.frame().await.is_some() {}
        assert!(body.ended);
    }
}
