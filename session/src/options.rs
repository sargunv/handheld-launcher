//! Core options: the settings a core declares, their values, and the host's
//! overrides.

use std::collections::HashMap;
use std::ffi::{CString, c_char};

use crate::cstr::{opt_string, string};
use crate::ffi;

/// A setting as the core declares it.
struct Declared {
    key: String,
    /// The values it accepts, in the core's order.
    values: Vec<String>,
    /// The value it takes unless the host picks another.
    default: String,
}

/// The options a core declared, with their current values.
#[derive(Debug, Default)]
pub(crate) struct Options {
    options: Vec<Entry>,
    /// Values the host set, applied whenever the core declares the key.
    overrides: HashMap<String, String>,
    /// Whether a value changed since the core last asked.
    updated: bool,
}

#[derive(Debug)]
struct Entry {
    key: String,
    values: Vec<String>,
    /// The current value, kept alive for the pointer the core receives.
    current: CString,
}

impl Options {
    pub(crate) fn new(overrides: HashMap<String, String>) -> Self {
        Self {
            overrides,
            ..Self::default()
        }
    }

    /// Replaces the declared options. A core declares all its options at once.
    fn declare(&mut self, declared: Vec<Declared>) {
        self.options = declared
            .into_iter()
            .filter_map(|option| {
                let value = match self.overrides.get(&option.key) {
                    Some(value) if option.values.contains(value) => value.clone(),
                    Some(value) => {
                        log::warn!(
                            "core option {} has no value {value:?}; using {:?}",
                            option.key,
                            option.default
                        );
                        option.default
                    }
                    None => option.default,
                };
                Some(Entry {
                    current: CString::new(value).ok()?,
                    key: option.key,
                    values: option.values,
                })
            })
            .collect();
        self.updated = true;
        log::debug!(
            "core declared options: {:?}",
            self.options
                .iter()
                .map(|e| (&e.key, &e.current))
                .collect::<Vec<_>>()
        );
    }

    /// Sets an option's value. It returns false, and changes nothing, if the
    /// core didn't declare the option or doesn't accept the value. The value
    /// also applies if the core declares its options again.
    pub(crate) fn set(&mut self, key: &str, value: &str) -> bool {
        let Some(entry) = self.options.iter_mut().find(|e| e.key == key) else {
            return false;
        };
        if !entry.values.iter().any(|v| v == value) {
            return false;
        }
        let Ok(current) = CString::new(value) else {
            return false;
        };
        entry.current = current;
        self.overrides.insert(key.to_owned(), value.to_owned());
        self.updated = true;
        true
    }

    /// The value for `key` as a pointer that stays valid until the value
    /// changes, or `None` if the core never declared it.
    pub(crate) fn get(&self, key: &str) -> Option<*const c_char> {
        let entry = self.options.iter().find(|e| e.key == key)?;
        Some(entry.current.as_ptr())
    }

    /// Whether a value changed since the last call.
    pub(crate) fn take_updated(&mut self) -> bool {
        std::mem::take(&mut self.updated)
    }

    /// Declares options from `RETRO_ENVIRONMENT_SET_VARIABLES`.
    ///
    /// # Safety
    ///
    /// `vars` points to an array of `retro_variable` that ends with a null key.
    pub(crate) unsafe fn declare_v0(&mut self, mut vars: *const ffi::retro_variable) {
        let mut declared = Vec::new();
        // SAFETY: the caller guarantees a terminated array of valid strings.
        unsafe {
            while !vars.is_null() && !(*vars).key.is_null() {
                let key = string((*vars).key);
                let spec = string((*vars).value);
                if let Some(option) = parse_v0(key, &spec) {
                    declared.push(option);
                }
                vars = vars.add(1);
            }
        }
        self.declare(declared);
    }

    /// Declares options from `RETRO_ENVIRONMENT_SET_CORE_OPTIONS`.
    ///
    /// # Safety
    ///
    /// `defs` points to an array of definitions that ends with a null key.
    pub(crate) unsafe fn declare_v1(&mut self, mut defs: *const ffi::retro_core_option_definition) {
        let mut declared = Vec::new();
        // SAFETY: the caller guarantees a terminated array of valid strings.
        unsafe {
            while !defs.is_null() && !(*defs).key.is_null() {
                let d = &*defs;
                declared.extend(option_from_parts(d.key, &d.values, d.default_value));
                defs = defs.add(1);
            }
        }
        self.declare(declared);
    }

    /// Declares options from `RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2`.
    ///
    /// # Safety
    ///
    /// `options` is null or points to valid v2 options whose definitions end
    /// with a null key.
    pub(crate) unsafe fn declare_v2(&mut self, options: *const ffi::retro_core_options_v2) {
        let mut declared = Vec::new();
        // SAFETY: the caller guarantees a terminated array of valid strings.
        unsafe {
            if let Some(options) = options.as_ref() {
                let mut defs = options.definitions;
                while !defs.is_null() && !(*defs).key.is_null() {
                    let d = &*defs;
                    declared.extend(option_from_parts(d.key, &d.values, d.default_value));
                    defs = defs.add(1);
                }
            }
        }
        self.declare(declared);
    }
}

