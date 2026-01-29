This directory contains "advanced" configurations for Dicer `Target`s. These are optional extensions
to the `Target` configurations found at [dicer/external/config](../../external/config), and follow
the same general pattern.

This directory is organized into subdirectories for each environment, i.e., `dev` (includes test
shards), `staging`, and `prod`.

The header of each configuration file must be:

```
# proto-file: dicer/assigner/config/proto/advanced_target_config.proto
# proto-message: AdvancedTargetConfigP
```
which references the advanced target configuration proto definition file and message type.

Advanced configuration changes are made exclusively by the Caching team. Fields in
`advanced_target_config.proto` may be removed, replaced, or possibly promoted to the regular
target configuration proto definition file at any time.
