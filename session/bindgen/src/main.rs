//! UniFFI's binding generator. The Gradle build runs it to generate the
//! session's Kotlin bindings.

fn main() {
    uniffi::uniffi_bindgen_main()
}
