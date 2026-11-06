use serde_json::Value;

/// A task definition in the form that gets uploaded to the /challenges/:id/tasks endpoint
#[derive(Clone, Debug, PartialEq)]
pub struct NewTask {
    /// The line of the NDJSON upload that this task came from (counting from 1)
    pub line: u64,
    /// The Feature's `id`, which identifies the task within its challenge.
    pub key: String,
    /// The Feature's geometry, as uploaded.
    pub geometry: Value,
    /// The Feature's properties, including `@id` for a key that names an OSM element.
    pub properties: serde_json::Map<String, Value>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UploadTarget {
    /// The priority of new tasks.
    pub default_priority: i32,
    /// The challenge is built from an Overpass query.
    pub overpass: bool,
    /// The challenge's tasks carry cooperative work (tag fixes or change files).
    pub cooperative: bool,
}

/// Summary of what an upload changed. Every uploaded task is counted exactly
/// once in `created`, `updated`, `unchanged` or `reopened`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct UploadCounts {
    pub created: u64,
    pub updated: u64,
    pub unchanged: u64,
    pub reopened: u64,
    /// Open tasks that weren't in the upload.
    pub closed: u64,
    /// Open tasks in the challenge afterwards.
    pub open_tasks: u64,
}

/// A feature that the database found a problem with, after the upload was parsed.
#[derive(Clone, Debug, PartialEq)]
pub struct InvalidTask {
    pub line: u64,
    pub key: String,
    /// A JSON Pointer to the problem field, within the line's Feature
    pub pointer: String,
    pub message: String,
}
