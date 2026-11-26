//! Codes a header stores as a number or a character, each with the meaning
//! its format gives it.

/// A code stored as a number. Its `name` is what the format calls the value;
/// a value the format doesn't define shows as hex.
macro_rules! numeric_code {
    (
        $(#[$meta:meta])*
        $name:ident($repr:ty) {
            $($(#[$vmeta:meta])* $konst:ident = $value:literal => $label:literal,)*
        }
    ) => {
        $(#[$meta])*
        #[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
        pub struct $name(pub $repr);

        impl $name {
            $($(#[$vmeta])* pub const $konst: Self = Self($value);)*

            /// What the format calls this value, or None for one it doesn't
            /// define.
            pub fn name(self) -> Option<&'static str> {
                match self.0 {
                    $($value => Some($label),)*
                    _ => None,
                }
            }
        }

        impl core::fmt::Display for $name {
            fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
                match self.name() {
                    Some(name) => f.write_str(name),
                    None => write!(f, "{:#04x}", self.0),
                }
            }
        }
    };
}

/// A code stored as an ASCII character. Its `name` is what the format calls
/// the value; a value the format doesn't define shows as its character.
macro_rules! char_code {
    (
        $(#[$meta:meta])*
        $name:ident {
            $($(#[$vmeta:meta])* $konst:ident = $value:literal => $label:literal,)*
        }
    ) => {
        $(#[$meta])*
        #[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
        pub struct $name(pub u8);

        impl $name {
            $($(#[$vmeta])* pub const $konst: Self = Self($value);)*

            /// What the format calls this value, or None for one it doesn't
            /// define.
            pub fn name(self) -> Option<&'static str> {
                match self.0 {
                    $($value => Some($label),)*
                    _ => None,
                }
            }

            /// The character the header stores.
            pub fn char(self) -> char {
                self.0 as char
            }
        }

        impl core::fmt::Display for $name {
            fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
                match self.name() {
                    Some(name) => f.write_str(name),
                    None if self.0.is_ascii_graphic() => write!(f, "{}", self.0 as char),
                    None => write!(f, "{:#04x}", self.0),
                }
            }
        }
    };
}

pub(crate) use char_code;
pub(crate) use numeric_code;
