package dev.sargunv.handheld.ui.about

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
  @Test
  fun readsAboutLibrariesComponentsByName() {
    val components =
      parseComponents(
        """
        {
          "libraries": [
            {"uniqueId": "org.example:zeta", "name": "zeta", "artifactVersion": "2.0",
             "licenses": ["Apache-2.0"], "developers": [], "funding": []},
            {"uniqueId": "crates.io:alpha@1.0.0", "name": "Alpha", "artifactVersion": "1.0.0",
             "description": "", "website": "https://example.org/alpha",
             "licenses": ["crate-MIT-1", "crate-Zlib-2"]},
            {"uniqueId": "org.example:unnamed", "licenses": ["missing"]}
          ],
          "licenses": {
            "Apache-2.0": {"name": "Apache License 2.0", "spdxId": "Apache-2.0",
                           "hash": "Apache-2.0", "content": "Apache text"},
            "crate-MIT-1": {"name": "MIT License", "spdxId": "MIT", "content": "MIT text"},
            "crate-Zlib-2": {"name": "zlib License", "content": "Zlib text"}
          }
        }
        """,
      )
    assertEquals(
      listOf(
        Component(
          id = "crates.io:alpha@1.0.0",
          name = "Alpha",
          version = "1.0.0",
          description = null,
          website = "https://example.org/alpha",
          licences =
            listOf(
              Licence("MIT", "MIT License", "MIT text"),
              Licence("zlib License", "zlib License", "Zlib text"),
            ),
        ),
        Component(
          id = "org.example:unnamed",
          name = "org.example:unnamed",
          version = null,
          description = null,
          website = null,
          licences = emptyList(),
        ),
        Component(
          id = "org.example:zeta",
          name = "zeta",
          version = "2.0",
          description = null,
          website = null,
          licences = listOf(Licence("Apache-2.0", "Apache License 2.0", "Apache text")),
        ),
      ),
      components,
    )
  }
}
