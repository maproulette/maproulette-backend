use serde_json::{Map, Value};

use crate::error::FieldError;
use crate::model::NewTask;

/// Maximum length of the GeoJSON feature's `id` (the task key), if it is a string
/// (integer keys are also allowed, and are stored in the database stringified).
const MAX_KEY_CHARS: usize = 255;

/// Maximum size of the GeoJSON's `properties` when serialized.
const MAX_PROPERTIES_BYTES: usize = 64 * 1024;

pub const NDJSON_HINT: &str = "The body must be NDJSON, one GeoJSON Feature per line. Convert a \
                               FeatureCollection with jq -c '.features[]'";

/// Why a line was rejected.
#[derive(Debug)]
pub enum Invalid {
    /// The line isn't JSON.
    Json(serde_json::Error),
    /// The line is JSON but not an acceptable Feature. Details are given as FieldErrors.
    Feature(Vec<FieldError>),
}

/// A problem in part of a value: a JSON pointer to the part, and a message describing the issue.
#[derive(Debug)]
struct Problem {
    pointer: String,
    message: String,
}

fn problem(pointer: impl Into<String>, message: impl Into<String>) -> Problem {
    Problem {
        pointer: pointer.into(),
        message: message.into(),
    }
}

impl Problem {
    /// The same problem, relative to the value that holds this one at `prefix`.
    fn within(mut self, prefix: &str) -> Problem {
        self.pointer.insert_str(0, prefix);
        self
    }
}

/// A member name as a JSON Pointer reference token (RFC 6901), with its leading `/`.
fn token(member: &str) -> String {
    format!("/{}", member.replace('~', "~0").replace('/', "~1"))
}

/// Parses one non-blank line of the upload.
pub fn parse(line: u64, text: &[u8]) -> Result<NewTask, Invalid> {
    let value: Value = serde_json::from_slice(text).map_err(Invalid::Json)?;
    let Value::Object(feature) = value else {
        return Err(Invalid::Feature(vec![FieldError::line(
            line,
            None,
            None,
            "must be a GeoJSON Feature object",
        )]));
    };
    task(line, feature).map_err(Invalid::Feature)
}