/// Parses a v0 variable's `"Description; value1|value2"`, where the first
/// value is the default.
fn parse_v0(key: String, spec: &str) -> Option<Declared> {
    let Some((_, values)) = spec.split_once("; ") else {
        log::warn!("core variable {key} has a malformed value {spec:?}");
        return None;
    };
    let values: Vec<String> = values.split('|').map(str::to_owned).collect();
    Some(Declared {
        key,
        default: values.first()?.clone(),
        values,
    })
}

/// Builds an option from a v1 or v2 definition's fields. The default falls
/// back to the first value when it's missing or not among the values.
///
/// # Safety
///
/// Every pointer is null or a valid C string, and `values` ends with a null
/// value or fills the array.
unsafe fn option_from_parts(
    key: *const c_char,
    values: &[ffi::retro_core_option_value],
    default: *const c_char,
) -> Option<Declared> {
    // SAFETY: forwarded from the caller.
    unsafe {
        let key = string(key);
        let values: Vec<String> = values
            .iter()
            .take_while(|v| !v.value.is_null())
            .map(|v| string(v.value))
            .collect();
        let default = opt_string(default)
            .filter(|d| values.contains(d))
            .or_else(|| values.first().cloned());
        let Some(default) = default else {
            log::warn!("core option {key} has no values");
            return None;
        };
        Some(Declared {
            key,
            values,
            default,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn value(options: &Options, key: &str) -> Option<String> {
        options
            .get(key)
            // SAFETY: `get` returns a live C string.
            .map(|p| unsafe { string(p) })
    }

    #[test]
    fn v0_defaults_to_the_first_value() {
        let vars = [
            ffi::retro_variable {
                key: c"speed".as_ptr(),
                value: c"Speed; normal|fast|slow".as_ptr(),
            },
            ffi::retro_variable {
                key: c"broken".as_ptr(),
                value: c"no separator".as_ptr(),
            },
            ffi::retro_variable::default(),
        ];
        let mut options = Options::default();
        // SAFETY: a terminated array of static strings.
        unsafe { options.declare_v0(vars.as_ptr()) };
        assert_eq!(value(&options, "speed").as_deref(), Some("normal"));
        assert_eq!(value(&options, "broken"), None);
        assert_eq!(value(&options, "missing"), None);
    }

    #[test]
    fn v2_uses_the_declared_default() {
        let mut def = ffi::retro_core_option_v2_definition {
            key: c"palette".as_ptr(),
            desc: c"Palette".as_ptr(),
            default_value: c"green".as_ptr(),
            ..Default::default()
        };
        def.values[0] = ffi::retro_core_option_value {
            value: c"grey".as_ptr(),
            label: c"Grey".as_ptr(),
        };
        def.values[1] = ffi::retro_core_option_value {
            value: c"green".as_ptr(),
            label: std::ptr::null(),
        };
        let defs = [def, Default::default()];
        let v2 = ffi::retro_core_options_v2 {
            categories: std::ptr::null_mut(),
            definitions: defs.as_ptr().cast_mut(),
        };
        let mut options = Options::default();
        // SAFETY: valid v2 options with a terminated definition array.
        unsafe { options.declare_v2(&v2) };
        assert_eq!(value(&options, "palette").as_deref(), Some("green"));
    }

    #[test]
    fn overrides_apply_when_valid_and_flag_updates() {
        let vars = [
            ffi::retro_variable {
                key: c"a".as_ptr(),
                value: c"A; x|y".as_ptr(),
            },
            ffi::retro_variable {
                key: c"b".as_ptr(),
                value: c"B; x|y".as_ptr(),
            },
            ffi::retro_variable::default(),
        ];
        let overrides = HashMap::from([
            ("a".to_owned(), "y".to_owned()),
            ("b".to_owned(), "z".to_owned()),
        ]);
        let mut options = Options::new(overrides);
        // SAFETY: a terminated array of static strings.
        unsafe { options.declare_v0(vars.as_ptr()) };
        assert_eq!(value(&options, "a").as_deref(), Some("y"));
        assert_eq!(value(&options, "b").as_deref(), Some("x"));

        assert!(options.take_updated());
        assert!(!options.take_updated());
        assert!(!options.set("b", "z"));
        assert!(!options.set("c", "x"));
        assert!(!options.take_updated());
        assert!(options.set("b", "y"));
        assert!(options.take_updated());
        assert_eq!(value(&options, "b").as_deref(), Some("y"));
    }
}
