# StationAPI compile stubs -- NOT SHIPPED, NOT RUN

These are signature-only stand-ins for the handful of StationAPI members the four mixins under
`com.periut.retrodragon.window.mixin.stapi` reference. They exist so a build does not have to
download and remap StationAPI (129 MB, and about 86 seconds on a cold cache) to compile four
compatibility mixins against an API that has not moved in years.

They are a SEPARATE SOURCE SET. Their output is on the main compile classpath and nowhere else: not
in the jar, not on any runtime classpath. `verifyNoStubsInJar` fails the build if a
`net/modificationstation` class ever reaches an output jar, because a stub that shipped would shadow
the real StationAPI and break every one of its consumers.

## Keeping them honest

Every signature here was copied from `javap` of StationAPI 2.0.0-alpha.6.2 and is reproduced exactly
-- same names, same descriptors, same static-ness. Bodies throw, because nothing ever calls them:
Mixin resolves `@Shadow`, `@Accessor`, `@Invoker` and `@Overwrite` against the descriptor, and at
runtime the real class is the one present.

**To check them against the real thing:** `./gradlew build -Pstapi=real`. That swaps the stubs out
for the actual dependency, so a signature that has drifted becomes a compile error. Worth running
after a StationAPI bump; not worth paying for on every clone.