fn task(line: u64, mut feature: Map<String, Value>) -> Result<NewTask, Vec<FieldError>> {
    let invalid = |key: &Option<String>, problems: Vec<Problem>| -> Vec<FieldError> {
        problems
            .into_iter()
            .map(|p| FieldError::line(line, key.clone(), Some(p.pointer), p.message))
            .collect()
    };

    match feature.remove("type") {
        Some(Value::String(t)) if t == "Feature" => {}
        Some(Value::String(t)) if t == "FeatureCollection" => {
            return Err(invalid(
                &None,
                vec![problem(
                    "/type",
                    format!("must be \"Feature\". {NDJSON_HINT}"),
                )],
            ));
        }
        _ => {
            // if the line isn't a GeoJSON Feature then it would probably fail every
            // other check, so return early in this case
            return Err(invalid(
                &None,
                vec![problem("/type", "must be \"Feature\"")],
            ));
        }
    }

    let mut problems = Vec::new();

    let key = match feature.remove("id") {
        Some(Value::String(s)) if s.is_empty() || s.chars().count() > MAX_KEY_CHARS => {
            problems.push(problem(
                "/id",
                format!("must be 1 to {MAX_KEY_CHARS} characters"),
            ));
            None
        }
        Some(Value::String(s)) if s.chars().any(char::is_control) => {
            problems.push(problem("/id", "must not contain control characters"));
            None
        }
        Some(Value::String(s)) => Some(s),
        Some(Value::Number(n)) if n.is_i64() || n.is_u64() => Some(n.to_string()),
        Some(_) => {
            problems.push(problem("/id", "must be a string or an integer"));
            None
        }
        None => {
            problems.push(problem(
                "/id",
                "is required, because it identifies the task",
            ));
            None
        }
    };

    let geometry = match feature.remove("geometry") {
        None | Some(Value::Null) => {
            problems.push(problem("/geometry", "is required"));
            None
        }
        Some(mut g) => match check_geometry(&mut g, false) {
            Ok(()) => Some(g),
            Err(p) => {
                problems.push(p.within("/geometry"));
                None
            }
        },
    };

    let mut properties = match feature.remove("properties") {
        None | Some(Value::Null) => Map::new(),
        Some(Value::Object(p)) => p,
        Some(_) => {
            problems.push(problem("/properties", "must be an object"));
            Map::new()
        }
    };
    if properties.contains_key("maproulette") {
        problems.push(problem("/properties/maproulette", "is reserved"));
    }
    if let Some(element) = key.as_deref().and_then(osm_element) {
        match properties.get("@id") {
            None => {}
            Some(Value::String(id)) if id == element => {}
            Some(_) => problems.push(problem(
                "/properties/@id",
                format!("must be \"{element}\", the OSM element that the id names, or absent"),
            )),
        }
        properties.insert("@id".into(), element.into());
    }

    let serialized = serde_json::to_string(&properties).unwrap();
    if serialized.len() > MAX_PROPERTIES_BYTES {
        problems.push(problem(
            "/properties",
            format!(
                "must be at most {} KiB when serialized",
                MAX_PROPERTIES_BYTES / 1024
            ),
        ));
    }

    // Postgres can't store U+0000 in jsonb.
    if serialized.contains("\\u0000") {
        problems.push(problem(
            "/properties",
            "must not contain the character U+0000",
        ));
    }

    feature.remove("bbox");
    for member in feature.keys() {
        problems.push(problem(token(member), "is an unrecognized property"));
    }

    match (key, geometry) {
        (Some(key), Some(geometry)) if problems.is_empty() => Ok(NewTask {
            line,
            key,
            geometry,
            properties,
        }),
        (key, _) => Err(invalid(&key, problems)),
    }
}

/// The OSM element a key names (`way/123` for `way/123` or `way/123@7`), if it names one.
fn osm_element(key: &str) -> Option<&str> {
    let (element, suffix) = match key.split_once('@') {
        Some((element, suffix)) => (element, Some(suffix)),
        None => (key, None),
    };
    if suffix.is_some_and(|s| s.is_empty() || s.contains('/')) {
        return None;
    }
    let (kind, id) = element.split_once('/')?;
    let id_ok = id.starts_with(|c: char| ('1'..='9').contains(&c))
        && id.bytes().all(|b| b.is_ascii_digit());
    (matches!(kind, "node" | "way" | "relation") && id_ok).then_some(element)
}

type Check = Result<(), Problem>;

/// Checks a geometry's shape, and drops its `bbox`. Other members than those GeoJSON defines
/// for the type are rejected, so that nothing unchecked is stored or reaches PostGIS.
fn check_geometry(g: &mut Value, in_collection: bool) -> Check {
    let Value::Object(g) = g else {
        return Err(problem("", "must be a GeoJSON geometry object"));
    };
    g.remove("bbox");
    let Some(kind) = g.get("type").and_then(Value::as_str) else {
        return Err(problem("/type", "must be a GeoJSON geometry type"));
    };
    let content = if kind == "GeometryCollection" {
        "geometries"
    } else {
        "coordinates"
    };
    if let Some(member) = g.keys().find(|m| *m != "type" && *m != content) {
        return Err(problem(
            token(member),
            format!("is not a member of a GeoJSON {kind}"),
        ));
    }
    if kind == "GeometryCollection" {
        if in_collection {
            return Err(problem("", "a GeometryCollection must not contain another"));
        }
        let Some(parts) = g.get_mut("geometries").and_then(Value::as_array_mut) else {
            return Err(problem("/geometries", "must be an array"));
        };
        if parts.is_empty() {
            return Err(problem("/geometries", "must not be empty"));
        }
        for (i, part) in parts.iter_mut().enumerate() {
            check_geometry(part, true).map_err(|p| p.within(&format!("/geometries/{i}")))?;
        }
        return Ok(());
    }
    let Some(c) = g.get("coordinates") else {
        return Err(problem("/coordinates", "is required"));
    };
    let point = |p: &Value| position(p).map(drop);
    let line = |l: &Value| positions(l, 2, "a LineString").map(drop);
    match kind {
        "Point" => point(c),
        "MultiPoint" => each(c, point),
        "LineString" => line(c),
        "MultiLineString" => each(c, line),
        "Polygon" => each(c, ring),
        "MultiPolygon" => each(c, |p| each(p, ring)),
        _ => {
            return Err(problem(
                "/type",
                format!("\"{kind}\" is not a GeoJSON geometry type"),
            ));
        }
    }
    .map_err(|p| p.within("/coordinates"))
}

