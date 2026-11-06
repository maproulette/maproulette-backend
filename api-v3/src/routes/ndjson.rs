//! Parser for NDJSON request bodies

use std::io;
use std::pin::Pin;
use std::time::Duration;

use async_compression::tokio::bufread::GzipDecoder;
use axum::body::Body;
use axum::extract::{FromRequest, Request};
use axum::http::{HeaderMap, header};
use futures_util::StreamExt;
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncReadExt, BufReader};
use tokio::time::timeout;
use tokio_util::io::StreamReader;

use crate::error::Error;

/// An `application/x-ndjson` (or `application/jsonl`) body, optionally gzipped.
pub struct Ndjson {
    body: Body,
    compression: Compression,
    content_length: Option<u64>,
}

impl<S: Send + Sync> FromRequest<S> for Ndjson {
    type Rejection = Error;

    async fn from_request(req: Request, _: &S) -> Result<Ndjson, Error> {
        let headers = req.headers();
        require_ndjson(headers)?;
        let compression = compression(headers)?;
        let content_length = content_length(headers);
        Ok(Ndjson {
            body: req.into_body(),
            compression,
            content_length,
        })
    }
}

impl Ndjson {
    /// Return a lazy reader over the body's lines.
    pub fn lines(self, limits: Limits) -> Result<Lines, Error> {
        if self.compression == Compression::None
            && self.content_length.is_some_and(|n| n > limits.max_bytes)
        {
            return Err(Error::PayloadTooLarge);
        }
        Ok(Lines::new(self.body, self.compression, limits))
    }
}

/// Limits on reading an NDJSON body.
#[derive(Clone, Copy, Debug)]
pub struct Limits {
    /// Maximum size in bytes of the entire body, after decompression.
    pub max_bytes: u64,
    /// Maximum size in bytes of any single line, excluding the newline.
    pub max_line_bytes: usize,
    /// How long the client may go without sending anything.
    pub idle_timeout: Duration,
}

/// Why the body couldn't be read.
#[derive(Debug)]
pub enum ReadError {
    /// The body is bigger than `max_bytes`.
    TooLarge,
    /// Some line is longer than `max_line_bytes`.
    LineTooLong { line: u64, max: usize },
    /// The client went quiet for longer than `idle_timeout`.
    TimedOut,
    /// The connection failed, or the body isn't valid gzip.
    Io(io::Error),
}

fn require_ndjson(headers: &HeaderMap) -> Result<(), Error> {
    let content_type = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default();
    let essence = content_type.split(';').next().unwrap().trim();
    if essence.eq_ignore_ascii_case("application/x-ndjson")
        || essence.eq_ignore_ascii_case("application/jsonl")
    {
        Ok(())
    } else {
        Err(Error::UnsupportedMediaType(
            "Expected Content-Type: application/x-ndjson or application/jsonl".into(),
        ))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Compression {
    None,
    Gzip,
}

fn compression(headers: &HeaderMap) -> Result<Compression, Error> {
    match headers.get(header::CONTENT_ENCODING).map(|v| v.to_str()) {
        None => Ok(Compression::None),
        Some(Ok(e)) if e.eq_ignore_ascii_case("identity") => Ok(Compression::None),
        Some(Ok(e)) if e.eq_ignore_ascii_case("gzip") || e.eq_ignore_ascii_case("x-gzip") => {
            Ok(Compression::Gzip)
        }
        Some(_) => Err(Error::UnsupportedMediaType(
            "Content-Encoding must be gzip, or absent".into(),
        )),
    }
}

fn content_length(headers: &HeaderMap) -> Option<u64> {
    headers
        .get(header::CONTENT_LENGTH)?
        .to_str()
        .ok()?
        .parse()
        .ok()
}

type Reader = Pin<Box<dyn AsyncBufRead + Send>>;

/// Splits the body into lines, enforcing the size limits and the idle timeout.
pub struct Lines {
    reader: Reader,
    limits: Limits,
    /// The current line.
    buf: Vec<u8>,
    /// The current line number, counting from 1.
    line: u64,
    /// How much of the body has been read, after decompression.
    bytes: u64,
}

impl Lines {
    fn new(body: Body, compression: Compression, limits: Limits) -> Lines {
        let idle = limits.idle_timeout;
        let chunks =
            futures_util::stream::unfold(body.into_data_stream(), move |mut chunks| async move {
                let chunk = match timeout(idle, chunks.next()).await {
                    Ok(None) => return None,
                    Ok(Some(chunk)) => chunk.map_err(io::Error::other),
                    Err(_) => Err(io::Error::other(IdleTimeout)),
                };
                Some((chunk, chunks))
            });
        let reader = StreamReader::new(chunks);
        let reader: Reader = match compression {
            Compression::None => Box::pin(reader),
            Compression::Gzip => {
                let mut decoder = GzipDecoder::new(reader);
                decoder.multiple_members(true);
                Box::pin(BufReader::new(decoder))
            }
        };
        Lines {
            reader,
            limits,
            buf: Vec::new(),
            line: 0,
            bytes: 0,
        }
    }

    /// Reads the next line, and returns its number and its content without the
    /// newline. Returns None at the end of the body.
    pub async fn next(&mut self) -> Result<Option<(u64, &[u8])>, ReadError> {
        self.buf.clear();
        let n = (&mut self.reader)
            .take(self.limits.max_line_bytes as u64 + 1)
            .read_until(b'\n', &mut self.buf)
            .await
            .map_err(|e| match e.get_ref() {
                Some(inner) if inner.is::<IdleTimeout>() => ReadError::TimedOut,
                _ => ReadError::Io(e),
            })?;

        // NOTE: this can overshoot `max_bytes` by up to a line before it's caught
        self.bytes += n as u64;
        if self.bytes > self.limits.max_bytes {
            return Err(ReadError::TooLarge);
        }

        if n == 0 {
            return Ok(None);
        }

        self.line += 1;
        if self.buf.last() == Some(&b'\n') {
            self.buf.pop();
        }

        if self.buf.len() > self.limits.max_line_bytes {
            return Err(ReadError::LineTooLong {
                line: self.line,
                max: self.limits.max_line_bytes,
            });
        }

        let mut text = &self.buf[..];
        if self.line == 1 {
            // strip UTF-8 byte order mark at the start of the stream, if present
            text = text.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(text);
        }

        Ok(Some((self.line, text)))
    }

    /// How much of the body has been read, after decompression.
    pub fn bytes(&self) -> u64 {
        self.bytes
    }
}

/// The error the body stream yields when the client goes quiet for longer than `idle_timeout`.
#[derive(Debug, thiserror::Error)]
#[error("timed out waiting for the request body")]
struct IdleTimeout;
