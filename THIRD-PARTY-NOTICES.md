# Third-party notices

The MIT license in [`LICENSE`](LICENSE) covers original Teletica project code. It does not replace the terms for the third-party material listed below.

## Brand image

`app/src/main/res/drawable-nodpi/teletica_logo_source.png` and derived launcher artwork use the Teletica logo documented at [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Teletica_Logo.png). The file page identifies Teletica as author and teletica.com as source, and describes the simple text logo as public domain. The page also notes that other restrictions, such as trademark rights, may still apply.

## Direct software dependencies

| Dependency | License | License/source |
| --- | --- | --- |
| AndroidX Core KTX, TV Provider, DocumentFile, and AndroidX Test libraries | Apache-2.0 | [AndroidX license](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) |
| AndroidX Media3 ExoPlayer, HLS, and UI | Apache-2.0 | [AndroidX Media license](https://github.com/androidx/media/blob/release/LICENSE) |
| XZ for Java (`org.tukaani:xz:1.10`) | 0BSD | [XZ for Java license](https://github.com/tukaani-project/xz-java/blob/master/LICENSES/0BSD.txt) |
| JUnit 4 (`junit:junit:4.13.2`, test scope) | EPL-1.0 | [JUnit 4 license](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) |

Gradle also resolves transitive dependencies. Review the resolved dependency tree and the license notices shipped with those artifacts before distributing each release. Build tools and dependencies used only by tests are not packaged in the app APK/AAB.