fn position(p: &Value) -> Result<Vec<f64>, Problem> {
    let p: Option<Vec<f64>> = p
        .as_array()
        .filter(|a| (2..=3).contains(&a.len()))
        .and_then(|a| a.iter().map(Value::as_f64).collect());
    let Some(p) = p else {
        return Err(problem(
            "",
            "must be a position: [longitude, latitude], with an optional elevation",
        ));
    };
    if !(-180.0..=180.0).contains(&p[0]) {
        return Err(problem("", "longitude must be between -180 and 180"));
    }
    if !(-90.0..=90.0).contains(&p[1]) {
        return Err(problem("", "latitude must be between -90 and 90"));
    }
    Ok(p)
}

fn positions(v: &Value, min: usize, what: &str) -> Result<Vec<Vec<f64>>, Problem> {
    let Some(a) = v.as_array() else {
        return Err(problem("", "must be an array of positions"));
    };
    let ps = a
        .iter()
        .enumerate()
        .map(|(i, p)| position(p).map_err(|e| e.within(&format!("/{i}"))))
        .collect::<Result<Vec<_>, _>>()?;
    if ps.len() < min {
        return Err(problem(
            "",
            format!("{what} needs at least {min} positions"),
        ));
    }
    Ok(ps)
}

fn ring(v: &Value) -> Check {
    let ps = positions(v, 4, "a polygon ring")?;
    if ps.first() != ps.last() {
        return Err(problem(
            "",
            "a polygon ring must end at the position it starts at",
        ));
    }
    Ok(())
}

