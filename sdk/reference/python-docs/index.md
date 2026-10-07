# SoulFire Python SDK

The API reference documents the SDK package, public modules, and generated protocol types.
Use the navigation or search to find a class, method, or type.

Start with the [Python tutorial](https://soulfiremc.com/docs/sdk/python).
Use the [SDK recipes](https://soulfiremc.com/docs/sdk/recipes) for complete programs.

SDK operations are lazy. Methods decorated with `@fn` return `Effect` values when called.
Their source signatures use `EffectGen` to describe the generator implementation.
Keep bot operations inside a scope and run the complete workflow at its boundary.
