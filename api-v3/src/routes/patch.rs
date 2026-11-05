use serde::{Deserialize, Deserializer};

/// A field in a PATCH body, with JSON Merge Patch (RFC 7396) semantics: an absent member leaves
/// the stored value alone, `null` clears it, and a value sets it.
///
/// Fields of this type need `#[serde(default)]`, so that an absent member deserializes as
/// `Absent`. Without it, an absent member is a "missing field" error rather than a silent change.
/// For the OpenAPI document, they also need `#[schema(value_type = Option<T>)]`.
#[derive(Clone, Debug, Default, PartialEq)]
pub enum Patch<T> {
    #[default]
    Absent,
    Null,
    Value(T),
}

impl<T> Patch<T> {
    /// Whether the request names the field. For SQL that writes a column only when it does:
    /// `col = CASE WHEN $1 THEN $2 ELSE col END`, binding `is_set()` and `into_option()`.
    pub fn is_set(&self) -> bool {
        !matches!(self, Patch::Absent)
    }

    /// The new value, or None to clear the field (or when it's absent; check `is_set` first).
    pub fn into_option(self) -> Option<T> {
        match self {
            Patch::Value(v) => Some(v),
            Patch::Absent | Patch::Null => None,
        }
    }
}

impl<'de, T: Deserialize<'de>> Deserialize<'de> for Patch<T> {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        Ok(match Option::<T>::deserialize(d)? {
            None => Patch::Null,
            Some(v) => Patch::Value(v),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};
    use utoipa::{PartialSchema, ToSchema};

    #[derive(Debug, Deserialize, ToSchema)]
    struct Change {
        #[serde(default)]
        #[schema(value_type = Option<String>)]
        name: Patch<String>,
    }

    fn parse(body: Value) -> Patch<String> {
        serde_json::from_value::<Change>(body).unwrap().name
    }

    #[test]
    fn distinguishes_absent_null_and_value() {
        assert_eq!(parse(json!({})), Patch::Absent);
        assert_eq!(parse(json!({"name": null})), Patch::Null);
        assert_eq!(parse(json!({"name": "x"})), Patch::Value("x".into()));
    }

    #[test]
    fn sql_binding() {
        assert_eq!(
            (
                Patch::<u8>::Absent.is_set(),
                Patch::<u8>::Absent.into_option()
            ),
            (false, None)
        );
        assert_eq!(
            (Patch::<u8>::Null.is_set(), Patch::<u8>::Null.into_option()),
            (true, None)
        );
        assert_eq!(
            (Patch::Value(1).is_set(), Patch::Value(1).into_option()),
            (true, Some(1))
        );
    }

    #[test]
    fn schema_is_an_optional_nullable_value() {
        let schema = serde_json::to_value(Change::schema()).unwrap();
        assert_eq!(
            schema["properties"]["name"]["type"],
            json!(["string", "null"])
        );
        assert!(schema.get("required").is_none(), "{schema}");
    }
}