/// Checks each element of a non-empty array.
fn each(v: &Value, check: impl Fn(&Value) -> Check) -> Check {
    let Some(a) = v.as_array().filter(|a| !a.is_empty()) else {
        return Err(problem("", "must be a non-empty array"));
    };
    for (i, x) in a.iter().enumerate() {
        check(x).map_err(|e| e.within(&format!("/{i}")))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn point() -> Value {
        json!({"type": "Point", "coordinates": [-122.3, 47.6]})
    }

    fn feature(id: Value) -> Value {
        json!({"type": "Feature", "id": id, "geometry": point(), "properties": {"name": "x"}})
    }

    fn parse_value(v: &Value) -> Result<NewTask, Invalid> {
        parse(7, v.to_string().as_bytes())
    }

    /// The errors for a JSON value that isn't an acceptable Feature.
    fn errors(v: &Value) -> Vec<FieldError> {
        match parse_value(v) {
            Ok(task) => panic!("expected {v} to be rejected, got {task:?}"),
            Err(Invalid::Json(e)) => panic!("{v} isn't JSON: {e}"),
            Err(Invalid::Feature(errors)) => errors,
        }
    }

    /// Asserts that `v` is rejected with exactly one error, at `pointer`,
    /// whose message contains `text`.
    #[track_caller]
    fn rejects_with(v: Value, pointer: &str, text: &str) {
        let errors = errors(&v);
        assert_eq!(errors.len(), 1, "{v}: {errors:?}");
        assert_eq!(
            errors[0].pointer.as_deref(),
            Some(pointer),
            "{v}: {errors:?}"
        );
        assert!(errors[0].message.contains(text), "{v}: {errors:?}");
    }

    #[track_caller]
    fn rejects(v: Value, pointer: &str) {
        rejects_with(v, pointer, "");
    }

    fn with(mut f: Value, member: &str, value: Value) -> Value {
        f[member] = value;
        f
    }

    fn geometry(g: Value) -> Value {
        with(feature(json!("k")), "geometry", g)
    }

    #[test]
    fn accepts_a_feature() {
        let task = parse_value(&feature(json!("k1"))).unwrap();
        assert_eq!(
            task,
            NewTask {
                line: 7,
                key: "k1".into(),
                geometry: point(),
                properties: json!({"name": "x"}).as_object().unwrap().clone(),
            }
        );
    }

    #[test]
    fn integer_ids_become_strings() {
        assert_eq!(parse_value(&feature(json!(42))).unwrap().key, "42");
        assert_eq!(parse_value(&feature(json!(-3))).unwrap().key, "-3");
        rejects(feature(json!(1.5)), "/id");
        rejects(feature(json!(null)), "/id");
        rejects(feature(json!([1])), "/id");
    }

    #[test]
    fn ids_are_limited() {
        rejects(feature(json!("")), "/id");
        rejects(feature(json!("a\tb")), "/id");
        assert!(parse_value(&feature(json!("é".repeat(255)))).is_ok());
        rejects(feature(json!("é".repeat(256))), "/id");
        let mut f = feature(json!("k"));
        f.as_object_mut().unwrap().remove("id");
        rejects(f, "/id");
    }

    #[test]
    fn needs_a_feature() {
        rejects(with(feature(json!("k")), "type", json!("Point")), "/type");
        rejects_with(
            json!({"type": "FeatureCollection", "features": []}),
            "/type",
            "jq",
        );
        let errors = errors(&json!([1]));
        assert_eq!(errors.len(), 1);
        assert_eq!(errors[0].pointer, None);
        assert_eq!(errors[0].message, "must be a GeoJSON Feature object");
        assert!(matches!(parse(1, b"{\"type\": "), Err(Invalid::Json(_))));
    }

    #[test]
    fn reports_every_problem_with_the_line_and_key() {
        let f = json!({"type": "Feature", "id": "k", "geometry": null, "properties": [], "x": 1});
        let errors = errors(&f);
        let pointers: Vec<_> = errors.iter().map(|e| e.pointer.as_deref()).collect();
        assert_eq!(
            pointers,
            [Some("/geometry"), Some("/properties"), Some("/x")]
        );
        for e in &errors {
            assert_eq!((e.line, e.key.as_deref()), (Some(7), Some("k")), "{e:?}");
        }
    }

    #[test]
    fn rejects_unknown_members_and_ignores_bbox() {
        assert!(parse_value(&with(feature(json!("k")), "bbox", json!([0, 0, 1, 1]))).is_ok());
        rejects(
            with(feature(json!("k")), "attachments", json!([])),
            "/attachments",
        );
        rejects(
            with(feature(json!("k")), "cooperativeWork", json!({})),
            "/cooperativeWork",
        );
        rejects(with(feature(json!("k")), "a/b~c", json!(1)), "/a~1b~0c");
    }

    #[test]
    fn properties_are_optional_objects() {
        let mut f = feature(json!("k"));
        f.as_object_mut().unwrap().remove("properties");
        assert!(parse_value(&f).unwrap().properties.is_empty());
        let f = with(feature(json!("k")), "properties", json!(null));
        assert!(parse_value(&f).unwrap().properties.is_empty());
        rejects(
            with(feature(json!("k")), "properties", json!("x")),
            "/properties",
        );
    }

    #[test]
    fn property_values_are_kept_as_uploaded() {
        let props = json!({"n": 1, "b": true, "z": null, "a": [1, "x"], "o": {"p": 1.5}});
        let task = parse_value(&with(feature(json!("k")), "properties", props.clone())).unwrap();
        assert_eq!(Value::Object(task.properties), props);
    }

    #[test]
    fn maproulette_property_is_reserved() {
        let f = with(
            feature(json!("k")),
            "properties",
            json!({"maproulette": {}}),
        );
        rejects(f, "/properties/maproulette");
    }

    #[test]
    fn properties_are_limited_in_size() {
        let big = |n: usize| {
            with(
                feature(json!("k")),
                "properties",
                json!({"x": "a".repeat(n)}),
            )
        };
        // {"x":"..."} is 8 bytes plus the string.
        assert!(parse_value(&big(MAX_PROPERTIES_BYTES - 8)).is_ok());
        rejects_with(big(MAX_PROPERTIES_BYTES - 7), "/properties", "64 KiB");
    }

    #[test]
    fn nul_characters_are_rejected() {
        let f = with(feature(json!("k")), "properties", json!({"x": "a\u{0}b"}));
        rejects_with(f, "/properties", "U+0000");
    }

    #[test]
    fn osm_element_keys_set_the_id_property() {
        for (key, element) in [
            ("way/123", Some("way/123")),
            ("node/1", Some("node/1")),
            ("relation/90@7", Some("relation/90")),
            ("way/5@2026-10-30@x", Some("way/5")),
            ("way/0", None),
            ("way/012", None),
            ("way/12a", None),
            ("way/", None),
            ("way/1@", None),
            ("way/1@a/b", None),
            ("w123", None),
            ("area/1", None),
            ("Way/1", None),
        ] {
            assert_eq!(osm_element(key), element, "{key}");
            let task = parse_value(&feature(json!(key))).unwrap();
            assert_eq!(
                task.properties.get("@id").and_then(Value::as_str),
                element,
                "{key}"
            );
        }
    }

    #[test]
    fn id_property_must_match_the_element() {
        let f = |id: Value| {
            with(
                feature(json!("way/123@4")),
                "properties",
                json!({"@id": id}),
            )
        };
        assert!(parse_value(&f(json!("way/123"))).is_ok());
        rejects(f(json!("way/123@4")), "/properties/@id");
        rejects(f(json!(123)), "/properties/@id");
        // Without an OSM element key, @id is an ordinary property.
        let other = with(feature(json!("k")), "properties", json!({"@id": 5}));
        assert_eq!(parse_value(&other).unwrap().properties["@id"], json!(5));
    }

    #[test]
    fn accepts_every_geometry_type() {
        let ring = json!([[0, 0], [1, 0], [1, 1], [0, 0]]);
        for g in [
            json!({"type": "Point", "coordinates": [1, 2, 3]}),
            json!({"type": "MultiPoint", "coordinates": [[1, 2], [3, 4]]}),
            json!({"type": "LineString", "coordinates": [[1, 2], [3, 4]]}),
            json!({"type": "MultiLineString", "coordinates": [[[1, 2], [3, 4]]]}),
            json!({"type": "Polygon", "coordinates": [ring]}),
            json!({"type": "MultiPolygon", "coordinates": [[ring], [ring]]}),
            json!({"type": "GeometryCollection", "geometries": [point(), {"type": "Polygon", "coordinates": [ring]}]}),
        ] {
            assert_eq!(parse_value(&geometry(g.clone())).unwrap().geometry, g);
        }
    }

    #[test]
    fn drops_geometry_bboxes() {
        let g = json!({"type": "GeometryCollection", "bbox": [0, 0, 1, 1],
                       "geometries": [{"type": "Point", "coordinates": [0, 0], "bbox": "x"}]});
        assert_eq!(
            parse_value(&geometry(g)).unwrap().geometry,
            json!({"type": "GeometryCollection", "geometries": [{"type": "Point", "coordinates": [0, 0]}]})
        );
    }

    #[test]
    fn rejects_malformed_geometry() {
        let open = json!([[0, 0], [1, 0], [1, 1], [0, 1]]);
        for (g, pointer, text) in [
            (json!(null), "/geometry", "required"),
            (json!("POINT(1 2)"), "/geometry", "geometry object"),
            (json!({"coordinates": [1, 2]}), "/geometry/type", ""),
            (
                json!({"type": "Circle", "coordinates": [1, 2]}),
                "/geometry/type",
                "Circle",
            ),
            (
                json!({"type": "Point", "coordinates": [0, 0], "crs": {}}),
                "/geometry/crs",
                "not a member",
            ),
            (
                json!({"type": "Point", "coordinates": [0, 0], "geometries": []}),
                "/geometry/geometries",
                "not a member",
            ),
            (
                json!({"type": "GeometryCollection", "geometries": [], "coordinates": []}),
                "/geometry/coordinates",
                "not a member",
            ),
            (
                json!({"type": "Point", "coordinates": [0, 0], "x": "\u{0}"}),
                "/geometry/x",
                "not a member",
            ),
            (
                json!({"type": "Point"}),
                "/geometry/coordinates",
                "required",
            ),
            (
                json!({"type": "Point", "coordinates": []}),
                "/geometry/coordinates",
                "position",
            ),
            (
                json!({"type": "Point", "coordinates": [1]}),
                "/geometry/coordinates",
                "position",
            ),
            (
                json!({"type": "Point", "coordinates": [1, 2, 3, 4]}),
                "/geometry/coordinates",
                "position",
            ),
            (
                json!({"type": "Point", "coordinates": ["1", 2]}),
                "/geometry/coordinates",
                "position",
            ),
            (
                json!({"type": "Point", "coordinates": [181, 0]}),
                "/geometry/coordinates",
                "longitude",
            ),
            (
                json!({"type": "Point", "coordinates": [0, -90.5]}),
                "/geometry/coordinates",
                "latitude",
            ),
            (
                json!({"type": "MultiPoint", "coordinates": []}),
                "/geometry/coordinates",
                "non-empty",
            ),
            (
                json!({"type": "MultiPoint", "coordinates": [[0, 0], [0]]}),
                "/geometry/coordinates/1",
                "position",
            ),
            (
                json!({"type": "LineString", "coordinates": [[0, 0]]}),
                "/geometry/coordinates",
                "a LineString",
            ),
            (
                json!({"type": "MultiLineString", "coordinates": [[[0, 0], [1, 1]], [[0, 0]]]}),
                "/geometry/coordinates/1",
                "a LineString",
            ),
            (
                json!({"type": "Polygon", "coordinates": []}),
                "/geometry/coordinates",
                "non-empty",
            ),
            (
                json!({"type": "Polygon", "coordinates": [[[0, 0], [1, 1], [0, 0]]]}),
                "/geometry/coordinates/0",
                "a polygon ring needs",
            ),
            (
                json!({"type": "Polygon", "coordinates": [open]}),
                "/geometry/coordinates/0",
                "a polygon ring must end",
            ),
            (
                json!({"type": "MultiPolygon", "coordinates": [[]]}),
                "/geometry/coordinates/0",
                "non-empty",
            ),
            (
                json!({"type": "GeometryCollection", "geometries": []}),
                "/geometry/geometries",
                "empty",
            ),
            (
                json!({"type": "GeometryCollection"}),
                "/geometry/geometries",
                "array",
            ),
            (
                json!({"type": "GeometryCollection", "geometries": [{"type": "GeometryCollection", "geometries": [point()]}]}),
                "/geometry/geometries/0",
                "must not contain another",
            ),
            (
                json!({"type": "GeometryCollection", "geometries": [{"type": "Point", "coordinates": [0, 91]}]}),
                "/geometry/geometries/0/coordinates",
                "latitude",
            ),
        ] {
            rejects_with(geometry(g), pointer, text);
        }
    }
}
